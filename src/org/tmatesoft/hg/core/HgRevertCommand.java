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

import org.tmatesoft.hg.internal.COWTransaction;
import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.DirstateBuilder;
import org.tmatesoft.hg.internal.DirstateReader;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.Transaction;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryLock;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Restore files to their checkout state, 'hg revert' counterpart.
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
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
	 * @throws HgIOException to indicate troubles updating files in working copy
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws CancelledException if execution of the command was cancelled
	 */
	public void execute() throws HgException, CancelledException {
		final HgRepositoryLock wdLock = repo.getWorkingDirLock();
		wdLock.acquire();
		try {
			final ProgressSupport progress = getProgressSupport(null);
			final CancelSupport cancellation = getCancelSupport(null, true);
			cancellation.checkCancelled();
			progress.start(files.size() + 2);
			final int csetRevision;
			if (changesetToCheckout.get() == HgRepository.WORKING_COPY) {
				csetRevision = repo.getChangelog().getRevisionIndex(repo.getWorkingCopyParents().first());
			} else {
				csetRevision = changesetToCheckout.get();
			}
			Internals implRepo = Internals.getInstance(repo);
			final DirstateBuilder dirstateBuilder = new DirstateBuilder(implRepo);
			dirstateBuilder.fillFrom(new DirstateReader(implRepo, new Path.SimpleSource()));
			progress.worked(1);
			cancellation.checkCancelled();
			
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
				progress.worked(1);
				cancellation.checkCancelled();
			}
			Transaction.Factory trFactory = new COWTransaction.Factory();
			Transaction tr = trFactory.create(repo);
			try {
				// TODO same code in HgAddRemoveCommand and similar in HgCommitCommand
				dirstateBuilder.serialize(tr);
				tr.commit();
			} catch (RuntimeException ex) {
				tr.rollback();
				throw ex;
			} catch (HgException ex) {
				tr.rollback();
				throw ex;
			}
			progress.worked(1);
			progress.done();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			wdLock.release();
		}
	}
}
