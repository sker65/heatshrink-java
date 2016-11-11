package com.rinke.solutions.io;

import org.junit.Before;
import org.junit.Test;

import static com.rinke.solutions.io.Result.Code.*;
import static com.rinke.solutions.io.Result.*;
import static com.rinke.solutions.io.HeatShrinkDecoder.State.*;
import static org.junit.Assert.*;

public class HeatShrinkDecoderTest {
	

	private HeatShrinkDecoder uut;

	@Before
	public void setUp() throws Exception {
		uut = new HeatShrinkDecoder(10, 5, 1024);
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

}
