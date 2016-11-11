package com.rinke.solutions.io;

import java.io.File;
import java.io.FileOutputStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HeatShrinkTest {
	
	@Rule
	public TemporaryFolder folder= new TemporaryFolder();
	
	File inFile;
	File outFile;
	File decFile;
	
	@Before
	public void setUp() throws Exception {
		inFile = folder.newFile("in.txt");
		outFile = folder.newFile("out.txt");
		decFile = folder.newFile("dec.txt");
		FileOutputStream os = new FileOutputStream( inFile );
		byte[] buf = new byte[512];
		os.write(buf);
		os.close();
	}

	@Test
	public void testCall() throws Exception {
		HeatShrink.main(new String[]{"-e", inFile.getPath(), outFile.getPath()});
		HeatShrink.main(new String[]{"-d", outFile.getPath(), decFile.getPath()});
	}
	
	@Test
	public void testEncodeDecode() throws Exception {
		HeatShrink.encode(new String[]{inFile.getPath(), outFile.getPath()});
		HeatShrink.decode(new String[]{outFile.getPath(), decFile.getPath()});
	}

}
