/*
 * Copyright (c) 2013 TMate Software Ltd
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

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.util.LogFacility;

/**
 * Container for metadata recorded as part of file revisions
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Metadata {
	private static class Record {
		public final int offset;
		public final MetadataEntry[] entries;
		
		public Record(int off, MetadataEntry[] entr) {
			offset = off;
			entries = entr;
		}
	}
	// XXX sparse array needed
	private final IntMap<Metadata.Record> entries = new IntMap<Metadata.Record>(5);
	
	private final Metadata.Record NONE = new Record(-1, null); // don't want statics

	private final LogFacility log;

	public Metadata(SessionContext.Source sessionCtx) {
		log = sessionCtx.getSessionContext().getLog();
	}
	
	// true when there's metadata for given revision
	public boolean known(int revision) {
		Metadata.Record i = entries.get(revision);
		return i != null && NONE != i;
	}

	// true when revision has been checked for metadata presence.
	public boolean checked(int revision) {
		return entries.containsKey(revision);
	}

	// true when revision has been checked and found not having any metadata
	public boolean none(int revision) {
		Metadata.Record i = entries.get(revision);
		return i == NONE;
	}

	// mark revision as having no metadata.
	void recordNone(int revision) {
		Metadata.Record i = entries.get(revision);
		if (i == NONE) {
			return; // already there
		} 
		if (i != null) {
			throw new HgInvalidStateException(String.format("Trying to override Metadata state for revision %d (known offset: %d)", revision, i));
		}
		entries.put(revision, NONE);
	}

	// since this is internal class, callers are supposed to ensure arg correctness (i.e. ask known() before)
	public int dataOffset(int revision) {
		return entries.get(revision).offset;
	}
	void add(int revision, int dataOffset, Collection<MetadataEntry> e) {
		assert !entries.containsKey(revision);
		entries.put(revision, new Record(dataOffset, e.toArray(new MetadataEntry[e.size()])));
	}
	
	/**
	 * @return <code>true</code> if metadata has been found
	 */
	public boolean tryRead(int revisionNumber, DataAccess data) throws IOException, HgInvalidControlFileException {
		final int daLength = data.length();
		if (daLength < 4 || data.readByte() != 1 || data.readByte() != 10) {
			recordNone(revisionNumber);
			return false;
		} else {
			ArrayList<MetadataEntry> _metadata = new ArrayList<MetadataEntry>();
			int offset = parseMetadata(data, daLength, _metadata);
			add(revisionNumber, offset, _metadata);
			return true;
		}
	}

	public String find(int revision, String key) {
		for (MetadataEntry me : entries.get(revision).entries) {
			if (me.matchKey(key)) {
				return me.value();
			}
		}
		return null;
	}

	private int parseMetadata(DataAccess data, final int daLength, ArrayList<MetadataEntry> _metadata) throws IOException, HgInvalidControlFileException {
		int lastEntryStart = 2;
		int lastColon = -1;
		// XXX in fact, need smth like ByteArrayBuilder, similar to StringBuilder,
		// which can't be used here because we can't convert bytes to chars as we read them
		// (there might be multi-byte encoding), and we need to collect all bytes before converting to string 
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		String key = null, value = null;
		boolean byteOne = false;
		boolean metadataIsComplete = false;
		for (int i = 2; i < daLength; i++) {
			byte b = data.readByte();
			if (b == '\n') {
				if (byteOne) { // i.e. \n follows 1
					lastEntryStart = i+1;
					metadataIsComplete = true;
					// XXX is it possible to have here incomplete key/value (i.e. if last pair didn't end with \n)
					// if yes, need to set metadataIsComplete to true in that case as well
					break;
				}
				if (key == null || lastColon == -1 || i <= lastColon) {
					log.dump(getClass(), Error, "Missing key in file revision metadata at index %d", i);
				}
				value = new String(bos.toByteArray()).trim();
				bos.reset();
				_metadata.add(new MetadataEntry(key, value));
				key = value = null;
				lastColon = -1;
				lastEntryStart = i+1;
				continue;
			} 
			// byteOne has to be consumed up to this line, if not yet, consume it
			if (byteOne) {
				// insert 1 we've read on previous step into the byte builder
				bos.write(1);
				byteOne = false;
				// fall-through to consume current byte
			}
			if (b == (int) ':') {
				assert value == null;
				key = new String(bos.toByteArray());
				bos.reset();
				lastColon = i;
			} else if (b == 1) {
				byteOne = true;
			} else {
				bos.write(b);
			}
		}
		// data.isEmpty is not reliable, renamed files of size==0 keep only metadata
		if (!metadataIsComplete) {
			// XXX perhaps, worth a testcase (empty file, renamed, read or ask ifCopy
			throw new HgInvalidControlFileException("Metadata is not closed properly", null, null);
		}
		return lastEntryStart;
	}

	/**
	 * There may be several entries of metadata per single revision, this class captures single entry
	 */
	private static class MetadataEntry {
		private final String entry;
		private final int valueStart;

		// key may be null
		/* package-local */MetadataEntry(String key, String value) {
			if (key == null) {
				entry = value;
				valueStart = -1; // not 0 to tell between key == null and key == ""
			} else {
				entry = key + value;
				valueStart = key.length();
			}
		}

		/* package-local */boolean matchKey(String key) {
			return key == null ? valueStart == -1 : key.length() == valueStart && entry.startsWith(key);
		}

//			uncomment once/if needed
//			public String key() {
//				return entry.substring(0, valueStart);
//			}

		public String value() {
			return valueStart == -1 ? entry : entry.substring(valueStart);
		}
	}
}