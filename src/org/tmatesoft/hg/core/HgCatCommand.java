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

import static org.tmatesoft.hg.repo.HgInternals.wrongLocalRevision;
import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
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

	/**
	 * File to read, required parameter 
	 * @param fname path to a repository file, can't be <code>null</code>
	 * @return <code>this</code> for convenience
	 * @throws IllegalArgumentException if supplied fname is null or points to directory
	 */
	public HgCatCommand file(Path fname) {
		if (fname == null || fname.isDirectory()) {
			throw new IllegalArgumentException(String.valueOf(fname));
		}
		file = fname;
		return this;
	}

	/**
	 * Invocation of this method clears revision set with {@link #revision(Nodeid)} or {@link #revision(int)} earlier.
	 * XXX rev can't be WORKING_COPY (if allowed, need to implement in #execute())
	 * @param rev local revision number, non-negative, or one of predefined constants. Note, use of {@link HgRepository#BAD_REVISION}, 
	 * although possible, makes little sense (command would fail if executed).  
 	 * @return <code>this</code> for convenience
	 */
	public HgCatCommand revision(int rev) {
		if (wrongLocalRevision(rev)) {
			throw new IllegalArgumentException(String.valueOf(rev));
		}
		localRevision = rev;
		revision = null;
		return this;
	}
	
	/**
	 * Select revision to read. Invocation of this method clears revision set with {@link #revision(int)} or {@link #revision(Nodeid)} earlier.
	 * 
	 * @param nodeid - unique revision identifier, Note, use of <code>null</code> or {@link Nodeid#NULL} is senseless
	 * @return <code>this</code> for convenience
	 */
	public HgCatCommand revision(Nodeid nodeid) {
		if (nodeid != null && nodeid.isNull()) {
			nodeid = null;
		}
		revision = nodeid;
		localRevision = BAD_REVISION;
		return this;
	}

	/**
	 * Runs the command with current set of parameters and pipes data to provided sink.
	 * 
	 * @param sink output channel to write data to.
	 * @throws HgDataStreamException 
	 * @throws IllegalArgumentException when command arguments are incomplete or wrong
	 */
	public void execute(ByteChannel sink) throws HgDataStreamException, IOException, CancelledException {
		if (localRevision == BAD_REVISION && revision == null) {
			throw new IllegalArgumentException("Either local file revision number or nodeid shall be specified");
		}
		if (file == null) {
			throw new IllegalArgumentException("Name of the file is missing");
		}
		if (sink == null) {
			throw new IllegalArgumentException("Need an output channel");
		}
		HgDataFile dataFile = repo.getFileNode(file);
		if (!dataFile.exists()) {
			throw new HgDataStreamException(file.toString(), new FileNotFoundException(file.toString()));
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
