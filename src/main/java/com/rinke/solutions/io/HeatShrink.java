package com.rinke.solutions.io;

import static com.rinke.solutions.io.Result.res;
import static com.rinke.solutions.io.Result.Code.DONE;
import static com.rinke.solutions.io.Result.Code.FULL;
import static com.rinke.solutions.io.Result.Code.MORE;
import static com.rinke.solutions.io.Result.Code.OK;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

public class HeatShrink {
	/**
	 * example main program for compression
	 * @param args in and outfile
	 * @throws Exception
	 */
	public static void main( String[] args ) throws Exception { 
		if( args.length < 3) {
			usage();
		}
		if( args[0].equals("-d") ) decode(Arrays.copyOfRange(args, 1, args.length));
		if( args[0].equals("-e") ) encode(Arrays.copyOfRange(args, 1, args.length));
	}
	
	private static void usage() {
		System.out.println("usage: HeatShring: (-d|-e) infile outfile");
		System.exit(1);
	}

	public static void encode( String[] args ) throws Exception {
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

	public static void decode( String[] args ) throws Exception {
		
		InputStream is = new FileInputStream(args[0]);
		OutputStream os = new FileOutputStream(args[1]);
		byte[] inbuffer = new byte[1024];
		byte[] outbuffer = new byte[4096];
		//System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
		HeatShrinkDecoder decoder = new HeatShrinkDecoder(10, 5, inbuffer.length);
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
					res = decoder.finish();
					break;
				}
				res = decoder.sink(inbuffer, inputOffset, remainingInInput);
				if( res.isError() ) error(res);
				remainingInInput -= res.count;
				inputOffset += res.count;
			} while( res.code != FULL );
			
			if( res.code == DONE ) break;
			// now input buffer is full, poll for output
			do {
				res = decoder.poll(outbuffer);
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
