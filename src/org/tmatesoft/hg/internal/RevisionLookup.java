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

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;

import java.util.Arrays;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgRevisionMap;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * Lite alternative to {@link HgRevisionMap}, to speed up nodeid to index conversion without consuming too much memory.
 * E.g. for a 100k revisions, {@link HgRevisionMap} consumes 3 * (N * sizeof(int)) for indexes plus 48 bytes per 
 * Nodeid instance, total (12+48)*N = 6 mb of memory. {RevisionLookup} instead keeps only Nodeid hashes, (N * sizeof(int) = 400 kb),
 * but is slower in lookup, O(N/2) to find potential match plus disk read operatin (or few, in an unlikely case of hash collisions).
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevisionLookup implements RevlogStream.Inspector {
	
	private final RevlogStream content;
	private int[] nodeidHashes;

	public RevisionLookup(RevlogStream stream) {
		assert stream != null;
		content = stream;
	}
	
	public static RevisionLookup createFor(RevlogStream stream) throws HgRuntimeException {
		RevisionLookup rv = new RevisionLookup(stream);
		int revCount = stream.revisionCount();
		rv.prepare(revCount);
		if (revCount > 0) {
			stream.iterate(0, revCount - 1, false, rv);
		}
		return rv;
	}

	public void prepare(int count) {
		nodeidHashes = new int[count];
		Arrays.fill(nodeidHashes, BAD_REVISION);
	}
	public void next(int index, byte[] nodeid) {
		nodeidHashes[index] = Nodeid.hashCode(nodeid);
	}
	public void next(int index, Nodeid nodeid) {
		nodeidHashes[index] = nodeid.hashCode();
	}
	public int findIndex(Nodeid nodeid) throws HgInvalidControlFileException, HgInvalidRevisionException {
		final int hash = nodeid.hashCode();
		for (int i = 0; i < nodeidHashes.length; i++) {
			if (nodeidHashes[i] == hash) {
				byte[] nodeidAtI = content.nodeid(i);
				if (nodeid.equalsTo(nodeidAtI)) {
					return i;
				}
			}
			// else: false match (only 4 head bytes matched, continue loop
		}
		return BAD_REVISION;
	}

	public void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
		next(revisionIndex, nodeid);
	}
}
