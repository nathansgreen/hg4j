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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.util.Map;
import java.util.TreeMap;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDirstate;
import org.tmatesoft.hg.repo.HgDirstate.EntryKind;
import org.tmatesoft.hg.repo.HgDirstate.Record;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepositoryFiles;
import org.tmatesoft.hg.util.Path;

/**
 * Facility to build a dirstate file as described in {@linkplain http://mercurial.selenic.com/wiki/DirState}
 * 
 * @see http://mercurial.selenic.com/wiki/DirState
 * @see HgDirstate
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class DirstateBuilder {
	private Map<Path, HgDirstate.Record> normal = new TreeMap<Path, HgDirstate.Record>();
	private Map<Path, HgDirstate.Record> added = new TreeMap<Path, HgDirstate.Record>();
	private Map<Path, HgDirstate.Record> removed = new TreeMap<Path, HgDirstate.Record>();
	private Map<Path, HgDirstate.Record> merged = new TreeMap<Path, HgDirstate.Record>();
	private Nodeid parent1, parent2;
	private final Internals hgRepo;
	private final EncodingHelper encodingHelper;
	
	public DirstateBuilder(Internals internalRepo) {
		hgRepo = internalRepo;
		encodingHelper = internalRepo.buildFileNameEncodingHelper();
	}
	
	public void parents(Nodeid p1, Nodeid p2) {
		parent1 = p1 == null ? Nodeid.NULL : p1;
		parent2 = p2 == null ? Nodeid.NULL : p2;
	}
	
	public void recordNormal(Path fname, int fmode, int mtime, int bytesWritten) {
		forget(fname);
		normal.put(fname, new HgDirstate.Record(fmode, bytesWritten, mtime, fname, null));
	}
	
	public void recordUncertain(Path fname) {
		// `hg revert` puts "n 0 -1 unset" for the reverted file, so shall we
		forget(fname);
		normal.put(fname, new HgDirstate.Record(0, -1, -1, fname, null));
	}
	
	public void recordAdded(Path fname, Flags flags, int size) {
		forget(fname);
		added.put(fname, new HgDirstate.Record(0, -1, -1, fname, null));
	}
	
	public void recordRemoved(Path fname) {
		HgDirstate.Record r = forget(fname);
		HgDirstate.Record n;
		if (r == null) {
			n = new HgDirstate.Record(0, -1, -1, fname, null);
		} else {
			n = new HgDirstate.Record(r.mode(), r.size(), r.modificationTime(), fname, r.copySource());
		}
		removed.put(fname, n);
	}
	
	private HgDirstate.Record forget(Path fname) {
		HgDirstate.Record r;
		if ((r = normal.remove(fname)) != null) {
			return r;
		}
		if ((r = added.remove(fname)) != null) {
			return r;
		}
		if ((r = removed.remove(fname)) != null) {
			return r;
		}
		return merged.remove(fname);
	}

	public void serialize(WritableByteChannel dest) throws IOException {
		assert parent1 != null : "Parent(s) of the working directory shall be set first";
		ByteBuffer bb = ByteBuffer.allocate(256);
		bb.put(parent1.toByteArray());
		bb.put(parent2.toByteArray());
		bb.flip();
		// header
		int written = dest.write(bb);
		if (written != bb.limit()) {
			throw new IOException("Incomplete write");
		}
		bb.clear();
		// entries
		@SuppressWarnings("unchecked")
		Map<Path, HgDirstate.Record>[] all = new Map[] {normal, added, removed, merged};
		ByteBuffer recordTypes = ByteBuffer.allocate(4);
		recordTypes.put((byte) 'n').put((byte) 'a').put((byte) 'r').put((byte) 'm').flip();
		for (Map<Path, HgDirstate.Record> m : all) {
			final byte recordType = recordTypes.get();
			for (HgDirstate.Record r : m.values()) {
				// regular entry is 1+4+4+4+4+fname.length bytes
				// it might get extended with copy origin, prepended with 0 byte 
				byte[] fname = encodingHelper.toDirstate(r.name());
				byte[] copyOrigin = r.copySource() == null ? null : encodingHelper.toDirstate(r.copySource());
				int length = fname.length + (copyOrigin == null ? 0 : (1 + copyOrigin.length)); 
				bb = ensureCapacity(bb, 17 + length);
				bb.put(recordType);
				bb.putInt(r.mode());
				bb.putInt(r.size());
				bb.putInt(r.modificationTime());
				bb.putInt(length);
				bb.put(fname);
				if (copyOrigin != null) {
					bb.put((byte) 0);
					bb.put(copyOrigin);
				}
				bb.flip();
				written = dest.write(bb);
				if (written != bb.limit()) {
					throw new IOException("Incomplete write");
				}
				bb.clear();
			}
		}
	}
	
	public void serialize() throws HgIOException {
		File dirstateFile = hgRepo.getRepositoryFile(HgRepositoryFiles.Dirstate);
		try {
			FileChannel dirstate = new FileOutputStream(dirstateFile).getChannel();
			serialize(dirstate);
			dirstate.close();
		} catch (IOException ex) {
			throw new HgIOException("Can't write down new directory state", ex, dirstateFile);
		}
	}
	
	public void fillFrom(DirstateReader dirstate) {
		// TODO preserve order, if reasonable and possible 
		dirstate.readInto(new HgDirstate.Inspector() {
			
			public boolean next(EntryKind kind, Record entry) {
				switch (kind) {
				case Normal: normal.put(entry.name(), entry); break;
				case Added : added.put(entry.name(), entry); break;
				case Removed : removed.put(entry.name(), entry); break;
				case Merged : merged.put(entry.name(), entry); break;
				default: throw new HgInvalidStateException(String.format("Unexpected entry in the dirstate: %s", kind));
				}
				return true;
			}
		});
		parents(dirstate.parents().first(), dirstate.parents().second());
	}


	private static ByteBuffer ensureCapacity(ByteBuffer buf, int cap) {
		if (buf.capacity() >= cap) {
			return buf;
		}
		return ByteBuffer.allocate(cap);
	}
}
