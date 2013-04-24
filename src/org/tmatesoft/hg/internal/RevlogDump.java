/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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
import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
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
//		System.out.println(escape("abc\0def\nzxc\tmnb"));
		boolean dumpDataFull = true;
		boolean dumpDataStats = false;
		if (args.length > 1) {
			repo = args[0];
			filename = args[1];
			dumpDataFull = args.length > 2 ? "dumpData".equals(args[2]) : false;
			dumpDataStats = args.length > 2 ? "dumpDataStats".equals(args[2]) : false;
		}
		final boolean needRevData = dumpDataFull || dumpDataStats; 
		//
		RevlogReader rr = new RevlogReader(new File(repo, filename)).needData(needRevData);
		rr.init(needRevData);
		System.out.printf("%#8x, inline: %b\n", rr.versionField, rr.inlineData);
		System.out.println("Index    Offset      Flags     Packed     Actual   Base Rev   Link Rev  Parent1  Parent2     nodeid");
		ByteBuffer data = null;
		while (rr.hasMore()) {
			rr.readNext();
			System.out.printf("%4d:%14d %6X %10d %10d %10d %10d %8d %8d     %040x\n", rr.entryIndex, rr.offset, rr.flags, rr.compressedLen, rr.actualLen, rr.baseRevision, rr.linkRevision, rr.parent1Revision, rr.parent2Revision, new BigInteger(rr.nodeid));
			if (needRevData) {
				String resultString;
				if (rr.getDataLength() == 0) {
					resultString = "<NO DATA>";
				} else {
					data = ensureCapacity(data, rr.getDataLength());
					rr.getData(data);
					data.flip();
					resultString = buildString(data, rr.isPatch(), dumpDataFull);
				}
				if (resultString.endsWith("\n")) {
					System.out.print(resultString);
				} else {
					System.out.println(resultString);
				}
			}
		}
		rr.done();
	}
	
	private static ByteBuffer ensureCapacity(ByteBuffer src, int requiredCap) {
		if (src == null || src.capacity() < requiredCap) {
			return ByteBuffer.allocate((1 + requiredCap) * 3 / 2);
		}
		src.clear();
		return src;
	}
	
	private static String buildString(ByteBuffer data, boolean isPatch, boolean completeDataDump) throws IOException, UnsupportedEncodingException {
		if (isPatch) {
			DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data.array(), data.arrayOffset(), data.remaining()));
			StringBuilder sb = new StringBuilder();
			sb.append("<PATCH>:\n");
			while (dis.available() > 0) {
				int s = dis.readInt();
				int e = dis.readInt();
				int l = dis.readInt();
				sb.append(String.format("%d..%d, %d", s, e, l));
				if (completeDataDump) {
					byte[] src = new byte[l];
					dis.read(src, 0, l);
					sb.append(":");
					sb.append(escape(new String(src, 0, l, "UTF-8")));
				} else {
					dis.skipBytes(l);
				}
				sb.append('\n');
			}
			return sb.toString();
		} else {
			if (completeDataDump) {
				return escape(new String(data.array(), data.arrayOffset(), data.remaining(), "UTF-8"));
			}
			return String.format("<DATA>:%d bytes", data.remaining());
		}
	}
	
	private static Pattern controlCharPattern = Pattern.compile("\\p{Cntrl}");
	// \p{Cntrl}	A control character: [\x00-\x1F\x7F]
	private static String[] replacements = new String[33];
	static {
		for (int i = 0; i < 32; i++) {
			// no idea why need FOUR backslashes to get only one in printout
			replacements[i] = String.format("\\\\%X", i);
		}
		replacements[32] = String.format("\\\\%X", 127);
	}
	// handy to get newline-separated data printed on newlines.
	// set to false for non-printable data (e.g. binaries, where \n doesn't make sense)
	private static boolean leaveNewlineInData = true;
	
	private static String escape(CharSequence possiblyWithBinary) {
		Matcher m = controlCharPattern.matcher(possiblyWithBinary);
		StringBuffer rv = new StringBuffer();
		while (m.find()) {
			char c = m.group().charAt(0);
			if (leaveNewlineInData && c == '\n') {
				continue;
			}
			int x = (int) c;
			m.appendReplacement(rv, replacements[x == 127 ? 32 : x]);
		}
		m.appendTail(rv);
		return rv.toString();
	}

	public static class RevlogReader {
		
		private final File file;
		private boolean needRevData;
		private DataInputStream dis;
		private boolean inlineData;
		public int versionField;
		private FileChannel dataStream;
		public int entryIndex;
		private byte[] data;
		private int dataOffset, dataLen;
		public long offset;
		public int flags;
		public int baseRevision;
		public int linkRevision;
		public int parent1Revision;
		public int parent2Revision;
		public int compressedLen;
		public int actualLen;
		public byte[] nodeid = new byte[21]; // need 1 byte in the front to be 0 to avoid negative BigInts

		public RevlogReader(File f) {
			assert f.getName().endsWith(".i");
			file = f;
		}

		// affects #readNext()
		public RevlogReader needData(boolean needData) {
			needRevData = needData;
			return this;
		}
		
		public void init(boolean mayRequireData) throws IOException {
			dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
			DataInput di = dis;
			dis.mark(10);
			versionField = di.readInt();
			dis.reset();
			final int INLINEDATA = 1 << 16;
			inlineData = (versionField & INLINEDATA) != 0;
			
			dataStream = null; 
			if (!inlineData && mayRequireData) {
				String fname = file.getAbsolutePath();
				dataStream = new FileInputStream(new File(fname.substring(0, fname.length()-2) + ".d")).getChannel();
			}
			
			entryIndex = -1;
		}
		
		public void startFrom(int startEntryIndex) throws IOException {
			if (dis == null) {
				throw new IllegalStateException("Call #init() first");
			}
			if (entryIndex != -1 && startEntryIndex != 0) {
				throw new IllegalStateException("Can't seek once iteration has started");
			}
			if (dataStream == null) {
				throw new IllegalStateException("Sorry, initial seek is now supported for separate .i/.d only");
			}
			long newPos = startEntryIndex * Internals.REVLOGV1_RECORD_SIZE, actualSkip;
			do {
				actualSkip = dis.skip(newPos);
				if (actualSkip <= 0) {
					throw new IllegalStateException(String.valueOf(actualSkip));
				}
				newPos -= actualSkip;
			} while (newPos > 0);
			entryIndex = startEntryIndex - 1;
		}
		
		public boolean hasMore() throws IOException {
			return dis.available() > 0;
		}
		
		public void readNext() throws IOException, DataFormatException {
			entryIndex++;
			DataInput di = dis;
			long l = di.readLong();
			offset = entryIndex == 0 ? 0 : (l >>> 16);
			flags = (int) (l & 0x0FFFF);
			compressedLen = di.readInt();
			actualLen = di.readInt();
			baseRevision = di.readInt();
			linkRevision = di.readInt();
			parent1Revision = di.readInt();
			parent2Revision = di.readInt();
			di.readFully(nodeid, 1, 20);
			dis.skipBytes(12); 
			// CAN'T USE skip() here without extra precautions. E.g. I ran into situation when 
			// buffer was 8192 and BufferedInputStream was at position 8182 before attempt to skip(12). 
			// BIS silently skips available bytes and leaves me two extra bytes that ruin the rest of the code.
			data = new byte[compressedLen];
			if (inlineData) {
				di.readFully(data);
			} else if (needRevData) {
				dataStream.position(offset);
				dataStream.read(ByteBuffer.wrap(data));
			}
			if (needRevData) {
				if (compressedLen == 0) {
					data = null;
					dataOffset = dataLen = 0;
				} else {
					if (data[0] == 0x78 /* 'x' */) {
						Inflater zlib = new Inflater();
						zlib.setInput(data, 0, compressedLen);
						byte[] result = new byte[actualLen * 3];
						int resultLen = zlib.inflate(result);
						zlib.end();
						data = result;
						dataOffset = 0;
						dataLen = resultLen;
					} else if (data[0] == 0x75 /* 'u' */) {
						dataOffset = 1;
						dataLen = data.length - 1;
					} else {
						dataOffset = 0;
						dataLen = data.length;
					}
				}
			}
		}
		
		public int getDataLength() {
			// NOT actualLen - there are empty patch revisions (dataLen == 0, but actualLen == previous length)
			// NOT compressedLen - zip data is uncompressed
			return dataLen;
		}
		
		public void getData(ByteBuffer bb) {
			assert bb.remaining() >= dataLen;
			bb.put(data, dataOffset, dataLen);
		}
		
		public boolean isPatch() {
			assert entryIndex != -1;
			return baseRevision != entryIndex;
		}
		
		public boolean isInline() {
			assert dis != null;
			return inlineData;
		}

		public void done() throws IOException {
			dis.close();
			dis = null;
			if (dataStream != null) {
				dataStream.close();
				dataStream = null;
			}
		}
	}
}
