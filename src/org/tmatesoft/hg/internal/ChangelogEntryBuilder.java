/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataSerializer.DataSource;
import org.tmatesoft.hg.util.Path;

/**
 * Builds changelog entry
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ChangelogEntryBuilder implements DataSource {

	private String user;
	private List<Path> modifiedFiles;
	private final Map<String, String> extrasMap = new LinkedHashMap<String, String>();
	private Integer tzOffset;
	private Long csetTime;
	private Nodeid manifestRev;
	private CharSequence comment;
	
	public ChangelogEntryBuilder user(String username) {
		user = username;
		return this;
	}
	
	public String user() {
		if (user == null) {
			// for our testing purposes anything but null is ok. no reason to follow Hg username lookup conventions 
			user = System.getProperty("user.name");
		}
		return user;
	}
	
	public ChangelogEntryBuilder setModified(Collection<Path> files) {
		modifiedFiles = new ArrayList<Path>(files == null ? Collections.<Path>emptyList() : files);
		return this;
	}

	public ChangelogEntryBuilder addModified(Collection<Path> files) {
		if (modifiedFiles == null) {
			return setModified(files);
		}
		modifiedFiles.addAll(files);
		return this;
	}
	
	public ChangelogEntryBuilder branch(String branchName) {
		if (branchName == null || "default".equals(branchName)) {
			extrasMap.remove("branch");
		} else {
			extrasMap.put("branch", branchName);
		}
		return this;
	}
	
	public ChangelogEntryBuilder extras(Map<String, String> extras) {
		extrasMap.clear();
		extrasMap.putAll(extras);
		return this;
	}
	
	public ChangelogEntryBuilder date(long seconds, int timezoneOffset) {
		csetTime = seconds;
		tzOffset = timezoneOffset;
		return this;
	}
	
	public ChangelogEntryBuilder manifest(Nodeid manifestRevision) {
		manifestRev = manifestRevision;
		return this;
	}
	
	public ChangelogEntryBuilder comment(CharSequence commentString) {
		comment = commentString;
		return this;
	}

	public void serialize(DataSerializer out) throws HgIOException {
		byte[] b = build();
		out.write(b, 0, b.length);
	}

	public int serializeLength() {
		return -1;
	}

	public byte[] build() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		final int LF = '\n';
		CharSequence extras = buildExtras();
		CharSequence files = buildFiles();
		byte[] manifestRevision = manifestRev.toString().getBytes();
		byte[] username = user().getBytes(EncodingHelper.getUTF8());
		out.write(manifestRevision, 0, manifestRevision.length);
		out.write(LF);
		out.write(username, 0, username.length);
		out.write(LF);
		final long csetDate = csetTime();
		byte[] date = String.format("%d %d", csetDate, csetTimezone(csetDate)).getBytes();
		out.write(date, 0, date.length);
		if (extras.length() > 0) {
			out.write(' ');
			byte[] b = extras.toString().getBytes();
			out.write(b, 0, b.length);
		}
		out.write(LF);
		byte[] b = files.toString().getBytes();
		out.write(b, 0, b.length);
		out.write(LF);
		out.write(LF);
		byte[] cmt = comment.toString().getBytes(EncodingHelper.getUTF8());
		out.write(cmt, 0, cmt.length);
		return out.toByteArray();
	}

	private CharSequence buildExtras() {
		StringBuilder extras = new StringBuilder();
		for (Iterator<Entry<String, String>> it = extrasMap.entrySet().iterator(); it.hasNext();) {
			final Entry<String, String> next = it.next();
			extras.append(encodeExtrasPair(next.getKey()));
			extras.append(':');
			extras.append(encodeExtrasPair(next.getValue()));
			if (it.hasNext()) {
				extras.append('\00');
			}
		}
		return extras;
	}

	private CharSequence buildFiles() {
		StringBuilder files = new StringBuilder();
		if (modifiedFiles != null) {
			Collections.sort(modifiedFiles);
			for (Iterator<Path> it = modifiedFiles.iterator(); it.hasNext(); ) {
				files.append(it.next());
				if (it.hasNext()) {
					files.append('\n');
				}
			}
		}
		return files;
	}

	private final static CharSequence encodeExtrasPair(String s) {
		if (s != null) {
			return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\00", "\\0");
		}
		return s;
	}

	private long csetTime() {
		if (csetTime != null) { 
			return csetTime;
		}
		return System.currentTimeMillis() / 1000;
	}
	
	private int csetTimezone(long time) {
		if (tzOffset != null) {
			return tzOffset;
		}
		return -(TimeZone.getDefault().getOffset(time) / 1000);
	}
}
