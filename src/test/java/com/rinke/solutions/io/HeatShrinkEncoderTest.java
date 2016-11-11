package com.rinke.solutions.io;

import org.junit.Before;
import org.junit.Test;

public class HeatShrinkEncoderTest {
	
	HeatShrinkEncoder uut;

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
	}

	@Test
	public void testPoll() throws Exception {
		byte[] buf = new byte[512];
		uut.poll(buf );
	}

	@Test
	public void testFinish() throws Exception {
		uut.finish();
	}

}
