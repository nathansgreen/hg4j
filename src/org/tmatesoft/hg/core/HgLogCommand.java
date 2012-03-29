/*
s * Copyright (c) 2011-2012 TMate Software Ltd
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;
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
		// TODO post-1.0 implement
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
	 * 
	 * @param rev1 - local index of start changeset revision
	 * @param rev2 - index of end changeset revision
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
	 * Select specific changeset
	 * 
	 * @param nid changeset revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset revision 
	 */
	public HgLogCommand changeset(Nodeid nid) throws HgBadArgumentException {
		// XXX perhaps, shall support multiple (...) arguments and extend #execute to handle not only range, but also set of revisions.
		try {
			final int csetRevIndex = repo.getChangelog().getRevisionIndex(nid);
			return range(csetRevIndex, csetRevIndex);
		} catch (HgRuntimeException ex) {
			throw new HgBadArgumentException("Can't find revision", ex).setRevision(nid);
		}
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
	 * Similar to {@link #execute(HgChangesetHandler)}, collects and return result as a list.
	 * 
	 * @see #execute(HgChangesetHandler)
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 */
	public List<HgChangeset> execute() throws HgException {
		CollectHandler collector = new CollectHandler();
		try {
			execute(collector);
		} catch (HgCallbackTargetException ex) {
			// see below for CanceledException
			HgInvalidStateException t = new HgInvalidStateException("Internal error");
			t.initCause(ex);
			throw t;
		} catch (CancelledException ex) {
			// can't happen as long as our CollectHandler doesn't throw any exception
			HgInvalidStateException t = new HgInvalidStateException("Internal error");
			t.initCause(ex);
			throw t;
		}
		return collector.getChanges();
	}

	/**
	 * Iterate over range of changesets configured in the command.
	 * 
	 * @param handler callback to process changesets.
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws IllegalArgumentException when inspector argument is null
	 * @throws ConcurrentModificationException if this log command instance is already running
	 */
	public void execute(HgChangesetHandler handler) throws HgCallbackTargetException, HgException, CancelledException {
		if (handler == null) {
			throw new IllegalArgumentException();
		}
		if (csetTransform != null) {
			throw new ConcurrentModificationException();
		}
		final ProgressSupport progressHelper = getProgressSupport(handler);
		try {
			count = 0;
			HgChangelog.ParentWalker pw = getParentHelper(file == null); // leave it uninitialized unless we iterate whole repo
			// ChangesetTransfrom creates a blank PathPool, and #file(String, boolean) above 
			// may utilize it as well. CommandContext? How about StatusCollector there as well?
			csetTransform = new ChangesetTransformer(repo, handler, pw, progressHelper, getCancelSupport(handler, true));
			if (file == null) {
				progressHelper.start(endRev - startRev + 1);
				repo.getChangelog().range(startRev, endRev, this);
				csetTransform.checkFailure();
			} else {
				progressHelper.start(-1/*XXX enum const, or a dedicated method startUnspecified(). How about startAtLeast(int)?*/);
				HgDataFile fileNode = repo.getFileNode(file);
				if (!fileNode.exists()) {
					throw new HgPathNotFoundException(String.format("File %s not found in the repository", file), file);
				}
				fileNode.history(startRev, endRev, this);
				csetTransform.checkFailure();
				if (fileNode.isCopy()) {
					// even if we do not follow history, report file rename
					do {
						if (handler instanceof HgChangesetHandler.WithCopyHistory) {
							HgFileRevision src = new HgFileRevision(repo, fileNode.getCopySourceRevision(), null, fileNode.getCopySourceName());
							HgFileRevision dst = new HgFileRevision(repo, fileNode.getRevision(0), null, fileNode.getPath(), src.getPath());
							((HgChangesetHandler.WithCopyHistory) handler).copy(src, dst);
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
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			csetTransform = null;
			progressHelper.done();
		}
	}
	
	/**
	 * Tree-wise iteration of a file history, with handy access to parent-child relations between changesets. 
	 *  
	 * @param handler callback to process changesets.
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws IllegalArgumentException if command is not satisfied with its arguments 
	 * @throws ConcurrentModificationException if this log command instance is already running
	 */
	public void execute(HgChangesetTreeHandler handler) throws HgCallbackTargetException, HgException, CancelledException {
		if (handler == null) {
			throw new IllegalArgumentException();
		}
		if (csetTransform != null) {
			throw new ConcurrentModificationException();
		}
		if (file == null) {
			throw new IllegalArgumentException("History tree is supported for files only (at least now), please specify file");
		}
		if (followHistory) {
			throw new UnsupportedOperationException("Can't follow file history when building tree (yet?)");
		}
		class TreeBuildInspector implements HgChangelog.ParentInspector, HgChangelog.RevisionInspector {
			HistoryNode[] completeHistory;
			int[] commitRevisions;

			public void next(int revisionNumber, Nodeid revision, int linkedRevision) {
				commitRevisions[revisionNumber] = linkedRevision;
			}

			public void next(int revisionNumber, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2) {
				HistoryNode p1 = null, p2 = null;
				if (parent1 != -1) {
					p1 = completeHistory[parent1];
				}
				if (parent2!= -1) {
					p2 = completeHistory[parent2];
				}
				completeHistory[revisionNumber] = new HistoryNode(commitRevisions[revisionNumber], revision, p1, p2);
			}
			
			HistoryNode[] go(HgDataFile fileNode) throws HgInvalidControlFileException {
				completeHistory = new HistoryNode[fileNode.getRevisionCount()];
				commitRevisions = new int[completeHistory.length];
				fileNode.indexWalk(0, TIP, this);
				return completeHistory;
			}
		};
		final ProgressSupport progressHelper = getProgressSupport(handler);
		progressHelper.start(4);
		final CancelSupport cancelHelper = getCancelSupport(handler, true);
		cancelHelper.checkCancelled();
		HgDataFile fileNode = repo.getFileNode(file);
		// build tree of nodes according to parents in file's revlog
		final TreeBuildInspector treeBuildInspector = new TreeBuildInspector();
		final HistoryNode[] completeHistory = treeBuildInspector.go(fileNode);
		progressHelper.worked(1);
		cancelHelper.checkCancelled();
		ElementImpl ei = new ElementImpl(treeBuildInspector.commitRevisions.length);
		final ProgressSupport ph2;
		if (treeBuildInspector.commitRevisions.length < 100 /*XXX is it really worth it? */) {
			ei.initTransform();
			repo.getChangelog().range(ei, treeBuildInspector.commitRevisions);
			progressHelper.worked(1);
			ph2 = new ProgressSupport.Sub(progressHelper, 2);
		} else {
			ph2 = new ProgressSupport.Sub(progressHelper, 3);
		}
		ph2.start(completeHistory.length);
		// XXX shall sort completeHistory according to changeset numbers?
		for (int i = 0; i < completeHistory.length; i++ ) {
			final HistoryNode n = completeHistory[i];
			handler.treeElement(ei.init(n));
			ph2.worked(1);
			cancelHelper.checkCancelled();
		}
		progressHelper.done();
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
			// TODO post-1.0 implement date support for log
		}
		count++;
		csetTransform.next(revisionNumber, nodeid, cset);
	}
	
	private HgChangelog.ParentWalker getParentHelper(boolean create) throws HgInvalidControlFileException {
		if (parentHelper == null && create) {
			parentHelper = repo.getChangelog().new ParentWalker();
			parentHelper.init();
		}
		return parentHelper;
	}


	public static class CollectHandler implements HgChangesetHandler {
		private final List<HgChangeset> result = new LinkedList<HgChangeset>();

		public List<HgChangeset> getChanges() {
			return Collections.unmodifiableList(result);
		}

		public void cset(HgChangeset changeset) {
			result.add(changeset.clone());
		}
	}

	private static class HistoryNode {
		final int changeset;
		final Nodeid fileRevision;
		final HistoryNode parent1, parent2;
		List<HistoryNode> children;

		HistoryNode(int cs, Nodeid revision, HistoryNode p1, HistoryNode p2) {
			changeset = cs;
			fileRevision = revision;
			parent1 = p1;
			parent2 = p2;
			if (p1 != null) {
				p1.addChild(this);
			}
			if (p2 != null) {
				p2.addChild(this);
			}
		}
		
		void addChild(HistoryNode child) {
			if (children == null) {
				children = new ArrayList<HistoryNode>(2);
			}
			children.add(child);
		}
	}

	private class ElementImpl implements HgChangesetTreeHandler.TreeElement, HgChangelog.Inspector {
		private HistoryNode historyNode;
		private Pair<HgChangeset, HgChangeset> parents;
		private List<HgChangeset> children;
		private IntMap<HgChangeset> cachedChangesets;
		private ChangesetTransformer.Transformation transform;
		private Nodeid changesetRevision;
		private Pair<Nodeid,Nodeid> parentRevisions;
		private List<Nodeid> childRevisions;
		
		public ElementImpl(int total) {
			cachedChangesets = new IntMap<HgChangeset>(total);
		}

		ElementImpl init(HistoryNode n) {
			historyNode = n;
			parents = null;
			children = null;
			changesetRevision = null;
			parentRevisions = null;
			childRevisions = null;
			return this;
		}

		public Nodeid fileRevision() {
			return historyNode.fileRevision;
		}

		public HgChangeset changeset() {
			return get(historyNode.changeset)[0];
		}

		public Pair<HgChangeset, HgChangeset> parents() {
			if (parents != null) {
				return parents;
			}
			HistoryNode p;
			final int p1, p2;
			if ((p = historyNode.parent1) != null) {
				p1 = p.changeset;
			} else {
				p1 = -1;
			}
			if ((p = historyNode.parent2) != null) {
				p2 = p.changeset;
			} else {
				p2 = -1;
			}
			HgChangeset[] r = get(p1, p2);
			return parents = new Pair<HgChangeset, HgChangeset>(r[0], r[1]);
		}

		public Collection<HgChangeset> children() {
			if (children != null) {
				return children;
			}
			if (historyNode.children == null) {
				children = Collections.emptyList();
			} else {
				int[] childrentChangesetNumbers = new int[historyNode.children.size()];
				int j = 0;
				for (HistoryNode hn : historyNode.children) {
					childrentChangesetNumbers[j++] = hn.changeset;
				}
				children = Arrays.asList(get(childrentChangesetNumbers));
			}
			return children;
		}
		
		void populate(HgChangeset cs) {
			cachedChangesets.put(cs.getRevisionIndex(), cs);
		}
		
		private HgChangeset[] get(int... changelogRevisionIndex) {
			HgChangeset[] rv = new HgChangeset[changelogRevisionIndex.length];
			IntVector misses = new IntVector(changelogRevisionIndex.length, -1);
			for (int i = 0; i < changelogRevisionIndex.length; i++) {
				if (changelogRevisionIndex[i] == -1) {
					rv[i] = null;
					continue;
				}
				HgChangeset cached = cachedChangesets.get(changelogRevisionIndex[i]);
				if (cached != null) {
					rv[i] = cached;
				} else {
					misses.add(changelogRevisionIndex[i]);
				}
			}
			if (misses.size() > 0) {
				final int[] changesets2read = misses.toArray();
				initTransform();
				repo.getChangelog().range(this, changesets2read);
				for (int changeset2read : changesets2read) {
					HgChangeset cs = cachedChangesets.get(changeset2read);
					if (cs == null) {
						HgInvalidStateException t = new HgInvalidStateException(String.format("Can't get changeset for revision %d", changeset2read));
						throw t.setRevisionIndex(changeset2read);
					}
					// HgChangelog.range may reorder changesets according to their order in the changelog
					// thus need to find original index
					boolean sanity = false;
					for (int i = 0; i < changelogRevisionIndex.length; i++) {
						if (changelogRevisionIndex[i] == cs.getRevisionIndex()) {
							rv[i] = cs;
							sanity = true;
							break;
						}
					}
					if (!sanity) {
						HgInternals.getContext(repo).getLog().error(getClass(), "Index of revision %d:%s doesn't match any of requested", cs.getRevisionIndex(), cs.getNodeid().shortNotation());
					}
					assert sanity;
				}
			}
			return rv;
		}

		// init only when needed
		void initTransform() throws HgRuntimeException {
			if (transform == null) {
				transform = new ChangesetTransformer.Transformation(new HgStatusCollector(repo)/*XXX try to reuse from context?*/, getParentHelper(false));
			}
		}
		
		public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
			HgChangeset cs = transform.handle(revisionNumber, nodeid, cset);
			populate(cs.clone());
		}

		public Nodeid changesetRevision() {
			if (changesetRevision == null) {
				changesetRevision = getRevision(historyNode.changeset);
			}
			return changesetRevision;
		}

		public Pair<Nodeid, Nodeid> parentRevisions() {
			if (parentRevisions == null) {
				HistoryNode p;
				final Nodeid p1, p2;
				if ((p = historyNode.parent1) != null) {
					p1 = getRevision(p.changeset);
				} else {
					p1 = Nodeid.NULL;;
				}
				if ((p = historyNode.parent2) != null) {
					p2 = getRevision(p.changeset);
				} else {
					p2 = Nodeid.NULL;
				}
				parentRevisions = new Pair<Nodeid, Nodeid>(p1, p2);
			}
			return parentRevisions;
		}

		public Collection<Nodeid> childRevisions() {
			if (childRevisions != null) {
				return childRevisions;
			}
			if (historyNode.children == null) {
				childRevisions = Collections.emptyList();
			} else {
				ArrayList<Nodeid> rv = new ArrayList<Nodeid>(historyNode.children.size());
				for (HistoryNode hn : historyNode.children) {
					rv.add(getRevision(hn.changeset));
				}
				childRevisions = Collections.unmodifiableList(rv);
			}
			return childRevisions;
		}
		
		// reading nodeid involves reading index only, guess, can afford not to optimize multiple reads
		private Nodeid getRevision(int changelogRevisionNumber) {
			// TODO post-1.0 pipe through pool
			HgChangeset cs = cachedChangesets.get(changelogRevisionNumber);
			if (cs != null) {
				return cs.getNodeid();
			} else {
				return repo.getChangelog().getRevision(changelogRevisionNumber);
			}
		}
	}
}
