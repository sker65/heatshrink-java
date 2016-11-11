package com.rinke.solutions.io;

import org.junit.Before;
import org.junit.Test;

import static com.rinke.solutions.io.Result.Code.*;
import static com.rinke.solutions.io.Result.*;
import static com.rinke.solutions.io.HeatShrinkDecoder.State.*;
import static org.junit.Assert.*;

public class HeatShrinkDecoderTest {
	
	byte[] compressed = { 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f,
			0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f, 0x0, 0x1f };

	private HeatShrinkDecoder uut;

	@Before
	public void setUp() throws Exception {
		uut = new HeatShrinkDecoder(10, 5, 1024);
	}
	
	@Test
	public void testDecode() throws Exception {
		Result res = uut.sink(compressed, 0, compressed.length);
		uut.finish();
		byte[] buf = new byte[4096];
		Result p = uut.poll(buf);
		assertEquals(512, p.count);
		assertEquals(EMPTY, p.code);
	}



	@Test
	public void testFinish() throws Exception {
		Result res = uut.finish();
		assertEquals(DONE, res.code);
	}

	@Test
	public void testReset() throws Exception {
		uut.reset();
	}

	@Test
	public void testSink() throws Exception {
		byte[] inBuffer = new byte[512];
		uut.sink(inBuffer, 0, inBuffer.length);
	}

	@Test
	public void testPoll() throws Exception {
		byte[] outBuffer = new byte[512];
		Result result = uut.poll(outBuffer);
		assertEquals(EMPTY, result.code);
	}
	
	@Test(expected=IllegalArgumentException.class)
	public void testPollNull() throws Exception {
		uut.poll(null);
	}

	@Test(expected=IllegalArgumentException.class)
	public void testSinkNull() throws Exception {
		uut.sink(null,0,0);
	}


}
