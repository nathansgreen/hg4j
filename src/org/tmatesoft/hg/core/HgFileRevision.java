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

import java.io.IOException;

import org.tmatesoft.hg.repo.HgDataFile;
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
public final class HgFileRevision implements HgLogCommand.FileRevision {
	private final HgRepository repo;
	private final Nodeid revision;
	private final Path path;
	
	public HgFileRevision(HgRepository hgRepo, Nodeid rev, Path p) {
		if (hgRepo == null || rev == null || p == null) {
			// since it's package local, it is our code to blame for non validated arguments
			throw new HgBadStateException();
		}
		repo = hgRepo;
		revision = rev;
		path = p;
	}
	
	public Path getPath() {
		return path;
	}
	public Nodeid getRevision() {
		return revision;
	}
	public void putContentTo(ByteChannel sink) throws HgDataStreamException, IOException, CancelledException {
		HgDataFile fn = repo.getFileNode(path);
		int localRevision = fn.getLocalRevision(revision);
		fn.contentWithFilters(localRevision, sink);
	}

}
