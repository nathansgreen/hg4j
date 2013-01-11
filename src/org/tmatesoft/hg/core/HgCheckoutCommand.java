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
package org.tmatesoft.hg.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.internal.DirstateBuilder;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.WorkingDirFileWriter;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;

/**
 * WORK IN PROGRESS.
 * 
 * Update working directory to specific state, 'hg checkout' counterpart.
 * For the time being, only 'clean' checkout is supported ('hg co --clean')
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public class HgCheckoutCommand extends HgAbstractCommand<HgCheckoutCommand>{

	private final HgRepository repo;
	private int revisionToCheckout = HgRepository.BAD_REVISION;

	public HgCheckoutCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	/**
	 * Select revision to check out
	 * 
	 * @param nodeid revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset 
	 */
	public HgCheckoutCommand changeset(Nodeid nodeid) throws HgBadArgumentException {
		try {
			return changeset(repo.getChangelog().getRevisionIndex(nodeid));
		} catch (HgInvalidRevisionException ex) {
			throw new HgBadArgumentException("Can't find revision", ex).setRevision(nodeid);
		}
	}

	/**
	 * Select revision to check out using local revision index
	 * 
	 * @param changesetIndex local revision index
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset 
	 */
	public HgCheckoutCommand changeset(int changesetIndex) throws HgBadArgumentException {
		int lastCsetIndex = repo.getChangelog().getLastRevision();
		if (changesetIndex < 0 || changesetIndex > lastCsetIndex) {
			throw new HgBadArgumentException(String.format("Bad revision index %d, value from [0..%d] expected", changesetIndex, lastCsetIndex), null).setRevisionIndex(changesetIndex);
		}
		revisionToCheckout = changesetIndex;
		return this;
	}

	/**
	 * 
	 * @throws HgIOException to indicate troubles updating files in working copy
	 * @throws HgException
	 * @throws CancelledException
	 */
	public void execute() throws HgException, CancelledException {
		Internals internalRepo = Internals.getInstance(repo);
		// remove tracked files from wd (perhaps, just forget 'Added'?)
		// TODO
		final DirstateBuilder dirstateBuilder = new DirstateBuilder(internalRepo.buildFileNameEncodingHelper());
		final Exception[] failure = new Exception[1];
		HgManifest.Inspector worker = new HgManifest.Inspector() {
			
			public boolean next(Nodeid nid, Path fname, Flags flags) {
				try {
					HgDataFile df = repo.getFileNode(fname);
					int fileRevIndex = df.getRevisionIndex(nid);
					// check out files based on manifest
					// FIXME links!
					WorkingDirFileWriter workingDirWriter = new WorkingDirFileWriter(repo);
					workingDirWriter.processFile(df, fileRevIndex);
					// new dirstate based on manifest
					dirstateBuilder.recordNormal(fname, flags, workingDirWriter.bytesWritten());
					return true;
				} catch (IOException ex) {
					failure[0] = ex;
				} catch (HgRuntimeException ex) {
					failure[0] = ex;
				}
				return false;
			}
			
			public boolean end(int manifestRevision) {
				return false;
			}
			
			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				return true;
			}
		};
		dirstateBuilder.parents(repo.getChangelog().getRevision(revisionToCheckout), null);
		repo.getManifest().walk(revisionToCheckout, revisionToCheckout, worker);
		if (failure[0] != null) {
			if (failure[0] instanceof IOException) {
				throw new HgIOException("Failed to write down file revision", failure[0], /*FIXME file*/null);
			}
			if (failure[0] instanceof HgRuntimeException) {
				throw new HgLibraryFailureException((HgRuntimeException) failure[0]);
			}
			HgInvalidStateException e = new HgInvalidStateException("Unexpected exception");
			e.initCause(failure[0]);
			throw e;
		}
		File dirstateFile = internalRepo.getFileFromRepoDir("dirstate");
		try {
			FileChannel dirstate = new FileOutputStream(dirstateFile).getChannel();
			dirstateBuilder.serialize(dirstate);
			dirstate.close();
		} catch (IOException ex) {
			throw new HgIOException("Can't write down new directory state", ex, dirstateFile);
		}
		// FIXME write down branch file
	}
}
