package com.tmate.hgkit.console;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.util.LinkedList;
import java.util.zip.Inflater;

import com.tmate.hgkit.ll.Changeset;

/**
 * 
 * @author artem
 */
public class Main {

	public static void main(String[] args) throws Exception {
		String filename = "store/00changelog.i";
		//String filename = "store/data/hello.c.i";
//		String filename = "store/data/docs/readme.i";
		LinkedList<Changeset> changelog = new LinkedList<Changeset>();
		//
		DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(new File("/temp/hg/hello/.hg/" + filename))));
		DataInput di = dis;
		dis.mark(10);
		int versionField = di.readInt();
		dis.reset();
		final int INLINEDATA = 1 << 16;
		
		boolean inlineData = (versionField & INLINEDATA) != 0;
		System.out.printf("%#8x, inline: %b\n", versionField, inlineData);
		System.out.println("\tOffset\tFlags\tPacked\t  Actual\tBase Rev    Link Rev\tParent1\tParent2\tnodeid");
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
			dis.skip(12);
			System.out.printf("%14d %6X %10d %10d %10d %10d %8d %8d     %040x\n", offset, flags, compressedLen, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, new BigInteger(buf));
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
				System.out.println(resultString);
			}
		}
		dis.close();
		//
		System.out.println("\n\n");
		System.out.println("====================>");
		for (Changeset cset : changelog) {
			System.out.println(">");
			cset.dump();
			System.out.println("<");
		}
	}
}
