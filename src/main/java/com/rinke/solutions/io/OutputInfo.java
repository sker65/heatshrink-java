package com.rinke.solutions.io;

public class OutputInfo {
	public byte[] buf; 		/* output buffer */
	public int bufSize; 	/* buffer size, redundant in java */
	public int outputSize; 	/* bytes pushed to buffer, so far */
	@Override
	public String toString() {
		return String.format("OutputInfo [buf_size=%s, output_size=%s]", bufSize, outputSize);
	}
}