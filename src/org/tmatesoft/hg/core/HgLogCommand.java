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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.IOException;
import java.util.Calendar;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;


/**
 * Access to changelog, 'hg log' command counterpart.
 * 
 * <pre>
 * Usage:
 *   new LogCommand().limit(20).branch("maintenance-2.1").user("me").execute(new MyHandler());
 * </pre>
 * Not thread-safe (each thread has to use own {@link HgLogCommand} instance).
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgLogCommand extends HgAbstractCommand<HgLogCommand> implements HgChangelog.Inspector {

	private final HgRepository repo;
	private Set<String> users;
	private Set<String> branches;
	private int limit = 0, count = 0;
	private int startRev = 0, endRev = TIP;
	private Calendar date;
	private Path file;
	private boolean followHistory; // makes sense only when file != null
	private ChangesetTransformer csetTransform;
	private HgChangelog.ParentWalker parentHelper;
	
	public HgLogCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}

	/**
	 * Limit search to specified user. Multiple user names may be specified. Once set, user names can't be 
	 * cleared, use new command instance in such cases.
	 * @param user - full or partial name of the user, case-insensitive, non-null.
	 * @return <code>this</code> instance for convenience
	 * @throws IllegalArgumentException when argument is null
	 */
	public HgLogCommand user(String user) {
		if (user == null) {
			throw new IllegalArgumentException();
		}
		if (users == null) {
			users = new TreeSet<String>();
		}
		users.add(user.toLowerCase());
		return this;
	}

	/**
	 * Limit search to specified branch. Multiple branch specification possible (changeset from any of these 
	 * would be included in result). If unspecified, all branches are considered. There's no way to clean branch selection 
	 * once set, create fresh new command instead.
	 * @param branch - branch name, case-sensitive, non-null.
	 * @return <code>this</code> instance for convenience
	 * @throws IllegalArgumentException when branch argument is null
	 */
	public HgLogCommand branch(String branch) {
		if (branch == null) {
			throw new IllegalArgumentException();
		}
		if (branches == null) {
			branches = new TreeSet<String>();
		}
		branches.add(branch);
		return this;
	}
	
	// limit search to specific date
	// multiple?
	public HgLogCommand date(Calendar date) {
		this.date = date;
		// FIXME implement
		// isSet(field) - false => don't use in detection of 'same date'
		throw HgRepository.notImplemented();
	}
	
	/**
	 * 
	 * @param num - number of changeset to produce. Pass 0 to clear the limit. 
	 * @return <code>this</code> instance for convenience
	 */
	public HgLogCommand limit(int num) {
		limit = num;
		return this;
	}

	/**
	 * Limit to specified subset of Changelog, [min(rev1,rev2), max(rev1,rev2)], inclusive.
	 * Revision may be specified with {@link HgRepository#TIP}  
	 * @param rev1 - local revision number
	 * @param rev2 - local revision number
	 * @return <code>this</code> instance for convenience
	 */
	public HgLogCommand range(int rev1, int rev2) {
		if (rev1 != TIP && rev2 != TIP) {
			startRev = rev2 < rev1 ? rev2 : rev1;
			endRev = startRev == rev2 ? rev1 : rev2;
		} else if (rev1 == TIP && rev2 != TIP) {
			startRev = rev2;
			endRev = rev1;
		} else {
			startRev = rev1;
			endRev = rev2;
		}
		return this;
	}
	
	/**
	 * Visit history of a given file only.
	 * @param file path relative to repository root. Pass <code>null</code> to reset.
	 * @param followCopyRename true to report changesets of the original file(-s), if copy/rename ever occured to the file. 
	 */
	public HgLogCommand file(Path file, boolean followCopyRename) {
		// multiple? Bad idea, would need to include extra method into Handler to tell start of next file
		this.file = file;
		followHistory = followCopyRename;
		return this;
	}
	
	/**
	 * Handy analog of {@link #file(Path, boolean)} when clients' paths come from filesystem and need conversion to repository's 
	 */
	public HgLogCommand file(String file, boolean followCopyRename) {
		return file(Path.create(repo.getToRepoPathHelper().rewrite(file)), followCopyRename);
	}

	/**
	 * Similar to {@link #execute(org.tmatesoft.hg.repo.RawChangeset.Inspector)}, collects and return result as a list.
	 */
	public List<HgChangeset> execute() throws HgDataStreamException {
		CollectHandler collector = new CollectHandler();
		try {
			execute(collector);
		} catch (HgCallbackTargetException ex) {
			// can't happen as long as our CollectHandler doesn't throw any exception
			throw new HgBadStateException(ex.getCause());
		} catch (CancelledException ex) {
			// can't happen, see above
			throw new HgBadStateException(ex);
		}
		return collector.getChanges();
	}

	/**
	 * 
	 * @param handler callback to process changesets.
	 * @throws IllegalArgumentException when inspector argument is null
	 * @throws ConcurrentModificationException if this log command instance is already running
	 */
	public void execute(HgChangesetHandler handler) throws HgDataStreamException, HgCallbackTargetException, CancelledException {
		if (handler == null) {
			throw new IllegalArgumentException();
		}
		if (csetTransform != null) {
			throw new ConcurrentModificationException();
		}
		final ProgressSupport progressHelper = getProgressSupport(handler);
		try {
			count = 0;
			HgChangelog.ParentWalker pw = parentHelper; // leave it uninitialized unless we iterate whole repo
			if (file == null) {
				pw = getParentHelper();
			}
			// ChangesetTransfrom creates a blank PathPool, and #file(String, boolean) above 
			// may utilize it as well. CommandContext? How about StatusCollector there as well?
			csetTransform = new ChangesetTransformer(repo, handler, pw, progressHelper, getCancelSupport(handler));
			if (file == null) {
				progressHelper.start(endRev - startRev + 1);
				repo.getChangelog().range(startRev, endRev, this);
				csetTransform.checkFailure();
			} else {
				progressHelper.start(-1/*XXX enum const, or a dedicated method startUnspecified(). How about startAtLeast(int)?*/);
				HgDataFile fileNode = repo.getFileNode(file);
				fileNode.history(startRev, endRev, this);
				csetTransform.checkFailure();
				if (fileNode.isCopy()) {
					// even if we do not follow history, report file rename
					do {
						if (handler instanceof FileHistoryHandler) {
							HgFileRevision src = new HgFileRevision(repo, fileNode.getCopySourceRevision(), fileNode.getCopySourceName());
							HgFileRevision dst = new HgFileRevision(repo, fileNode.getRevision(0), fileNode.getPath());
							try {
								((FileHistoryHandler) handler).copy(src, dst);
							} catch (RuntimeException ex) {
								throw new HgCallbackTargetException(ex).setRevision(fileNode.getCopySourceRevision()).setFileName(fileNode.getCopySourceName());
							}
						}
						if (limit > 0 && count >= limit) {
							// if limit reach, follow is useless.
							break;
						}
						if (followHistory) {
							fileNode = repo.getFileNode(fileNode.getCopySourceName());
							fileNode.history(this);
							csetTransform.checkFailure();
						}
					} while (followHistory && fileNode.isCopy());
				}
			}
		} finally {
			csetTransform = null;
			progressHelper.done();
		}
	}

	//
	
	public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
		if (limit > 0 && count >= limit) {
			return;
		}
		if (branches != null && !branches.contains(cset.branch())) {
			return;
		}
		if (users != null) {
			String csetUser = cset.user().toLowerCase();
			boolean found = false;
			for (String u : users) {
				if (csetUser.indexOf(u) != -1) {
					found = true;
					break;
				}
			}
			if (!found) {
				return;
			}
		}
		if (date != null) {
			// FIXME
		}
		count++;
		csetTransform.next(revisionNumber, nodeid, cset);
	}
	
	private HgChangelog.ParentWalker getParentHelper() {
		if (parentHelper == null) {
			parentHelper = repo.getChangelog().new ParentWalker();
			parentHelper.init();
		}
		return parentHelper;
	}


	/**
	 * @deprecated Use {@link HgChangesetHandler} instead. This interface is left temporarily for compatibility.
	 */
	@Deprecated()
	public interface Handler extends HgChangesetHandler {
	}
	
	/**
	 * When {@link HgLogCommand} is executed against file, handler passed to {@link HgLogCommand#execute(HgChangesetHandler)} may optionally
	 * implement this interface to get information about file renames. Method {@link #copy(FileRevision, FileRevision)} would
	 * get invoked prior any changeset of the original file (if file history being followed) is reported via {@link #next(HgChangeset)}.
	 * 
	 * For {@link HgLogCommand#file(Path, boolean)} with renamed file path and follow argument set to false, 
	 * {@link #copy(FileRevision, FileRevision)} would be invoked for the first copy/rename in the history of the file, but not 
	 * followed by any changesets. 
	 *
	 * @author Artem Tikhomirov
	 * @author TMate Software Ltd.
	 */
	public interface FileHistoryHandler extends HgChangesetHandler {
		// XXX perhaps, should distinguish copy from rename? And what about merged revisions and following them?
		void copy(FileRevision from, FileRevision to);
	}
	
	public static class CollectHandler implements HgChangesetHandler {
		private final List<HgChangeset> result = new LinkedList<HgChangeset>();

		public List<HgChangeset> getChanges() {
			return Collections.unmodifiableList(result);
		}

		public void next(HgChangeset changeset) {
			result.add(changeset.clone());
		}
	}

	/**
	 * @deprecated pulled up, use {@link HgFileRevision} instead.
	 */
	@Deprecated
	public interface FileRevision {
		public abstract Path getPath();
		public abstract Nodeid getRevision();
		public abstract void putContentTo(ByteChannel sink) throws HgDataStreamException, CancelledException;
	}
}
