/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;

/**
 * @see mercurial/changelog.py:read()
 * <pre>
        format used:
        nodeid\n        : manifest node in ascii
        user\n          : user, no \n or \r allowed
        time tz extra\n : date (time is int or float, timezone is int)
                        : extra is metadatas, encoded and separated by '\0'
                        : older versions ignore it
        files\n\n       : files modified by the cset, no \n or \r allowed
        (.*)            : comment (free text, ideally utf-8)

        changelog v0 doesn't use extra
 * </pre>
 * @author artem
 */
public class Changeset {
	private /*final*/ Nodeid nodeid;
	private String user;
	private String comment;
	private ArrayList<String> files;
	private String timezone; // FIXME
	public byte[] rawData; // FIXME
	
	public void dump() {
		System.out.println("User:" + user);
		System.out.println("Comment:" + comment);
		System.out.println("Nodeid:" + nodeid);
		System.out.println("Date:" + timezone);
		System.out.println("Files: " + files.size());
		for (String s : files) {
			System.out.print('\t');
			System.out.println(s);
		}
	}

	public void read(byte[] buf, int offset, int length) {
		rawData = new byte[length];
		System.arraycopy(buf, offset, rawData, 0, length);
		final int bufferEndIndex = offset + length;
		final byte lineBreak = (byte) '\n';
		int breakIndex1 = indexOf(buf, lineBreak, offset, bufferEndIndex);
		if (breakIndex1 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		nodeid = Nodeid.fromAscii(buf, 0, breakIndex1);
		int breakIndex2 = indexOf(buf, lineBreak, breakIndex1+1, bufferEndIndex);
		if (breakIndex2 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		user = new String(buf, breakIndex1+1, breakIndex2 - breakIndex1 - 1);
		int breakIndex3 = indexOf(buf, lineBreak, breakIndex2+1, bufferEndIndex);
		if (breakIndex3 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		timezone = new String(buf, breakIndex2+1, breakIndex3 - breakIndex2 - 1);
		
		//
		int lastStart = breakIndex3 + 1;
		int breakIndex4 = indexOf(buf, lineBreak, lastStart, bufferEndIndex);
		files = new ArrayList<String>(5);
		while (breakIndex4 != -1 && breakIndex4 + 1 < bufferEndIndex) {
			files.add(new String(buf, lastStart, breakIndex4 - lastStart));
			lastStart = breakIndex4 + 1;
			if (buf[breakIndex4 + 1] == lineBreak) {
				// found \n\n
				break;
			} else {
				breakIndex4 = indexOf(buf, lineBreak, lastStart, bufferEndIndex);
			}
		}
		if (breakIndex4 == -1 || breakIndex4 >= bufferEndIndex) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		try {
			comment = new String(buf, breakIndex4+2, bufferEndIndex - breakIndex4 - 2, "UTF-8");
		} catch (UnsupportedEncodingException ex) {
			comment = "";
			throw new IllegalStateException("Could hardly happen");
		}
	}

	private static int indexOf(byte[] src, byte what, int startOffset, int endIndex) {
		for (int i = startOffset; i < endIndex; i++) {
			if (src[i] == what) {
				return i;
			}
		}
		return -1;
	}
}
