/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
public class Changeset implements Cloneable /*for those that would like to keep a copy*/ {
	// TODO immutable
	private /*final*/ Nodeid manifest;
	private String user;
	private String comment;
	private List<String> files; // unmodifiable collection (otherwise #files() and implicit #clone() shall be revised)
	private Date time;
	private int timezone; // not sure it's of any use
	private Map<String,String> extras;
	
	private Changeset() {
	}
	
	public Nodeid manifest() {
		return manifest;
	}
	
	public String user() {
		return user;
	}
	
	public String comment() {
		return comment;
	}
	
	public List<String> files() {
		return files;
	}

	public Date date() {
		return time;
	}
	
	public String dateString() {
		StringBuilder sb = new StringBuilder(30);
		Formatter f = new Formatter(sb, Locale.US);
		f.format("%ta %<tb %<td %<tH:%<tM:%<tS %<tY %<tz", time);
		return sb.toString();
	}

	public Map<String, String> extras() {
		return extras;
	}
	
	public String branch() {
		return extras.get("branch");
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Changeset {");
		sb.append("User: ").append(user).append(", ");
		sb.append("Comment: ").append(comment).append(", ");
		sb.append("Manifest: ").append(manifest).append(", ");
		sb.append("Date: ").append(time).append(", ");
		sb.append("Files: ").append(files.size());
		for (String s : files) {
			sb.append(", ").append(s);
		}
		if (extras != null) {
			sb.append(", Extra: ").append(extras);
		}
		sb.append("}");
		return sb.toString();
	}

	public static Changeset parse(byte[] data, int offset, int length) {
		Changeset rv = new Changeset();
		rv.init(data, offset, length);
		return rv;
	}

	/*package-local*/ void init(byte[] data, int offset, int length) {
		final int bufferEndIndex = offset + length;
		final byte lineBreak = (byte) '\n';
		int breakIndex1 = indexOf(data, lineBreak, offset, bufferEndIndex);
		if (breakIndex1 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		Nodeid _nodeid = Nodeid.fromAscii(data, 0, breakIndex1);
		int breakIndex2 = indexOf(data, lineBreak, breakIndex1+1, bufferEndIndex);
		if (breakIndex2 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		String _user = new String(data, breakIndex1+1, breakIndex2 - breakIndex1 - 1);
		int breakIndex3 = indexOf(data, lineBreak, breakIndex2+1, bufferEndIndex);
		if (breakIndex3 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		String _timeString = new String(data, breakIndex2+1, breakIndex3 - breakIndex2 - 1);
		int space1 = _timeString.indexOf(' ');
		if (space1 == -1) {
			throw new IllegalArgumentException("Bad Changeset data");
		}
		int space2 = _timeString.indexOf(' ', space1+1);
		if (space2 == -1) {
			space2 = _timeString.length();
		}
		long unixTime = Long.parseLong(_timeString.substring(0, space1)); // XXX Float, perhaps
		int _timezone = Integer.parseInt(_timeString.substring(space1+1, space2));
		// XXX not sure need to add timezone here - I can't figure out whether Hg keeps GMT time, and records timezone just for info, or unixTime is taken local
		// on commit and timezone is recorded to adjust it to UTC.
		Date _time = new Date(unixTime * 1000);
		String _extras = space2 < _timeString.length() ? _timeString.substring(space2+1) : null;
		Map<String, String> _extrasMap;
		if (_extras == null) {
			 _extrasMap = Collections.singletonMap("branch", "default"); 
		} else {
			_extrasMap = new HashMap<String, String>();
			for (String pair : _extras.split("\00")) {
				int eq = pair.indexOf(':');
				// FIXME need to decode key/value, @see changelog.py:decodeextra
				_extrasMap.put(pair.substring(0, eq), pair.substring(eq+1));
			}
			if (!_extrasMap.containsKey("branch")) {
				_extrasMap.put("branch", "default");
			}
			_extrasMap = Collections.unmodifiableMap(_extrasMap);
		}
		
		//
		int lastStart = breakIndex3 + 1;
		int breakIndex4 = indexOf(data, lineBreak, lastStart, bufferEndIndex);
		ArrayList<String> _files = new ArrayList<String>(5);
		while (breakIndex4 != -1 && breakIndex4 + 1 < bufferEndIndex) {
			_files.add(new String(data, lastStart, breakIndex4 - lastStart));
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
		String _comment;
		try {
			_comment = new String(data, breakIndex4+2, bufferEndIndex - breakIndex4 - 2, "UTF-8");
		} catch (UnsupportedEncodingException ex) {
			_comment = "";
			throw new IllegalStateException("Could hardly happen");
		}
		// change this instance at once, don't leave it partially changes in case of error
		this.manifest = _nodeid;
		this.user = _user;
		this.time = _time;
		this.timezone = _timezone;
		this.files = Collections.unmodifiableList(_files);
		this.comment = _comment;
		this.extras = _extrasMap;
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
		// TODO describe whether cset is new instance each time
		void next(int revisionNumber, Nodeid nodeid, Changeset cset);
	}
}
