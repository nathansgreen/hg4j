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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgDirstate;
import org.tmatesoft.hg.repo.HgManifest.Flags;
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
	private List<HgDirstate.Record> normal = new ArrayList<HgDirstate.Record>();
	private Nodeid parent1, parent2;
	private final EncodingHelper encodingHelper;
	
	public DirstateBuilder(EncodingHelper encHelper) {
		encodingHelper = encHelper;
	}
	
	public void parents(Nodeid p1, Nodeid p2) {
		parent1 = p1 == null ? Nodeid.NULL : p1;
		parent2 = p2 == null ? Nodeid.NULL : p2;
	}
	
	public void recordNormal(Path fname, Flags flags, int bytesWritten) {
		// Mercurial seems to write "n   0  -1   unset fname" on `hg --clean co -rev <earlier rev>`
		// and the reason for 'force lookup' I suspect is a slight chance of simultaneous modification
		// of the file by user that doesn't alter its size the very second dirstate is being written
		// (or the file is being updated and the update brought in changes that didn't alter the file size - 
		// with size and timestamp set, later `hg status` won't notice these changes)
		
		// However, as long as we use this class to write clean copies of the files, we can put all the fields
		// right away.
		int fmode = flags == Flags.RegularFile ? 0666 : 0777; // FIXME actual unix flags
		int mtime = (int) (System.currentTimeMillis() / 1000);
		normal.add(new HgDirstate.Record(fmode, bytesWritten, mtime,fname, null));
		
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
		for (HgDirstate.Record r : normal) {
			// normal entry is 1+4+4+4+4+fname.length bytes
			byte[] fname = encodingHelper.toDirstate(r.name().toString());
			bb = ensureCapacity(bb, 17 + fname.length);
			bb.put((byte) 'n');
			bb.putInt(r.mode());
			bb.putInt(r.size());
			bb.putInt(r.modificationTime());
			bb.putInt(fname.length);
			bb.put(fname);
			bb.flip();
			written = dest.write(bb);
			if (written != bb.limit()) {
				throw new IOException("Incomplete write");
			}
			bb.clear();
		}
	}

	private static ByteBuffer ensureCapacity(ByteBuffer buf, int cap) {
		if (buf.capacity() >= cap) {
			return buf;
		}
		return ByteBuffer.allocate(cap);
	}
}
