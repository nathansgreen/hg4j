/*
 * Copyright (c) 2011 TMate Software Ltd
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
package org.tmatesoft.hg.core;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;

/**
 * Keeps together information about specific file revision
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgFileRevision {
	private final HgRepository repo;
	private final Nodeid revision;
	private final Path path;
	private Path origin;
	private Boolean isCopy = null; // null means not yet known

	public HgFileRevision(HgRepository hgRepo, Nodeid rev, Path p) {
		if (hgRepo == null || rev == null || p == null) {
			// since it's package local, it is our code to blame for non validated arguments
			throw new IllegalArgumentException();
		}
		repo = hgRepo;
		revision = rev;
		path = p;
	}

	// this cons shall be used when we know whether p was a copy. Perhaps, shall pass Map<Path,Path> instead to stress orig argument is not optional  
	HgFileRevision(HgRepository hgRepo, Nodeid rev, Path p, Path orig) {
		this(hgRepo, rev, p);
		isCopy = Boolean.valueOf(orig == null);
		origin = orig; 
	}
	
	public Path getPath() {
		return path;
	}
	public Nodeid getRevision() {
		return revision;
	}
	public boolean wasCopied() {
		if (isCopy == null) {
			checkCopy();
		}
		return isCopy.booleanValue();
	}
	/**
	 * @return <code>null</code> if {@link #wasCopied()} is <code>false</code>, name of the copy source otherwise.
	 */
	public Path getOriginIfCopy() {
		if (wasCopied()) {
			return origin;
		}
		return null;
	}

	public void putContentTo(ByteChannel sink) throws HgDataStreamException, CancelledException {
		HgDataFile fn = repo.getFileNode(path);
		int localRevision = fn.getLocalRevision(revision);
		fn.contentWithFilters(localRevision, sink);
	}

	private void checkCopy() {
		HgDataFile fn = repo.getFileNode(path);
		try {
			if (fn.isCopy()) {
				if (fn.getRevision(0).equals(revision)) {
					// this HgFileRevision represents first revision of the copy
					isCopy = Boolean.TRUE;
					origin = fn.getCopySourceName();
					return;
				}
			}
		} catch (HgDataStreamException ex) {
			HgInternals.getContext(repo).getLog().error(getClass(), ex, null);
		}
		isCopy = Boolean.FALSE;
	}
}
