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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.ArrayList;
import java.util.Collection;
import java.util.TreeMap;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.internal.RevlogStream;



/**
 * ? name:HgFileNode?
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgDataFile extends Revlog {

	// absolute from repo root?
	// slashes, unix-style?
	// repo location agnostic, just to give info to user, not to access real storage
	private final Path path;
	private Metadata metadata;
	
	/*package-local*/HgDataFile(HgRepository hgRepo, Path path, RevlogStream content) {
		super(hgRepo, content);
		this.path = path;
	}
	
	public boolean exists() {
		return content != null; // XXX need better impl
	}

	// human-readable (i.e. "COPYING", not "store/data/_c_o_p_y_i_n_g.i")
	public Path getPath() {
		return path; // hgRepo.backresolve(this) -> name?
	}

	public int length(Nodeid nodeid) {
		return content.dataLength(getLocalRevisionNumber(nodeid));
	}

	public byte[] content() {
		return content(TIP);
	}

	// for data files need to check heading of the file content for possible metadata
	// @see http://mercurial.selenic.com/wiki/FileFormats#data.2BAC8-
	@Override
	public byte[] content(int revision) {
		if (revision == TIP) {
			revision = content.revisionCount() - 1; // FIXME maxRevision.
		}
		byte[] data = super.content(revision);
		if (data.length < 4 || (data[0] != 1 && data[1] != 10)) {
			return data;
		}
		int toSkip = 0;
		if (metadata == null || !metadata.known(revision)) {
			int lastEntryStart = 2;
			int lastColon = -1;
			ArrayList<MetadataEntry> _metadata = new ArrayList<MetadataEntry>();
			String key = null, value = null;
			for (int i = 2; i < data.length; i++) {
				if (data[i] == (int) ':') {
					key = new String(data, lastEntryStart, i - lastEntryStart);
					lastColon = i;
				} else if (data[i] == '\n') {
					if (key == null || lastColon == -1 || i <= lastColon) {
						throw new IllegalStateException(); // FIXME log instead and record null key in the metadata. Ex just to fail fast during dev
					}
					value = new String(data, lastColon + 1, i - lastColon - 1).trim();
					_metadata.add(new MetadataEntry(key, value));
					key = value = null;
					lastColon = -1;
					lastEntryStart = i+1;
				} else if (data[i] == 1 && i + 1 < data.length && data[i+1] == 10) {
					if (key != null && lastColon != -1 && i > lastColon) {
						// just in case last entry didn't end with newline
						value = new String(data, lastColon + 1, i - lastColon - 1);
						_metadata.add(new MetadataEntry(key, value));
					}
					lastEntryStart = i+1;
					break;
				}
			}
			_metadata.trimToSize();
			if (metadata == null) {
				metadata = new Metadata();
			}
			metadata.add(revision, lastEntryStart, _metadata);
			toSkip = lastEntryStart;
		} else {
			toSkip = metadata.dataOffset(revision);
		}
		// XXX copy of an array may be memory-hostile, a wrapper with baseOffsetShift(lastEntryStart) would be more convenient
		byte[] rv = new byte[data.length - toSkip];
		System.arraycopy(data, toSkip, rv, 0, rv.length);
		return rv;
	}

	public void history(Changeset.Inspector inspector) {
		history(0, content.revisionCount() - 1, inspector);
	}

	public void history(int start, int end, Changeset.Inspector inspector) {
		if (!exists()) {
			throw new IllegalStateException("Can't get history of invalid repository file node"); 
		}
		final int last = content.revisionCount() - 1;
		if (start < 0 || start > last) {
			throw new IllegalArgumentException();
		}
		if (end == TIP) {
			end = last;
		} else if (end < start || end > last) {
			throw new IllegalArgumentException();
		}
		final int[] commitRevisions = new int[end - start + 1];
		RevlogStream.Inspector insp = new RevlogStream.Inspector() {
			int count = 0;
			
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				commitRevisions[count++] = linkRevision;
			}
		};
		content.iterate(start, end, false, insp);
		getRepo().getChangelog().range(inspector, commitRevisions);
	}

	public boolean isCopy() {
		if (metadata == null) {
			content(0); // FIXME expensive way to find out metadata, distinct RevlogStream.Iterator would be better.
		}
		if (metadata == null || !metadata.known(0)) {
			return false;
		}
		return metadata.find(0, "copy") != null;
	}

	public Path getCopySourceName() {
		if (isCopy()) {
			return Path.create(metadata.find(0, "copy"));
		}
		throw new UnsupportedOperationException(); // XXX REVISIT, think over if Exception is good (clients would check isCopy() anyway, perhaps null is sufficient?)
	}
	
	public Nodeid getCopySourceRevision() {
		if (isCopy()) {
			return Nodeid.fromAscii(metadata.find(0, "copyrev")); // XXX reuse/cache Nodeid
		}
		throw new UnsupportedOperationException();
	}

	public static final class MetadataEntry {
		private final String entry;
		private final int valueStart;
		/*package-local*/MetadataEntry(String key, String value) {
			entry = key + value;
			valueStart = key.length();
		}
		/*package-local*/boolean matchKey(String key) {
			return key.length() == valueStart && entry.startsWith(key);
		}
		public String key() {
			return entry.substring(0, valueStart);
		}
		public String value() {
			return entry.substring(valueStart);
		}
	}

	private static class Metadata {
		// XXX sparse array needed
		private final TreeMap<Integer, Integer> offsets = new TreeMap<Integer, Integer>();
		private final TreeMap<Integer, MetadataEntry[]> entries = new TreeMap<Integer, MetadataEntry[]>();
		boolean known(int revision) {
			return offsets.containsKey(revision);
		}
		// since this is internal class, callers are supposed to ensure arg correctness (i.e. ask known() before)
		int dataOffset(int revision) {
			return offsets.get(revision);
		}
		void add(int revision, int dataOffset, Collection<MetadataEntry> e) {
			offsets.put(revision, dataOffset);
			entries.put(revision, e.toArray(new MetadataEntry[e.size()]));
		}
		String find(int revision, String key) {
			for (MetadataEntry me : entries.get(revision)) {
				if (me.matchKey(key)) {
					return me.value();
				}
			}
			return null;
		}
	}
}
