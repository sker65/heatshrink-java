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
		HeatShrinkEncoder encoder = new HeatShrinkEncoder(10, 5);
		encoder.encode(is,os);
		os.close();
		is.close();	
	}
	
	public static void decode( String[] args ) throws Exception {
		InputStream is = new FileInputStream(args[0]);
		OutputStream os = new FileOutputStream(args[1]);
		HeatShrinkDecoder decoder = new HeatShrinkDecoder(10, 5, 1024);
		decoder.decode(is, os);
		os.close();
		is.close();	
	}

	private static void error(Result res) {
		System.err.println("finished with error "+res.code.name());
		System.exit(1);
	}

}
