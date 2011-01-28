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

import java.util.Calendar;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.repo.Changeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.util.PathPool;


/**
 * <pre>
 *   new LogCommand().limit(20).branch("maintenance-2.1").user("me").execute(new MyHandler());
 * </pre>
 * Not thread-safe (each thread has to use own {@link LogCommand} instance).
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class LogCommand implements Changeset.Inspector {

	private final HgRepository repo;
	private Set<String> users;
	private Set<String> branches;
	private int limit = 0, count = 0;
	private int startRev = 0, endRev = TIP;
	private Handler delegate;
	private Calendar date;
	private Path file;
	private boolean followHistory; // makes sense only when file != null
	private Cset changeset;
	
	public LogCommand(HgRepository hgRepo) {
		this.repo = hgRepo;
	}

	/**
	 * Limit search to specified user. Multiple user names may be specified.
	 * @param user - full or partial name of the user, case-insensitive, non-null.
	 * @return <code>this</code> instance for convenience
	 */
	public LogCommand user(String user) {
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
	 * would be included in result). If unspecified, all branches are considered.
	 * @param branch - branch name, case-sensitive, non-null.
	 * @return <code>this</code> instance for convenience
	 */
	public LogCommand branch(String branch) {
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
	public LogCommand date(Calendar date) {
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
	public LogCommand limit(int num) {
		limit = num;
		return this;
	}

	/**
	 * Limit to specified subset of Changelog, [min(rev1,rev2), max(rev1,rev2)], inclusive.
	 * Revision may be specified with {@link HgRepository#TIP}  
	 * @param rev1
	 * @param rev2
	 * @return <code>this</code> instance for convenience
	 */
	public LogCommand range(int rev1, int rev2) {
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
	public LogCommand file(Path file, boolean followCopyRename) {
		// multiple? Bad idea, would need to include extra method into Handler to tell start of next file
		this.file = file;
		followHistory = followCopyRename;
		return this;
	}

	/**
	 * Similar to {@link #execute(org.tmatesoft.hg.repo.Changeset.Inspector)}, collects and return result as a list.
	 */
	public List<Cset> execute() {
		CollectHandler collector = new CollectHandler();
		execute(collector);
		return collector.getChanges();
	}

	/**
	 * 
	 * @param inspector
	 * @throws IllegalArgumentException when inspector argument is null
	 * @throws ConcurrentModificationException if this log command instance is already running
	 */
	public void execute(Handler handler) {
		if (handler == null) {
			throw new IllegalArgumentException();
		}
		if (delegate != null) {
			throw new ConcurrentModificationException();
		}
		try {
			delegate = handler;
			count = 0;
			changeset = new Cset(new HgStatusCollector(repo), new PathPool(repo.getPathHelper()));
			if (file == null) {
				repo.getChangelog().range(startRev, endRev, this);
			} else {
				HgDataFile fileNode = repo.getFileNode(file);
				fileNode.history(startRev, endRev, this);
				if (handler instanceof FileHistoryHandler && fileNode.isCopy()) {
					// even if we do not follow history, report file rename
					do {
						FileRevision src = new FileRevision(repo, fileNode.getCopySourceRevision(), fileNode.getCopySourceName());
						FileRevision dst = new FileRevision(repo, fileNode.getRevision(0), fileNode.getPath());
						((FileHistoryHandler) handler).copy(src, dst);
						if (limit > 0 && count >= limit) {
							// if limit reach, follow is useless.
							break;
						}
						if (followHistory) {
							fileNode = repo.getFileNode(src.getPath());
							fileNode.history(this);
						}
					} while (followHistory && fileNode.isCopy());
				}
			}
		} finally {
			delegate = null;
			changeset = null;
		}
	}

	//
	
	public void next(int revisionNumber, Nodeid nodeid, Changeset cset) {
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
		changeset.init(revisionNumber, nodeid, cset);
		delegate.next(changeset);
	}

	public interface Handler {
		/**
		 * @param changeset not necessarily a distinct instance each time, {@link Cset#clone() clone()} if need a copy.
		 */
		void next(Cset changeset);
	}
	
	/**
	 * When {@link LogCommand} is executed against file, handler passed to {@link LogCommand#execute(Handler)} may optionally
	 * implement this interface to get information about file renames. Method {@link #copy(FileRevision, FileRevision)} would
	 * get invoked prior any changeset of the original file (if file history being followed) is reported via {@link #next(Cset)}.
	 * 
	 * For {@link LogCommand#file(Path, boolean)} with renamed file path and follow argument set to false, 
	 * {@link #copy(FileRevision, FileRevision)} would be invoked for the first copy/rename in the history of the file, but not 
	 * followed by any changesets. 
	 *
	 * @author Artem Tikhomirov
	 * @author TMate Software Ltd.
	 */
	public interface FileHistoryHandler extends Handler {
		// XXX perhaps, should distinguish copy from rename? And what about merged revisions and following them?
		void copy(FileRevision from, FileRevision to);
	}
	
	public static class CollectHandler implements Handler {
		private final List<Cset> result = new LinkedList<Cset>();

		public List<Cset> getChanges() {
			return Collections.unmodifiableList(result);
		}

		public void next(Cset changeset) {
			result.add(changeset.clone());
		}
	}

	public static final class FileRevision {
		private final HgRepository repo;
		private final Nodeid revision;
		private final Path path;
		
		/*package-local*/FileRevision(HgRepository hgRepo, Nodeid rev, Path p) {
			if (hgRepo == null || rev == null || p == null) {
				throw new IllegalArgumentException();
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
		public byte[] getContent() {
			// XXX Content wrapper, to allow formats other than byte[], e.g. Stream, DataAccess, etc?
			return repo.getFileNode(path).content(revision);
		}
	}
}
