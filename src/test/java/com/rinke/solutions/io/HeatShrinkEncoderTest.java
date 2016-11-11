package com.rinke.solutions.io;

import static org.junit.Assert.*;

import java.util.Random;

import org.junit.Before;
import org.junit.Test;
import static com.rinke.solutions.io.Result.Code.*;
import static com.rinke.solutions.io.Result.*;
import static com.rinke.solutions.io.HeatShrinkDecoder.State.*;

public class HeatShrinkEncoderTest {
	
	HeatShrinkEncoder uut;
	Random rand = new Random();

	@Before
	public void setUp() throws Exception {
		uut = new HeatShrinkEncoder(10, 5);
	}

	@Test
	public void testReset() throws Exception {
		uut.reset();
	}

	@Test
	public void testSink() throws Exception {
		byte[] inBuffer = new byte[512];
		Result sink = uut.sink(inBuffer, 0, inBuffer.length);
		uut.finish();
		byte[] buf = new byte[1024];
		Result poll = uut.poll(buf);
		assertEquals(32, poll.count);
//		for( int i = 0; i < poll.count; i++ ) {
//			System.out.print("0x"+Integer.toHexString(buf[i])+", ");
//		}
	}

	@Test
	public void testPoll() throws Exception {
		byte[] buf = new byte[512];
		uut.poll(buf );
	}

	@Test
	public void testEncodeDecode() throws Exception {
		byte[] buf = new byte[10000];
		byte[] encoded = new byte[10000];
		byte current = 0;
		for (int i = 0; i < buf.length; i++) {
			if( i % 20 == 0) current = (byte) rand.nextInt(255);
			buf[i] = current;
		}
		int size = buf.length;
		int offset = 0;
		int out = 0;
		Result sink = new Result(0, OK);
		while( size > 0 ) {
			sink = uut.sink(buf, offset, size);
			size -= sink.count;
			offset += sink.count;
			if( size == 0) uut.finish();
			byte[] obuf = new byte[10000];
			Result poll = uut.poll(obuf);
			if( poll.count > 0) {
				System.arraycopy(obuf, 0, encoded, out, poll.count);
				out += poll.count;
			}
		}
		// encoded now contains out bytes.
		// now decode
		HeatShrinkDecoder decoder = new HeatShrinkDecoder(10, 5, 1024);
		offset = 0;
		size = out;
		out = 0;
		byte[] decoded = new byte[10000];
		while( size > 0 ) {
			sink = decoder.sink(encoded, offset, size);
			size -= sink.count;
			offset += sink.count;
			if( size == 0) decoder.finish();
			byte[] obuf = new byte[10000];
			Result poll = decoder.poll(obuf);
			if( poll.count > 0) {
				System.arraycopy(obuf, 0, decoded, out, poll.count);
				out += poll.count;
			}
		}
		// compare
		for (int i = 0; i < decoded.length; i++) {
			if(decoded[i] != buf[i]) {
				fail( "decoded buffer differs from original at "+i);
			}
		}
	}


	@Test
	public void testFinish() throws Exception {
		uut.finish();
	}

}
