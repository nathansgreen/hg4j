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
package org.tmatesoft.hg.core;

import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.DirstateBuilder;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;

/**
 * WORK IN PROGRESS.
 * 
 * Restore files to their checkout state, 'hg revert' counterpart.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress")
public class HgRevertCommand extends HgAbstractCommand<HgRevertCommand> {

	private final HgRepository repo;
	private final Set<Path> files = new LinkedHashSet<Path>();
	private CsetParamKeeper changesetToCheckout;
	private boolean keepOriginal = true;

	public HgRevertCommand(HgRepository hgRepo) {
		repo = hgRepo;
		changesetToCheckout = new CsetParamKeeper(hgRepo);
		changesetToCheckout.doSet(HgRepository.WORKING_COPY); // XXX WORKING_COPY_PARENT, in fact
	}

	/**
	 * Additive
	 * 
	 * @param paths files to revert
	 * @return <code>this</code> for convenience
	 */
	public HgRevertCommand file(Path... paths) {
		files.addAll(Arrays.asList(paths));
		return this;
	}

	/**
	 * Revert the given files to their states as of a specific revision
	 * 
	 * @param changesetRevIndex
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException
	 */
	public HgRevertCommand changeset(int changesetRevIndex) throws HgBadArgumentException {
		changesetToCheckout.set(changesetRevIndex);
		return this;
	}
	
	/**
	 * Handy supplement to {@link #changeset(int)}
	 * 
	 * @param revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException
	 */
	public HgRevertCommand changeset(Nodeid revision) throws HgBadArgumentException {
		changesetToCheckout.set(revision);
		return this;
	}
	
	// TODO keepOriginal() to save .orig (with tests!)

	/**
	 * Perform the back out for the given files
	 * 
	 * @throws HgIOException 
	 * @throws HgException
	 * @throws CancelledException
	 */
	public void execute() throws HgException, CancelledException {
		try {
			final int csetRevision;
			if (changesetToCheckout.get() == HgRepository.WORKING_COPY) {
				csetRevision = repo.getChangelog().getRevisionIndex(repo.getWorkingCopyParents().first());
			} else {
				csetRevision = changesetToCheckout.get();
			}
			Internals implRepo = Internals.getInstance(repo);
			final DirstateBuilder dirstateBuilder = new DirstateBuilder(implRepo);
			dirstateBuilder.fillFrom(new DirstateReader(implRepo, new Path.SimpleSource()));
			final HgCheckoutCommand.CheckoutWorker worker = new HgCheckoutCommand.CheckoutWorker(implRepo);
			
			HgManifest.Inspector insp = new HgManifest.Inspector() {
				
				public boolean next(Nodeid nid, Path fname, Flags flags) {
					if (worker.next(nid, fname, flags)) {
						dirstateBuilder.recordUncertain(fname);
						return true;
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

			for (Path file : files) {
				File f = new File(repo.getWorkingDir(), file.toString());
				if (f.isFile()) {
					if (keepOriginal) {
						File copy = new File(f.getParentFile(), f.getName() + ".orig");
						if (copy.exists()) {
							copy.delete();
						}
						f.renameTo(copy);
					} else {
						f.delete();
					}
				}
				repo.getManifest().walkFileRevisions(file, insp, csetRevision);
				worker.checkFailed();
			}
			dirstateBuilder.serialize();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}
}
