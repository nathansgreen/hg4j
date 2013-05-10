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

import static org.tmatesoft.hg.repo.HgRepositoryFiles.Branch;
import static org.tmatesoft.hg.repo.HgRepositoryFiles.Dirstate;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.DirstateBuilder;
import org.tmatesoft.hg.internal.EncodingHelper;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.WorkingDirFileWriter;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgDirstate;
import org.tmatesoft.hg.repo.HgDirstate.EntryKind;
import org.tmatesoft.hg.repo.HgDirstate.Record;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Update working directory to specific state, 'hg checkout' counterpart.
 * For the time being, only 'clean' checkout is supported ('hg co --clean')
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgCheckoutCommand extends HgAbstractCommand<HgCheckoutCommand>{

	private final HgRepository repo;
	private final CsetParamKeeper revisionToCheckout;
	private boolean cleanCheckout;

	public HgCheckoutCommand(HgRepository hgRepo) {
		repo = hgRepo;
		revisionToCheckout = new CsetParamKeeper(repo);
	}
	
	/**
	 * Whether to discard all uncommited changes prior to check-out.
	 * 
	 * NOTE, at the moment, only clean checkout is supported!
	 *  
	 * @param clean <code>true</code> to discard any change
	 * @return <code>this</code> for convenience
	 */
	public HgCheckoutCommand clean(boolean clean) {
		cleanCheckout = clean;
		return this;
	}
	
	/**
	 * Select revision to check out
	 * 
	 * @param nodeid revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset 
	 */
	public HgCheckoutCommand changeset(Nodeid nodeid) throws HgBadArgumentException {
		revisionToCheckout.set(nodeid);
		return this;
	}

	/**
	 * Select revision to check out using local revision index
	 * 
	 * @param changesetIndex local changelog revision index, or {@link HgRepository#TIP}
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset 
	 */
	public HgCheckoutCommand changeset(int changesetIndex) throws HgBadArgumentException {
		revisionToCheckout.set(changesetIndex);
		return this;
	}

	/**
	 * Update working copy to match state of the selected revision.
	 * 
	 * @throws HgIOException to indicate troubles updating files in working copy
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws CancelledException if execution of the command was cancelled
	 */
	public void execute() throws HgException, CancelledException {
		try {
			final ProgressSupport progress = getProgressSupport(null);
			final CancelSupport cancellation = getCancelSupport(null, true);
			cancellation.checkCancelled();
			progress.start(6);
			Internals internalRepo = Internals.getInstance(repo);
			if (cleanCheckout) {
				// remove tracked files from wd (perhaps, just forget 'Added'?)
				// for now, just delete each and every tracked file
				// TODO WorkingCopy container with getFile(HgDataFile/Path) to access files in WD
				HgDirstate dirstate = new HgInternals(repo).getDirstate();
				dirstate.walk(new HgDirstate.Inspector() {
					
					public boolean next(EntryKind kind, Record entry) {
						File f = new File(repo.getWorkingDir(), entry.name().toString());
						if (f.exists()) {
							f.delete();
						}
						return true;
					}
				});
			} else {
				throw new HgBadArgumentException("Sorry, only clean checkout is supported now, use #clean(true)", null);
			}
			progress.worked(1);
			cancellation.checkCancelled();
			final DirstateBuilder dirstateBuilder = new DirstateBuilder(internalRepo);
			final CheckoutWorker worker = new CheckoutWorker(internalRepo);
			HgManifest.Inspector insp = new HgManifest.Inspector() {
				
				public boolean next(Nodeid nid, Path fname, Flags flags) {
					if (worker.next(nid, fname, flags)) {
						// Mercurial seems to write "n   0  -1   unset fname" on `hg --clean co -rev <earlier rev>`
						// and the reason for 'force lookup' I suspect is a slight chance of simultaneous modification
						// of the file by user that doesn't alter its size the very second dirstate is being written
						// (or the file is being updated and the update brought in changes that didn't alter the file size - 
						// with size and timestamp set, later `hg status` won't notice these changes)
						
						// However, as long as we use this class to write clean copies of the files, we can put all the fields
						// right away.
						int mtime = worker.getLastFileModificationTime();
						// Manifest flags are chars (despite octal values `hg manifest --debug` displays),
						// while dirstate keeps actual unix flags.
						int fmode = worker.getLastFileMode();
						dirstateBuilder.recordNormal(fname, fmode, mtime, worker.getLastFileSize());
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
			// checkout tip if no revision set
			final int coRevision = revisionToCheckout.get(HgRepository.TIP);
			dirstateBuilder.parents(repo.getChangelog().getRevision(coRevision), null);
			repo.getManifest().walk(coRevision, coRevision, insp);
			worker.checkFailed();
			progress.worked(3);
			cancellation.checkCancelled();
			File dirstateFile = internalRepo.getRepositoryFile(Dirstate);
			try {
				FileChannel dirstateFileChannel = new FileOutputStream(dirstateFile).getChannel();
				dirstateBuilder.serialize(dirstateFileChannel);
				dirstateFileChannel.close();
			} catch (IOException ex) {
				throw new HgIOException("Can't write down new directory state", ex, dirstateFile);
			}
			progress.worked(1);
			cancellation.checkCancelled();
			String branchName = repo.getChangelog().range(coRevision, coRevision).get(0).branch();
			assert branchName != null;
			File branchFile = internalRepo.getRepositoryFile(Branch);
			if (HgRepository.DEFAULT_BRANCH_NAME.equals(branchName)) {
				// clean actual branch, if any
				if (branchFile.isFile()) {
					branchFile.delete();
				}
			} else {
				try {
					// branch file is UTF-8, see http://mercurial.selenic.com/wiki/EncodingStrategy#UTF-8_strings
					OutputStreamWriter ow = new OutputStreamWriter(new FileOutputStream(branchFile), EncodingHelper.getUTF8());
					ow.write(branchName);
					ow.close();
				} catch (IOException ex) {
					throw new HgIOException("Can't write down branch information", ex, branchFile);
				}
			}
			progress.worked(1);
			progress.done();
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}

	static class CheckoutWorker {
		private final Internals hgRepo;
		private HgException failure;
		private int lastWrittenFileSize;
		private int lastFileMode;
		private int lastFileModificationTime;
		
		CheckoutWorker(Internals implRepo) {
			hgRepo = implRepo;
		}
		
		public boolean next(Nodeid nid, Path fname, Flags flags) {
			WorkingDirFileWriter workingDirWriter = null;
			try {
				HgDataFile df = hgRepo.getRepo().getFileNode(fname);
				int fileRevIndex = df.getRevisionIndex(nid);
				// check out files based on manifest
				workingDirWriter = new WorkingDirFileWriter(hgRepo);
				workingDirWriter.processFile(df, fileRevIndex, flags);
				lastWrittenFileSize = workingDirWriter.bytesWritten();
				lastFileMode = workingDirWriter.fmode();
				lastFileModificationTime = workingDirWriter.mtime();
				return true;
			} catch (IOException ex) {
				failure = new HgIOException("Failed to write down file revision", ex, workingDirWriter.getDestinationFile());
			} catch (HgRuntimeException ex) {
				failure = new HgLibraryFailureException(ex);
			}
			return false;
		}
		
		public int getLastFileMode() {
			return lastFileMode;
		}
		
		public int getLastFileModificationTime() {
			return lastFileModificationTime;
		}
		
		public int getLastFileSize() {
			return lastWrittenFileSize;
		}
		
		public void checkFailed() throws HgException {
			if (failure != null) {
				throw failure;
			}
		}
	};
}
