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

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.FileContentSupplier;
import org.tmatesoft.hg.repo.CommitFacility;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgStatusCollector.Record;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Outcome;
import org.tmatesoft.hg.util.Outcome.Kind;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 * WORK IN PROGRESS. UNSTABLE API
 * 
 * 'hg commit' counterpart, commit changes
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Work in progress. Unstable API")
public class HgCommitCommand extends HgAbstractCommand<HgCommitCommand> {

	private final HgRepository repo;
	private String message;
	private String user;
	// nodeid of newly added revision
	private Nodeid newRevision;

	public HgCommitCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	
	public HgCommitCommand message(String msg) {
		message = msg;
		return this;
	}
	
	public HgCommitCommand user(String userName) {
		user = userName;
		return this;
	}
	
	/**
	 * Tell if changes in the working directory constitute merge commit. May be invoked prior to (and independently from) {@link #execute()}
	 * 
	 * @return <code>true</code> if working directory changes are result of a merge
	 * @throws HgException subclass thereof to indicate specific issue with the repository
	 */
	public boolean isMergeCommit() throws HgException {
		int[] parents = new int[2];
		detectParentFromDirstate(parents);
		return parents[0] != NO_REVISION && parents[1] != NO_REVISION; 
	}

	/**
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws IOException propagated IO errors from status walker over working directory
	 * @throws CancelledException if execution of the command was cancelled
	 */
	public Outcome execute() throws HgException, IOException, CancelledException {
		if (message == null) {
			throw new HgBadArgumentException("Shall supply commit message", null);
		}
		try {
			int[] parentRevs = new int[2];
			detectParentFromDirstate(parentRevs);
			if (parentRevs[0] != NO_REVISION && parentRevs[1] != NO_REVISION) {
				throw new HgBadArgumentException("Sorry, I'm not yet smart enough to perform merge commits", null);
			}
			HgWorkingCopyStatusCollector sc = new HgWorkingCopyStatusCollector(repo);
			Record status = sc.status(HgRepository.WORKING_COPY);
			if (status.getModified().size() == 0 && status.getAdded().size() == 0 && status.getRemoved().size() == 0) {
				newRevision = Nodeid.NULL;
				return new Outcome(Kind.Failure, "nothing to add");
			}
			CommitFacility cf = new CommitFacility(repo, parentRevs[0], parentRevs[1]);
			for (Path m : status.getModified()) {
				HgDataFile df = repo.getFileNode(m);
				cf.add(df, new WorkingCopyContent(df));
			}
			ArrayList<FileContentSupplier> toClear = new ArrayList<FileContentSupplier>();
			for (Path a : status.getAdded()) {
				HgDataFile df = repo.getFileNode(a); // TODO need smth explicit, like repo.createNewFileNode(Path) here
				// XXX might be an interesting exercise not to demand a content supplier, but instead return a "DataRequester"
				// object, that would indicate interest in data, and this code would "push" it to requester, so that any exception
				// is handled here, right away, and won't need to travel supplier and CommitFacility. (although try/catch inside
				// supplier.read (with empty throws declaration)
				FileContentSupplier fcs = new FileContentSupplier(repo, a);
				cf.add(df, fcs);
				toClear.add(fcs);
			}
			for (Path r : status.getRemoved()) {
				HgDataFile df = repo.getFileNode(r); 
				cf.forget(df);
			}
			cf.branch(detectBranch());
			cf.user(detectUser());
			newRevision = cf.commit(message);
			// TODO toClear list is awful
			for (FileContentSupplier fcs : toClear) {
				fcs.done();
			}
			return new Outcome(Kind.Success, "Commit ok");
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
	}

	public Nodeid getCommittedRevision() {
		if (newRevision == null) {
			throw new IllegalStateException("Call #execute() first!");
		}
		return newRevision;
	}

	private String detectBranch() {
		return repo.getWorkingCopyBranchName();
	}
	
	private String detectUser() {
		if (user != null) {
			return user;
		}
		// TODO HgInternals is odd place for getNextCommitUsername()
		return new HgInternals(repo).getNextCommitUsername();
	}

	private void detectParentFromDirstate(int[] parents) {
		Pair<Nodeid, Nodeid> pn = repo.getWorkingCopyParents();
		HgChangelog clog = repo.getChangelog();
		parents[0] = pn.first().isNull() ? NO_REVISION : clog.getRevisionIndex(pn.first());
		parents[1] = pn.second().isNull() ? NO_REVISION : clog.getRevisionIndex(pn.second());
	}

	private static class WorkingCopyContent implements CommitFacility.ByteDataSupplier {
		private final HgDataFile file;
		private ByteBuffer fileContent; 

		public WorkingCopyContent(HgDataFile dataFile) {
			file = dataFile;
			if (!dataFile.exists()) {
				throw new IllegalArgumentException();
			}
		}

		public int read(ByteBuffer dst) {
			if (fileContent == null) {
				try {
					ByteArrayChannel sink = new ByteArrayChannel();
					// TODO desperately need partial read here
					file.workingCopy(sink);
					fileContent = ByteBuffer.wrap(sink.toArray());
				} catch (CancelledException ex) {
					// ByteArrayChannel doesn't cancel, never happens
					assert false;
				}
			}
			if (fileContent.remaining() == 0) {
				return -1;
			}
			int dstCap = dst.remaining();
			if (fileContent.remaining() > dstCap) {
				// save actual limit, and pretend we've got exactly desired amount of bytes
				final int lim = fileContent.limit();
				fileContent.limit(dstCap);
				dst.put(fileContent);
				fileContent.limit(lim);
			} else {
				dst.put(fileContent);
			}
			return dstCap - dst.remaining();
		}
	}
}
