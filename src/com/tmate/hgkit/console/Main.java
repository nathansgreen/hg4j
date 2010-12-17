package com.tmate.hgkit.console;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import com.tmate.hgkit.ll.Changeset;

/**
 * 
 * @author artem
 */
public class Main {

	public static void main(String[] args) throws Exception {
		Deflater zip1 = new Deflater(6, true);
		final byte[] input = "Abstractions are valueless".getBytes();
		zip1.setInput(input);
		zip1.finish();
		byte[] result1 = new byte[100];
		int resLen1 = zip1.deflate(result1);
		System.out.printf("%3d:", resLen1);
		for (int i = 0; i < resLen1; i++) {
			System.out.printf("%02X", result1[i]);
		}
		System.out.println();
		//
		Deflater zip2 = new Deflater(6, false);
		zip2.setInput(input);
		zip2.finish();
		byte[] result2 = new byte[100];
		int resLen2 = zip2.deflate(result2);
		System.out.printf("%3d:", resLen2);
		for (int i = 0; i < resLen2; i++) {
			System.out.printf("%02X", result2[i]);
		}
		System.out.println();
		//
		LinkedList<Changeset> changelog = new LinkedList<Changeset>();
		//
		DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(new File("/temp/hg/hello/" + ".hg/store/00changelog.i"))));
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
				byte[] data = new byte[compressedLen];
				di.readFully(data);
				if (data[0] == 0x78 /* 'x' */) {
					Inflater zlib = new Inflater();
					zlib.setInput(data, 0, compressedLen);
					byte[] result = new byte[actualLen*2];
					int resultLen = zlib.inflate(result);
					zlib.end();
					if (resultLen != actualLen) {
						System.err.printf("Expected:%d, decomressed to:%d bytes\n", actualLen, resultLen);
					}
					String resultString;
					if (baseRevision != entryCount) {
						// this is a patch
						byte[] baseRevContent = changelog.get(baseRevision).rawData;
						LinkedList<PatchRecord> bins = new LinkedList<PatchRecord>();
						int p1, p2, len, patchElementIndex = 0;
						do {
							final int x = patchElementIndex;
							p1 = (result[x] << 24) | (result[x+1] << 16) | (result[x+2] << 8) | result[x+3];
							p2 = (result[x+4] << 24) | (result[x+5] << 16) | (result[x+6] << 8) | result[x+7];
							len = (result[x+8] << 24) | (result[x+9] << 16) | (result[x+10] << 8) | result[x+11];
							System.out.printf("%4d %4d %4d\n", p1, p2, len);
							patchElementIndex += 12 + len;
							bins.add(new PatchRecord(p1, p2, len, result, x+12));
						} while (patchElementIndex < resultLen);
						// 
						result = apply(baseRevContent, bins);
						resultLen = result.length;
					}
					resultString = new String(result, 0, resultLen, "UTF-8");
					System.out.println(resultString);
					entryCount++;
					Changeset changeset = new Changeset();
					changeset.read(result, 0, resultLen);
					changelog.add(changeset);
				} // TODO else if uncompressed
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


	// mpatch.c : apply()
	private static byte[] apply(byte[] baseRevisionContent, List<PatchRecord> patch) {
		byte[] tempBuf = new byte[512]; // XXX
		int last = 0, destIndex = 0;
		for (PatchRecord pr : patch) {
			System.arraycopy(baseRevisionContent, last, tempBuf, destIndex, pr.start-last);
			destIndex += pr.start - last;
			System.arraycopy(pr.data, 0, tempBuf, destIndex, pr.data.length);
			destIndex += pr.data.length;
			last = pr.end;
		}
		System.arraycopy(baseRevisionContent, last, tempBuf, destIndex, baseRevisionContent.length - last);
		destIndex += baseRevisionContent.length - last; // total length
		byte[] rv = new byte[destIndex];
		System.arraycopy(tempBuf, 0, rv, 0, destIndex);
		return rv;
	}

	static class PatchRecord { // copy of struct frag from mpatch.c
		int start, end, len;
		byte[] data;

		public PatchRecord(int p1, int p2, int len, byte[] src, int srcOffset) {
		start = p1;
				end = p2;
				this.len = len;
				data = new byte[len];
				System.arraycopy(src, srcOffset, data, 0, len);
		}
	}
}
