package com.rinke.solutions.io;

import static com.rinke.solutions.io.Result.Code.*;
import static com.rinke.solutions.io.Result.*;
import static com.rinke.solutions.io.HeatShrinkDecoder.State.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * java implementation of the heatshrink compression algorithm by https://github.com/atomicobject/heatshrink
 * @author Stefan Rinke
 */
public class HeatShrinkDecoder {
	
	private static final Logger log = LoggerFactory.getLogger(HeatShrinkDecoder.class);
	private static final int NO_BITS = -1;
	
	enum State {
	    HSDS_TAG_BIT,               /* tag bit */
	    HSDS_YIELD_LITERAL,         /* ready to yield literal byte */
	    HSDS_BACKREF_INDEX_MSB,     /* most significant byte of index */
	    HSDS_BACKREF_INDEX_LSB,     /* least significant byte of index */
	    HSDS_BACKREF_COUNT_MSB,     /* most significant byte of count */
	    HSDS_BACKREF_COUNT_LSB,     /* least significant byte of count */
	    HSDS_YIELD_BACKREF,         /* ready to yield back-reference */
	};
	
	private int inputSize;        /* bytes in input buffer */
	private int inputIndex;       /* offset to next unprocessed input byte */
	private int outputCount;      /* how many bytes to output */
	private int outputIndex;      /* index for bytes to output */
	private int headIndex;        /* head of window buffer */
	private State state;              /* current state machine node */
	private int currentByte;       /* current byte of input */
	private int bitIndex;          /* current bit index */

    /* Fields that are only used if dynamically allocated. */
	private int windowSize;         /* window buffer bits */
    private int lookaheadSize;      /* lookahead bits */
    private int inputBufferSize; /* input buffer size */

    /* Input buffer, then expansion window buffer */
    private byte buffer[];

	public HeatShrinkDecoder(int windowSize, int lookaheadSize, int input_buffer_size) {
		super();
		this.windowSize = windowSize;
		this.lookaheadSize = lookaheadSize;
	    //int buffers_sz = (1 << windowSize) + input_buffer_size;
	    inputBufferSize = input_buffer_size;
		reset();
	}

	public Result finish() {
		switch (state) {
		case HSDS_TAG_BIT:
			return inputSize == 0 ? res(DONE) : res(MORE);

			/*
			 * If we want to finish with no input, but are in these states, it's
			 * because the 0-bit padding to the last byte looks like a backref
			 * marker bit followed by all 0s for index and count bits.
			 */
		case HSDS_BACKREF_INDEX_LSB:
		case HSDS_BACKREF_INDEX_MSB:
		case HSDS_BACKREF_COUNT_LSB:
		case HSDS_BACKREF_COUNT_MSB:
			return inputSize == 0 ? res(DONE) : res(MORE);

			/*
			 * If the output stream is padded with 0xFFs (possibly due to being
			 * in flash memory), also explicitly check the input size rather
			 * than uselessly returning MORE but yielding 0 bytes when polling.
			 */
		case HSDS_YIELD_LITERAL:
			return inputSize == 0 ? res(DONE) : res(MORE);

		default:
			return res(MORE);
		}
		
	}

    public void reset() {
        int buf_sz = 1 << windowSize;
        int input_sz = inputBufferSize;
        buffer = new byte[buf_sz + input_sz];
        state = HSDS_TAG_BIT;
        inputSize = 0;
        inputIndex = 0;
        bitIndex = 0x00;
        currentByte = 0x00;
        outputCount = 0;
        outputIndex = 0;
        headIndex = 0;
    }
    
    public Result sink(byte inBuffer[], int offset, int size) {
        if ( inBuffer == null) {
        	throw new IllegalArgumentException("inBuffer must not be null");
        }

        int rem = inputBufferSize - inputSize;
        if (rem == 0) {
            return res(0, FULL);
        }
        
       // int size = in_buf.length;
        size = rem < size ? rem : size;
        log.debug("-- sinking {} bytes", size);
        /* copy into input buffer (at head of buffers) */
        //memcpy(&hsd->buffers[hsd->input_size], in_buf, size);
        System.arraycopy(inBuffer, offset, buffer, inputSize, size);
        inputSize += size;
        return res(size,OK);
    }
    
	public void decode(InputStream is, OutputStream os) throws IOException {
		byte[] inbuffer = new byte[1<<windowSize];
		byte[] outbuffer = new byte[4<<windowSize];
		int inputOffset = 0;
		int remainingInInput = 0;
		Result res = res(OK);
		while( true ) {
			do { // read and fill input buffer until full
				if( remainingInInput == 0 ) {
					// read some input bytes
					remainingInInput = is.read(inbuffer);
					inputOffset = 0;
				}
				if( remainingInInput < 0 ) {
					res = finish();
					break;
				}
				res = sink(inbuffer, inputOffset, remainingInInput);
				if( res.isError() ) throw new RuntimeException("error sink");
				remainingInInput -= res.count;
				inputOffset += res.count;
			} while( res.code != FULL );
			
			if( res.code == DONE ) break;
			// now input buffer is full, poll for output
			do {
				res = poll(outbuffer);
				if( res.isError()) throw new RuntimeException("error poll");
				if( res.count > 0 ) {
					os.write(outbuffer, 0, res.count);
				}
			} while( res.code == MORE );
			//if( res.code == DONE ) break;
		}
		
	}

    /**
     * poll decoded bytes into outBuffer.
     * @param outBuffer must not be null
     * @return result: count byte were polled.
     */
    public Result poll( byte[] outBuffer /*, int offset , size_t *output_size*/) {
        if (outBuffer == null) {
            throw new IllegalArgumentException("outbuffer must not be null");
        }
        
        int outBufSize = outBuffer.length;
        
        OutputInfo oi = new OutputInfo();
        oi.buf = outBuffer;
        oi.bufSize = outBufSize;
        oi.outputSize = 0;

        while (true) {
            log.debug("-- poll, state is {} ({}), input_size {}",
                state.ordinal(), state.name(), inputSize);
            State in_state = state;
            switch (in_state) {
            case HSDS_TAG_BIT:
                state = tagBit();
                break;
            case HSDS_YIELD_LITERAL:
                state = yieldLiteral(oi);
                break;
            case HSDS_BACKREF_INDEX_MSB:
                state = backrefIndexMsb();
                break;
            case HSDS_BACKREF_INDEX_LSB:
                state = backrefIndexLsb();
                break;
            case HSDS_BACKREF_COUNT_MSB:
                state = backrefCountMsb();
                break;
            case HSDS_BACKREF_COUNT_LSB:
                state = backrefCountLsb();
                break;
            case HSDS_YIELD_BACKREF:
                state = yieldBackref(oi);
                break;
            default:
                return res(ERROR_UNKNOWN);
            }
            
            /* If the current state cannot advance, check if input or output
             * buffer are exhausted. */
            if (state == in_state) {
				if (oi.outputSize == outBufSize)
					return res(oi.outputSize, MORE);
                return res(oi.outputSize, EMPTY);
            }
        }
    }

	private State yieldBackref(OutputInfo oi) {
	    int count = oi.bufSize - oi.outputSize;
	    if (count > 0) {
	        int i = 0;
	        if (outputCount < count) count = outputCount;
	        //uint8_t *buf = &buffers[input_buffer_size];
	        int mask = (1 << windowSize) - 1;
	        
	        int neg_offset = outputIndex;
	        
	        log.debug("-- emitting {} bytes from {} bytes back", count, neg_offset);
//	        ASSERT(neg_offset <= mask + 1);
//	        ASSERT(count <= (size_t)(1 << BACKREF_COUNT_BITS(hsd)));

	        for (i=0; i<count; i++) {
	            byte c = buffer[inputBufferSize + ((headIndex - neg_offset) & mask)];
	            pushByte(oi, c);
	            buffer[inputBufferSize + (headIndex & mask)] = c;
	            headIndex++;
	            log.debug("  -- ++ 0x{}\n", c);
	        }
	        outputCount -= count;
	        if (outputCount == 0) { return HSDS_TAG_BIT; }
	    }
	    return HSDS_YIELD_BACKREF;
	}

	private void pushByte(OutputInfo oi, byte c) {
	    log.debug(" -- pushing byte: 0x%02x ('%c')", c, isPrint(c) ? c : '.');
	    oi.buf[oi.outputSize++] = c;
	}

	private State backrefCountLsb() {
	    int br_bit_ct = lookaheadSize;
	    int bits = getBits(br_bit_ct < 8 ? br_bit_ct : 8);
	    log.debug("-- backref count (lsb), got 0x{} (+1)", bits);
	    if (bits == NO_BITS) { return HSDS_BACKREF_COUNT_LSB; }
	    outputCount |= bits;
	    outputCount++;
	    return HSDS_YIELD_BACKREF;
	}

	/* Get the next COUNT bits from the input buffer, saving incremental progress.
	 * Returns NO_BITS on end of input, or if more than 15 bits are requested. */
	private int getBits(int count) {
	    int accumulator = 0;
	    int i = 0;
	    if (count > 15) { return NO_BITS; }
	    log.debug("-- popping {} bit(s)", count);

	    /* If we aren't able to get COUNT bits, suspend immediately, because we
	     * don't track how many bits of COUNT we've accumulated before suspend. */
	    if (inputSize == 0) {
	        if (bitIndex < (1 << (count - 1))) { return NO_BITS; }
	    }

	    for (i = 0; i < count; i++) {
	        if (bitIndex == 0x00) {
	            if (inputSize == 0) {
	            	log.debug("  -- out of bits, suspending w/ accumulator of {} (0x{})",
	                    accumulator, accumulator);
	                return NO_BITS;
	            }
	            currentByte = buffer[inputIndex++];
	            log.debug("  -- pulled byte 0x{}", currentByte);
	            if (inputIndex == inputSize) {
	                inputIndex = 0; /* input is exhausted */
	                inputSize = 0;
	            }
	            bitIndex = 0x80;
	        }
	        accumulator <<= 1;
	        if ((currentByte & bitIndex)!=0) {
	            accumulator |= 0x01;
//	            if (0) {
//	            	log.debug("  -- got 1, accumulator 0x%04x, bit_index 0x%02x\n",
//	                accumulator, bit_index);
//	            }
	        } else {
//	            if (0) {
//	            	log.debug("  -- got 0, accumulator 0x%04x, bit_index 0x%02x\n",
//	                accumulator, bit_index);
//	            }
	        }
	        bitIndex >>= 1;
	    }

	    if (count > 1) { log.debug("  -- accumulated {}", accumulator); }
	    return accumulator;
	}

	private State backrefCountMsb() {
	    int br_bit_ct = lookaheadSize;
	    assert(br_bit_ct > 8);
	    int bits = getBits( br_bit_ct - 8);
	    log.debug("-- backref count (msb), got 0x{} (+1)", Integer.toHexString(bits));
	    if (bits == NO_BITS) { return HSDS_BACKREF_COUNT_MSB; }
	    outputCount = bits << 8;
	    return HSDS_BACKREF_COUNT_LSB;
	}

	private State backrefIndexLsb() {
	    int bit_ct = windowSize;
	    int bits = getBits(bit_ct < 8 ? bit_ct : 8);
	    log.debug("-- backref index (lsb), got 0x{} (+1)", Integer.toHexString(bits));
	    if (bits == NO_BITS) { return HSDS_BACKREF_INDEX_LSB; }
	    outputIndex |= bits;
	    outputIndex++;
	    int br_bit_ct = lookaheadSize;
	    outputCount = 0;
	    return (br_bit_ct > 8) ? HSDS_BACKREF_COUNT_MSB : HSDS_BACKREF_COUNT_LSB;
	}

	private State backrefIndexMsb() {
	    int bit_ct = windowSize;
	    assert(bit_ct > 8);
	    int bits = getBits( bit_ct - 8);
	    log.debug("-- backref index (msb), got 0x{} (+1)", Integer.toHexString(bits));
	    if (bits == NO_BITS) { return HSDS_BACKREF_INDEX_MSB; }
	    outputIndex = bits << 8;
	    return HSDS_BACKREF_INDEX_LSB;
	}
	
	private boolean isPrint(byte c) {
		return c> 0x1f && c < 127;
	}


	private State yieldLiteral(OutputInfo oi) {
	    /* Emit a repeated section from the window buffer, and add it (again)
	     * to the window buffer. (Note that the repetition can include
	     * itself.)*/
	    if (oi.outputSize < oi.bufSize) {
	        int b = getBits( 8);
	        if (b == NO_BITS) { return HSDS_YIELD_LITERAL; } /* out of input */
	        
	        //uint8_t *buf = &hsd->buffers[input_buffer_size];
	        int mask = (1 << windowSize)  - 1;
	        byte c = (byte)(b & 0xFF);
	        log.debug("-- emitting literal byte 0x{} ('{}')\n", c, isPrint(c) ? c : '.');
	        buffer[inputBufferSize + (headIndex++ & mask)] = c;
	        pushByte(oi, c);
	        return HSDS_TAG_BIT;
	    } else {
	        return HSDS_YIELD_LITERAL;
	    }
	}

	private State tagBit() {
	    int bits = getBits(1);  // get tag bit
	    if (bits == NO_BITS) {
	        return HSDS_TAG_BIT;
	    } else if (bits!=0) {
	        return HSDS_YIELD_LITERAL;
	    } else if (windowSize > 8) {
	        return HSDS_BACKREF_INDEX_MSB;
	    } else {
	        outputIndex = 0;
	        return HSDS_BACKREF_INDEX_LSB;
	    }
	}
	

}
