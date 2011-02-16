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

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.FileNotFoundException;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.Path;

/**
 * Command to obtain content of a file, 'hg cat' counterpart. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgCatCommand {

	private final HgRepository repo;
	private Path file;
	private int localRevision = TIP;
	private Nodeid revision;

	public HgCatCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public HgCatCommand file(Path fname) {
		file = fname;
		return this;
	}

	// rev can't be WORKING_COPY (if allowed, need to implement in #execute())
	public HgCatCommand revision(int rev) {
		localRevision = rev;
		revision = null;
		return this;
	}
	
	public HgCatCommand revision(Nodeid nodeid) {
		revision = nodeid;
		localRevision = BAD_REVISION;
		return this;
	}

	public void execute(ByteChannel sink) throws Exception /*TODO own exception type*/ {
		if (localRevision == BAD_REVISION && revision == null) {
			throw new IllegalArgumentException("Either local file revision number or nodeid shall be specified");
		}
		if (file == null) {
			throw new IllegalArgumentException("Name of the file is missing");
		}
		if (sink == null) {
			throw new IllegalArgumentException();
		}
		HgDataFile dataFile = repo.getFileNode(file);
		if (!dataFile.exists()) {
			throw new FileNotFoundException();
		}
		int revToExtract;
		if (revision != null) {
			revToExtract = dataFile.getLocalRevision(revision);
		} else {
			revToExtract = localRevision;
		}
		dataFile.content(revToExtract, sink, true);
	}
}
