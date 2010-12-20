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
	// TODO immutable
	private /*final*/ Nodeid nodeid;
	private String user;
	private String comment;
	private ArrayList<String> files;
	private String timezone; // FIXME
	
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

	public static Changeset parse(byte[] data, int offset, int length) {
		Changeset rv = new Changeset();
		final int bufferEndIndex = offset + length;
		final byte lineBreak = (byte) '\n';
		int breakIndex1 = indexOf(data, lineBreak, offset, bufferEndIndex);
		if (breakIndex1 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		rv.nodeid = Nodeid.fromAscii(data, 0, breakIndex1);
		int breakIndex2 = indexOf(data, lineBreak, breakIndex1+1, bufferEndIndex);
		if (breakIndex2 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		rv.user = new String(data, breakIndex1+1, breakIndex2 - breakIndex1 - 1);
		int breakIndex3 = indexOf(data, lineBreak, breakIndex2+1, bufferEndIndex);
		if (breakIndex3 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		rv.timezone = new String(data, breakIndex2+1, breakIndex3 - breakIndex2 - 1);
		
		//
		int lastStart = breakIndex3 + 1;
		int breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
		rv.files = new ArrayList<String>(5);
		while (breakIndex4 != -1 && breakIndex4 + 1 < bufferEndIndex) {
			rv.files.add(new String(data, lastStart, breakIndex4 - lastStart));
			lastStart = breakIndex4 + 1;
			if (data[breakIndex4 + 1] == lineBreak) {
				// found \n\n
				break;
			} else {
				breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
			}
		}
		if (breakIndex4 == -1 || breakIndex4 >= bufferEndIndex) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		try {
			rv.comment = new String(data, breakIndex4+2, bufferEndIndex - breakIndex4 - 2, "UTF-8");
		} catch (UnsupportedEncodingException ex) {
			rv.comment = "";
			throw new IllegalStateException("Could hardly happen");
		}
		return rv;
	}

	private static int indexOf(byte[] src, byte what, int startOffset, int endIndex) {
		for (int i = startOffset; i < endIndex; i++) {
			if (src[i] == what) {
				return i;
			}
		}
		return -1;
	}

	public interface Inspector {
		// first(), last(), single().
		// <T>
		void next(Changeset cset);
	}
}
