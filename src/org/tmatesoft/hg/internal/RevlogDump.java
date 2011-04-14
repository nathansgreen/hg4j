/*
 * Copyright (c) 2010-2011 TMate Software Ltd
 *  
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * For information on how to redistribute this software under
 * the terms of a license other than GNU General Public License
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.internal;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.util.zip.Inflater;

/**
 * Utility to test/debug/troubleshoot
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogDump {

	/**
	 * Takes 3 command line arguments - 
	 *   repository path, 
	 *   path to index file (i.e. store/data/hello.c.i) in the repository (relative) 
	 *   and "dumpData" whether to print actual content or just revlog headers 
	 */
	public static void main(String[] args) throws Exception {
		String repo = "/temp/hg/hello/.hg/";
		String filename = "store/00changelog.i";
//		String filename = "store/data/hello.c.i";
//		String filename = "store/data/docs/readme.i";
		boolean dumpData = true;
		if (args.length > 1) {
			repo = args[0];
			filename = args[1];
			dumpData = args.length > 2 ? "dumpData".equals(args[2]) : false;
		}
		//
		DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(new File(repo + filename))));
		DataInput di = dis;
		dis.mark(10);
		int versionField = di.readInt();
		dis.reset();
		final int INLINEDATA = 1 << 16;
		
		boolean inlineData = (versionField & INLINEDATA) != 0;
		System.out.printf("%#8x, inline: %b\n", versionField, inlineData);
		System.out.println("Index    Offset      Flags     Packed     Actual   Base Rev   Link Rev  Parent1  Parent2     nodeid");
		int entryCount = 0;
		while (dis.available() > 0) {
			long l = di.readLong();
			long offset = l >>> 16;
			int flags = (int) (l & 0X0FFFF);
			int compressedLen = di.readInt();
			int actualLen = di.readInt();
			int baseRevision = di.readInt();
			int linkRevision = di.readInt();
			int parent1Revision = di.readInt();
			int parent2Revision = di.readInt();
			byte[] buf = new byte[32];
			di.readFully(buf, 12, 20);
			dis.skipBytes(12); 
			// CAN'T USE skip() here without extra precautions. E.g. I ran into situation when 
			// buffer was 8192 and BufferedInputStream was at position 8182 before attempt to skip(12). 
			// BIS silently skips available bytes and leaves me two extra bytes that ruin the rest of the code.
			System.out.printf("%4d:%14d %6X %10d %10d %10d %10d %8d %8d     %040x\n", entryCount, offset, flags, compressedLen, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, new BigInteger(buf));
			if (inlineData) {
				String resultString;
				byte[] data = new byte[compressedLen];
				di.readFully(data);
				if (data[0] == 0x78 /* 'x' */) {
					Inflater zlib = new Inflater();
					zlib.setInput(data, 0, compressedLen);
					byte[] result = new byte[actualLen*2];
					int resultLen = zlib.inflate(result);
					zlib.end();
					resultString = new String(result, 0, resultLen, "UTF-8");
				} else if (data[0] == 0x75 /* 'u' */) {
					resultString = new String(data, 1, data.length - 1, "UTF-8");
				} else {
					resultString = new String(data);
				}
				if (dumpData) { 
					System.out.println(resultString);
				}
			}
			entryCount++;
		}
		dis.close();
		//
	}
}
