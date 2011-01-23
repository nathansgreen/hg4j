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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.core;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.util.Calendar;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.util.PathPool;

import com.tmate.hgkit.ll.Changeset;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;
import com.tmate.hgkit.ll.StatusCollector;

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
	
	// multiple? Bad idea, would need to include extra method into Handler to tell start of next file
	public LogCommand file(Path file) {
		// implicit --follow in this case
		throw HgRepository.notImplemented();
	}

	/**
	 * Similar to {@link #execute(com.tmate.hgkit.ll.Changeset.Inspector)}, collects and return result as a list.
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
			changeset = new Cset(new StatusCollector(repo), new PathPool(repo.getPathHelper()));
			repo.getChangelog().range(startRev, endRev, this);
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
		
		public FileRevision(HgRepository hgRepo, Nodeid rev, Path p) {
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
			return repo.getFileNode(path).content();
		}
	}
}
