package com.rinke.solutions.io;


import static com.rinke.solutions.io.Result.Code.*;
import static com.rinke.solutions.io.Result.*;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * java implementation of the heatshrink compression algorithm by https://github.com/atomicobject/heatshrink
 * @author Stefan Rinke
 */
public class HeatShrinkEncoder {
	
	private static final Logger log = LoggerFactory.getLogger(HeatShrinkEncoder.class);

	/**
	 * couple of convenience factory methods for results
	 * @return
	 */

	static Result res() {
		return new Result(0, ERROR_NULL);
	}

	static Result res(int count, Code res) {
		return new Result(count, res);
	}

	static Result res(Code res) {
		return new Result(0, res);
	}

	enum State {
		HSES_NOT_FULL,			/* input buffer not full enough */
		HSES_FILLED, 			/* buffer is full */
		HSES_SEARCH, 			/* searching for patterns */
		HSES_YIELD_TAG_BIT, 	/* yield tag bit */
		HSES_YIELD_LITERAL, 	/* emit literal byte */
		HSES_YIELD_BR_INDEX, 	/* yielding backref index */
		HSES_YIELD_BR_LENGTH, 	/* yielding backref length */
		HSES_SAVE_BACKLOG, 		/* copying buffer to backlog */
		HSES_FLUSH_BITS, 		/* flush bit buffer */
		HSES_DONE, 				/* done */
	}

	private final int MATCH_NOT_FOUND = -1;

	private int index[];

	private static final int FLAG_IS_FINISHING = 1;
	private static final byte HEATSHRINK_LITERAL_MARKER = 0x01;
	private static final byte HEATSHRINK_BACKREF_MARKER = 0x00;

	int inputSize; /* bytes in input buffer */
	Match match = new Match(0, 0);
	int outgoingBits; /* enqueued outgoing bits */
	int outgoingBitsCount;
	int flags;
	State state; /* current state machine node */
	int currentByte; /* current byte of output */
	int bitIndex; /* current bit index */

	int windowSize;
	int lookAhead;

	/* input buffer and / sliding window for expansion */
	byte buffer[];// = new byte[2 << HEATSHRINK_STATIC_WINDOW_BITS];

	private boolean useIndex = true;

	public HeatShrinkEncoder(int windowSize, int lookAhead) {
		super();
		this.windowSize = windowSize;
		this.lookAhead = lookAhead;
		buffer = new byte[2 << windowSize];
		index = new int[2 << windowSize];
		reset();
	}

	public void reset() {
		Arrays.fill(buffer, (byte) 0);
		inputSize = 0;
		state = State.HSES_NOT_FULL;
		flags = 0;
		bitIndex = 0x80;
		currentByte = 0x00;
		match.scanIndex = 0;
		match.length = 0;
		outgoingBits = 0x0000;
		outgoingBitsCount = 0;
		index = new int[2 << windowSize];
	}

	/*
	 * Sink up to SIZE bytes from IN_BUF into the encoder. INPUT_SIZE is set to
	 * the number of bytes actually sunk (in case a buffer was filled.).
	 */
	public Result sink(byte[] inputBuffer, int offset, int size /* , size_t *input_size */) {
		if (inputBuffer == null) {
			return res(ERROR_NULL);
		}
		/* Sinking more content after saying the content is done, tsk tsk */
		if (isFinishing()) {
			return res(ERROR_MISUSE);
		}

		/* Sinking more content before processing is done */
		if (state != State.HSES_NOT_FULL) {
			return res(ERROR_MISUSE);
		}

		int writeOffset = getInputOffset() + inputSize;
		int inputBufferSize = getInputBufferSize();
		int remain = inputBufferSize - inputSize;
		int copySize = remain < size ? remain : size;

		// memcpy(&hse->buffer[write_offset], in_buf, cp_sz);
		System.arraycopy(inputBuffer, offset, buffer, writeOffset, copySize);
		// *input_size = cp_sz;

		inputSize += copySize;

		log.debug("-- sunk {} bytes (of {}) into encoder at {}, input buffer now has {}", copySize, size, writeOffset, inputSize);
		if (copySize == remain) {
			log.debug("-- internal buffer is now full");
			state = State.HSES_FILLED;
			return res(copySize, FULL);
		}

		return res(copySize, OK);
	}

	private int getInputBufferSize() {
		return 1 << windowSize;
	}

	private int getInputOffset() {
		return getInputBufferSize(); //
	}

	private boolean isFinishing() {
		return (flags & FLAG_IS_FINISHING) != 0;
	}

	/*
	 * Poll for output from the encoder, copying at most OUT_BUF_SIZE bytes into
	 * OUT_BUF (setting *OUTPUT_SIZE to the actual amount copied).
	 */
	public Result poll(byte[] out_buf /* size_t *output_size */) {
		if (out_buf == null) {
			return res(ERROR_NULL);
		}
		
		int outBufSize = out_buf.length;

		if (outBufSize == 0) {
			log.debug("-- MISUSE: output buffer size is 0");
			return res(ERROR_MISUSE);
		}

		OutputInfo oi = new OutputInfo();
		oi.buf = out_buf;
		oi.bufSize = out_buf.length;
		oi.outputSize = 0;

		while (true) {
			log.debug("-- polling, state {} ({}), flags 0x{}", state.ordinal(), state.name().toLowerCase().substring(5), flags);

			State inState = state;
			switch (inState) {
			case HSES_NOT_FULL:
				return res(oi.outputSize, EMPTY);
			case HSES_FILLED:
				doIndexing();
				state = State.HSES_SEARCH;
				break;
			case HSES_SEARCH:
				state = stepSearch();
				break;
			case HSES_YIELD_TAG_BIT:
				state = yieldTagBit(oi);
				break;
			case HSES_YIELD_LITERAL:
				state = yieldLiteral(oi);
				break;
			case HSES_YIELD_BR_INDEX:
				state = yieldBackRefIndex(oi);
				break;
			case HSES_YIELD_BR_LENGTH:
				state = yieldBackRefLength(oi);
				break;
			case HSES_SAVE_BACKLOG:
				state = saveBacklog();
				break;
			case HSES_FLUSH_BITS:
				state = flushBitBuffer(oi);
			case HSES_DONE:
				return res(oi.outputSize, EMPTY);
			default:
				log.debug("-- bad state {}", state.name());
				return res(oi.outputSize, ERROR_MISUSE);
			}

			if (state == inState) {
				/* Check if output buffer is exhausted. */
				if (oi.outputSize == outBufSize)
					return res(oi.outputSize, MORE);
			}
		}
		// return new PollRes(output_size, PollRes.Res.EMPTY);
	}

	private State flushBitBuffer(OutputInfo oi) {
		if (bitIndex == 0x80) {
			log.debug("-- done!");
			return State.HSES_DONE;
		} else if (canTakeByte(oi)) {
			log.debug("-- flushing remaining byte (bit_index == {})", bitIndex);
			oi.buf[oi.outputSize++] = (byte) currentByte;
			log.debug("-- done!");
			return State.HSES_DONE;
		} else {
			return State.HSES_FLUSH_BITS;
		}
	}

	private State saveBacklog() {
		log.debug("-- saving backlog");
		int inputBufferSize = getInputBufferSize();

		/*
		 * Copy processed data to beginning of buffer, so it can be used for
		 * future matches. Don't bother checking whether the input is less than
		 * the maximum size, because if it isn't, we're done anyway.
		 */
		int rem = inputBufferSize - match.scanIndex; // unprocessed bytes
		int shiftSize = inputBufferSize + rem;

		// memmove(&hse->buffer[0],
		// &hse->buffer[input_buf_sz - rem],
		// shift_sz);
		// to: buffer, from: buffer + (input_buf_sz - rem) 
		// amount: shift_sz
		int offset = inputBufferSize - rem;
		System.arraycopy(buffer, offset, buffer, 0, shiftSize);
		match.scanIndex = 0;
		inputSize -= offset;
		
		return State.HSES_NOT_FULL;
	}

	private State yieldBackRefLength(OutputInfo oi) {
		if (canTakeByte(oi)) {
			log.debug("-- yielding backref length {}", match.length);
			if (push_outgoing_bits(oi) > 0) {
				return State.HSES_YIELD_BR_LENGTH;
			} else {
				match.scanIndex += match.length;
				match.length = 0;
				return State.HSES_SEARCH;
			}
		} else {
			return State.HSES_YIELD_BR_LENGTH;
		}
	}

	private int push_outgoing_bits(OutputInfo oi) {
		int count = 0;
		byte bits = 0;
		if (outgoingBitsCount > 8) {
			count = 8;
			bits = (byte) (outgoingBits >> (outgoingBitsCount - 8));
		} else {
			count = outgoingBitsCount;
			bits = (byte) outgoingBits;
		}

		if (count > 0) {
			log.debug("-- pushing {} outgoing bits: 0x{}", count, Integer.toHexString(bits));
			pushBits(count, bits, oi);
			outgoingBitsCount -= count;
		}
		return count;
	}

	private State yieldBackRefIndex(OutputInfo oi) {
		if (canTakeByte(oi)) {
			log.debug("-- yielding backref index {}", match.pos);
			if (push_outgoing_bits(oi) > 0) {
				return State.HSES_YIELD_BR_INDEX; /* continue */
			} else {
				outgoingBits = match.length - 1;
				outgoingBitsCount = lookAhead;
				return State.HSES_YIELD_BR_LENGTH; /* done */
			}
		} else {
			return State.HSES_YIELD_BR_INDEX; /* continue */
		}
	}

	private State yieldLiteral(OutputInfo oi) {
		if (canTakeByte(oi)) {
			push_literal_byte(oi);
			return State.HSES_SEARCH;
		} else {
			return State.HSES_YIELD_LITERAL;
		}
	}

	private void push_literal_byte(OutputInfo oi) {
		int processedOffset = match.scanIndex - 1;
		int inputOffset = getInputOffset() + processedOffset;
		byte c = buffer[inputOffset];
		log.debug("-- yielded literal byte 0x{} ('{}') from {}", c, isPrint(c) ? c : '.', inputOffset);
		pushBits(8, c, oi);

	}

	private boolean isPrint(byte c) {
		return c> 0x1f && c < 127;
	}

	private State yieldTagBit(OutputInfo oi) {
		if (canTakeByte(oi)) {
			if (match.length == 0) {
				addTagBit(oi, HEATSHRINK_LITERAL_MARKER);
				return State.HSES_YIELD_LITERAL;
			} else {
				addTagBit(oi, HEATSHRINK_BACKREF_MARKER);
				outgoingBits = match.pos - 1;
				outgoingBitsCount = windowSize;
				return State.HSES_YIELD_BR_INDEX;
			}
		} else {
			return State.HSES_YIELD_TAG_BIT; /* output is full, continue */
		}
	}

	private boolean canTakeByte(OutputInfo oi) {
		return oi.outputSize < oi.bufSize;
	}

	private void addTagBit(OutputInfo oi, byte tag) {
		log.debug("-- adding tag bit: {}", tag);
		pushBits(1, tag, oi);
	}

	private void pushBits(int count, byte bits, OutputInfo oi) {
		log.debug("++ push_bits: {} bits, input of 0x{}", count, bits);

		/*
		 * If adding a whole byte and at the start of a new output byte, just
		 * push it through whole and skip the bit IO loop.
		 */
		if (count == 8 && bitIndex == 0x80) {
			oi.buf[oi.outputSize++] = bits;
		} else {
			for (int i = count - 1; i >= 0; i--) {
				boolean bit = (bits & (1 << i)) != 0;
				if (bit) {
					currentByte |= bitIndex;
				}
				// if (false) {
				// log.debug("  -- setting bit %d at bit index 0x%02x, byte => 0x%02x",
				// bit ? 1 : 0, bit_index, current_byte));
				// }
				bitIndex >>= 1;
				if (bitIndex == 0x00) {
					bitIndex = 0x80;
					log.debug(" > pushing byte 0x{}", currentByte);
					oi.buf[oi.outputSize++] = (byte) currentByte;
					currentByte = 0x00;
				}
			}
		}

	}

	private State stepSearch() {
		int window_length = getInputBufferSize();
		int lookahead_sz = getLookaheadSize();
		int msi = match.scanIndex;
		log.debug("## step_search, scan @ {} ({}/{}), input size {}", msi, inputSize + msi, 2 * window_length, inputSize);

		boolean fin = isFinishing();
		if (msi > inputSize - (fin ? 1 : lookahead_sz)) {
			/*
			 * Current search buffer is exhausted, copy it into the backlog and
			 * await more input.
			 */
			log.debug("-- end of search @ {}", msi);
			return fin ? State.HSES_FLUSH_BITS : State.HSES_SAVE_BACKLOG;
		}

		int input_offset = getInputOffset();
		int end = input_offset + msi;
		int start = end - window_length;

		int max_possible = lookahead_sz;
		if (inputSize - msi < lookahead_sz) {
			max_possible = inputSize - msi;
		}

		match = findLongestMatch(start, end, max_possible /* , &match_length */);

		if (match.pos == MATCH_NOT_FOUND) {
			log.debug("ss Match not found");
			match.scanIndex++;
			match.length = 0;
			return State.HSES_YIELD_TAG_BIT;
		} else {
			log.debug("ss Found match of {} bytes at {}", match.length, match.pos);
			// match_pos = match_pos;
			// match_length = match_length;
			// ASSERT(match_pos <= 1 << HEATSHRINK_ENCODER_WINDOW_BITS(hse)
			// /*window_length*/);

			return State.HSES_YIELD_TAG_BIT;
		}
	}

	private static class Match {
		public int pos;
		public int length;
		public int scanIndex;

		public Match(int pos, int length) {
			super();
			this.pos = pos;
			this.length = length;
		}
	}

	private Match findLongestMatch(int start, int end, int maxlen) {
		log.debug("-- scanning for match of buf[{}:{}] between buf[{}:{}] (max {} bytes)", 
				end, end + maxlen, start, end + maxlen - 1, maxlen);

		int match_maxlen = 0;
		int match_index = MATCH_NOT_FOUND;

		int len = 0;
		int needlepointIdx = end; // "points into buffer"

		if( useIndex ) {
			// struct hs_index *hsi = HEATSHRINK_ENCODER_INDEX(hse);
			int pos = index[end];

			while (pos - start >= 0) {
				int pospointIdx = pos; // "points into buffer"
				len = 0;

				/*
				 * Only check matches that will potentially beat the current maxlen.
				 * This is redundant with the index if match_maxlen is 0, but the
				 * added branch overhead to check if it == 0 seems to be worse.
				 */
				if (buffer[pospointIdx + match_maxlen] != buffer[needlepointIdx + match_maxlen]) {
					pos = index[pos];
					continue;
				}

				for (len = 1; len < maxlen; len++) {
					if (buffer[pospointIdx + len] != buffer[needlepointIdx + len])
						break;
				}

				if (len > match_maxlen) {
					match_maxlen = len;
					match_index = pos;
					if (len == maxlen) {
						break;
					} /* won't find better */
				}
				pos = index[pos];
			}
			
		} else {
		    for (int pos=end - 1; pos - start >= 0; pos--) {
		        int pospointIdx = pos;
		        
		        if ((buffer[pospointIdx+match_maxlen] == buffer[needlepointIdx+match_maxlen])
		            && (buffer[pospointIdx] == buffer[needlepointIdx])) {
		            for (len=1; len<maxlen; len++) {
//		                if (0) {
//		                    LOG("  --> cmp buf[%d] == 0x%02x against %02x (start %u)\n",
//		                        pos + len, pospoint[len], needlepoint[len], start);
//		                }
		                if (buffer[pospointIdx+len] != buffer[needlepointIdx+len]) { break; }
		            }
		            if (len > match_maxlen) {
		                match_maxlen = len;
		                match_index = pos;
		                if (len == maxlen) { break; } /* don't keep searching */
		            }
		        }
		    }

		} // use index
			

		int break_even_point = (1 + windowSize + lookAhead);

		/*
		 * Instead of comparing break_even_point against 8*match_maxlen, compare
		 * match_maxlen against break_even_point/8 to avoid overflow. Since
		 * MIN_WINDOW_BITS and MIN_LOOKAHEAD_BITS are 4 and 3, respectively,
		 * break_even_point/8 will always be at least 1.
		 */
		if (match_maxlen > (break_even_point / 8)) {
			log.debug("-- best match: {} bytes at {}", match_maxlen, end - match_index);
			match.length = match_maxlen;
			match.pos = end - match_index;
			return match;
		}
		log.debug("-- none found");
		match.pos = MATCH_NOT_FOUND;
		return match;
	}

	private int getLookaheadSize() {
		return 1 << lookAhead;
	}

	private void doIndexing() {
		/*
		 * Build an index array I that contains flattened linked lists for the
		 * previous instances of every byte in the buffer.
		 * 
		 * For example, if buf[200] == 'x', then index[200] will either be an
		 * offset i such that buf[i] == 'x', or a negative offset to indicate
		 * end-of-list. This significantly speeds up matching, while only using
		 * sizeof(uint16_t)*sizeof(buffer) bytes of RAM.
		 * 
		 * Future optimization options: 1. Since any negative value represents
		 * end-of-list, the other 15 bits could be used to improve the index
		 * dynamically.
		 * 
		 * 2. Likewise, the last lookahead_sz bytes of the index will not be
		 * usable, so temporary data could be stored there to dynamically
		 * improve the index.
		 */
		// struct hs_index *hsi = HEATSHRINK_ENCODER_INDEX(hse);
		int[] last = new int[256];
		Arrays.fill(last, -1); // memset(last, 0xFF, sizeof(last));

		int inputOffset = getInputOffset();
		int end = inputOffset + inputSize;

		for (int i = 0; i < end; i++) {
			int v = (int)(buffer[i] & 0xFF);
			int lv = last[v];
			index[i] = lv;
			last[v] = i;
		}
	}

	/*
	 * Notify the encoder that the input stream is finished. If the return value
	 * is HSER_FINISH_MORE, there is still more output, so call
	 * heatshrink_encoder_poll and repeat.
	 */
	public Result finish() {
		log.debug("-- setting is_finishing flag");
		flags |= FLAG_IS_FINISHING;
		if (state == State.HSES_NOT_FULL) {
			state = State.HSES_FILLED;
		}
		return state == State.HSES_DONE ? res(DONE) : res(MORE);
	}
	
	public static void main( String[] args ) throws Exception {
		InputStream is = new FileInputStream(args[0]);
		OutputStream os = new FileOutputStream(args[1]);
		byte[] inbuffer = new byte[1024];
		byte[] outbuffer = new byte[4096];
		//System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		HeatShrinkEncoder encoder = new HeatShrinkEncoder(10, 4);
		int inputOffset = 0;
		int remainingInInput = 0;
		Result res = res(OK);
		long start = System.currentTimeMillis();
		while( true ) {
			do { // read and fill input buffer until full
				if( remainingInInput == 0 ) {
					// read some input bytes
					remainingInInput = is.read(inbuffer);
					inputOffset = 0;
				}
				if( remainingInInput < 0 ) {
					res = encoder.finish();
					break;
				}
				res = encoder.sink(inbuffer, inputOffset, remainingInInput);
				if( res.isError() ) error(res);
				remainingInInput -= res.count;
				inputOffset += res.count;
			} while( res.code != FULL );
			
			if( res.code == DONE ) break;
			// now input buffer is full, poll for output
			do {
				res = encoder.poll(outbuffer);
				if( res.isError()) error(res);
				if( res.count > 0 ) {
					os.write(outbuffer, 0, res.count);
				}
			} while( res.code == MORE );
			//if( res.code == DONE ) break;
		}
		long duration = System.currentTimeMillis() - start;
		System.out.println("duration: "+ duration / 1000.0);
		os.close();
		is.close();	
	}

	private static void error(Result res) {
		System.err.println("finished with error "+res.code.name());
		System.exit(1);
	}

}
