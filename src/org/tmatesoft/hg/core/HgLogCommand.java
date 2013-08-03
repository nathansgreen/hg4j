/*
s * Copyright (c) 2011-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.tmatesoft.hg.internal.AdapterPlug;
import org.tmatesoft.hg.internal.BatchRangeHelper;
import org.tmatesoft.hg.internal.CsetParamKeeper;
import org.tmatesoft.hg.internal.FileRenameHistory;
import org.tmatesoft.hg.internal.FileRenameHistory.Chunk;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.Lifecycle;
import org.tmatesoft.hg.internal.LifecycleProxy;
import org.tmatesoft.hg.internal.ReverseIterator;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgParentChildMap;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.util.Adaptable;
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
public class HgLogCommand extends HgAbstractCommand<HgLogCommand> {

	private final HgRepository repo;
	private Set<String> users;
	private Set<String> branches;
	private int limit = 0, count = 0;
	private int startRev = 0, endRev = TIP;
	private Calendar date;
	private Path file;
	/*
	 * Whether to iterate file origins, if any.
	 * Makes sense only when file != null
	 */
	private boolean followRenames;
	/*
	 * Whether to track history of the selected file version (based on file revision
	 * in working dir parent), follow ancestors only.
	 * Note, 'hg log --follow' combines both #followHistory and #followAncestry
	 */
	private boolean followAncestry;

	private HgIterateDirection iterateDirection = HgIterateDirection.OldToNew;

	private ChangesetTransformer csetTransform;
	private HgParentChildMap<HgChangelog> parentHelper;
	
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
		throw Internals.notImplemented();
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
		// TODO [2.0 API break] shall throw HgBadArgumentException, like other commands do
		return this;
	}
	
	/**
	 * Limit history to specified range.
	 * 
	 * @see #range(int, int)
	 * @param cset1 range start revision
	 * @param cset2 range end revision
	 * @return <code>this</code> instance for convenience
	 * @throws HgBadArgumentException if revisions are not valid changeset identifiers
	 */
	public HgLogCommand range(Nodeid cset1, Nodeid cset2) throws HgBadArgumentException {
		CsetParamKeeper pk = new CsetParamKeeper(repo);
		int r1 = pk.set(cset1).get();
		int r2 = pk.set(cset2).get();
		return range(r1, r2);
	}
	
	/**
	 * Select specific changeset by index
	 * @see #changeset(Nodeid)
	 * @param revisionIndex index of changelog revision
	 * @return <code>this</code> for convenience
	 * @throws HgBadArgumentException if failed to find supplied changeset revision
	 */
	public HgLogCommand changeset(int revisionIndex) throws HgBadArgumentException {
		int ri = new CsetParamKeeper(repo).set(revisionIndex).get();
		return range(ri, ri);
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
		final int csetRevIndex = new CsetParamKeeper(repo).set(nid).get();
		return range(csetRevIndex, csetRevIndex);
	}
	
	/**
	 * Visit history of a given file only. Note, unlike native <code>hg log</code> command argument <code>--follow</code>, this method doesn't
	 * follow file ancestry, but reports complete file history (with <code>followCopyRenames == true</code>, for each 
	 * name of the file known in sequence). To achieve output similar to that of <code>hg log --follow filePath</code>, use
	 * {@link #file(Path, boolean, boolean) file(filePath, true, true)} alternative.
	 * 
	 * @param filePath path relative to repository root. Pass <code>null</code> to reset.
	 * @param followCopyRename true to report changesets of the original file(-s), if copy/rename ever occured to the file.
	 * @return <code>this</code> for convenience
	 */
	public HgLogCommand file(Path filePath, boolean followCopyRename) {
		return file(filePath, followCopyRename, false);
	}
	
	/**
	 * Full control over file history iteration.
	 * 
	 * @param filePath path relative to repository root. Pass <code>null</code> to reset.
	 * @param followCopyRename true to report changesets of the original file(-s), if copy/rename ever occured to the file.
	 * @param followFileAncestry true to follow file history starting from revision at working copy parent. Note, only revisions 
	 * accessible (i.e. on direct parent line) from the selected one will be reported. This is how <code>hg log --follow filePath</code>
	 * behaves, with the difference that this method allows separate control whether to follow renames or not.
	 * 
	 * @return <code>this</code> for convenience
	 */
	public HgLogCommand file(Path filePath, boolean followCopyRename, boolean followFileAncestry) {
		file = filePath;
		followRenames = followCopyRename;
		followAncestry = followFileAncestry;
		return this;
	}
	
	/**
	 * Handy analog to {@link #file(Path, boolean)} when clients' paths come from filesystem and need conversion to repository's 
	 * @return <code>this</code> for convenience
	 */
	public HgLogCommand file(String file, boolean followCopyRename) {
		Path.Source ps = repo.getSessionContext().getPathFactory();
		return file(ps.path(repo.getToRepoPathHelper().rewrite(file)), followCopyRename);
	}

	/**
	 * Handy analog to {@link #file(Path, boolean, boolean)} when clients' paths come from filesystem and need conversion to repository's 
	 * @return <code>this</code> for convenience
	 */
	public HgLogCommand file(String file, boolean followCopyRename, boolean followFileAncestry) {
		Path.Source ps = repo.getSessionContext().getPathFactory();
		return file(ps.path(repo.getToRepoPathHelper().rewrite(file)), followCopyRename, followFileAncestry);
	}
	
	/**
	 * Specifies order for changesets reported through #execute(...) methods.
	 * By default, command reports changeset in their natural repository order, older first, 
	 * newer last (i.e. {@link HgIterateDirection#OldToNew}
	 * 
	 * @param order {@link HgIterateDirection#NewToOld} to get newer revisions first
	 * @return <code>this</code> for convenience
	 */
	public HgLogCommand order(HgIterateDirection order) {
		iterateDirection = order;
		return this;
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
			if (repo.getChangelog().getRevisionCount() == 0) {
				return;
			}
			final int firstCset = startRev;
			final int lastCset = endRev == TIP ? repo.getChangelog().getLastRevision() : endRev;
			// XXX pretty much like HgInternals.checkRevlogRange
			if (lastCset < 0 || lastCset > repo.getChangelog().getLastRevision()) {
				throw new HgBadArgumentException(String.format("Bad value %d for end revision", lastCset), null);
			}
			if (firstCset < 0 || firstCset > lastCset) {
				throw new HgBadArgumentException(String.format("Bad value %d for start revision for range [%1$d..%d]", firstCset, lastCset), null);
			}
			final int BATCH_SIZE = 100;
			count = 0;
			HgParentChildMap<HgChangelog> pw = getParentHelper(file == null); // leave it uninitialized unless we iterate whole repo
			// ChangesetTransfrom creates a blank PathPool, and #file(String, boolean) above 
			// may utilize it as well. CommandContext? How about StatusCollector there as well?
			csetTransform = new ChangesetTransformer(repo, handler, pw, progressHelper, getCancelSupport(handler, true));
			// FilteringInspector is responsible to check command arguments: users, branches, limit, etc.
			// prior to passing cset to next Inspector, which is either (a) collector to reverse cset order, then invokes 
			// transformer from (b), below, with alternative cset order or (b) transformer to hi-level csets. 
			FilteringInspector filterInsp = new FilteringInspector();
			filterInsp.changesets(firstCset, lastCset);
			if (file == null) {
				progressHelper.start(lastCset - firstCset + 1);
				if (iterateDirection == HgIterateDirection.OldToNew) {
					filterInsp.delegateTo(csetTransform);
					repo.getChangelog().range(firstCset, lastCset, filterInsp);
					csetTransform.checkFailure();
				} else {
					assert iterateDirection == HgIterateDirection.NewToOld;
					BatchRangeHelper brh = new BatchRangeHelper(firstCset, lastCset, BATCH_SIZE, true);
					BatchChangesetInspector batchInspector = new BatchChangesetInspector(Math.min(lastCset-firstCset+1, BATCH_SIZE));
					filterInsp.delegateTo(batchInspector);
					// XXX this batching code is bit verbose, refactor
					while (brh.hasNext()) {
						brh.next();
						repo.getChangelog().range(brh.start(), brh.end(), filterInsp);
						for (BatchChangesetInspector.BatchRecord br : batchInspector.iterate(true)) {
							csetTransform.next(br.csetIndex, br.csetRevision, br.cset);
							csetTransform.checkFailure();
						}
						batchInspector.reset();
					}
				}
			} else {
				filterInsp.delegateTo(csetTransform);
				final HgFileRenameHandlerMixin withCopyHandler = Adaptable.Factory.getAdapter(handler, HgFileRenameHandlerMixin.class, null);
				FileRenameQueueBuilder frqBuilder = new FileRenameQueueBuilder();
				List<QueueElement> fileRenames = frqBuilder.buildFileRenamesQueue(firstCset, lastCset);
				progressHelper.start(fileRenames.size());
				for (int nameIndex = 0, fileRenamesSize = fileRenames.size(); nameIndex < fileRenamesSize; nameIndex++) {
					QueueElement curRename = fileRenames.get(nameIndex);
					HgDataFile fileNode = curRename.file();
					if (followAncestry) {
						TreeBuildInspector treeBuilder = new TreeBuildInspector(followAncestry);
						@SuppressWarnings("unused")
						List<HistoryNode> fileAncestry = treeBuilder.go(curRename);
						int[] commitRevisions = narrowChangesetRange(treeBuilder.getCommitRevisions(), firstCset, lastCset);
						if (iterateDirection == HgIterateDirection.OldToNew) {
							repo.getChangelog().range(filterInsp, commitRevisions);
							csetTransform.checkFailure();
						} else {
							assert iterateDirection == HgIterateDirection.NewToOld;
							// visit one by one in the opposite direction
							for (int i = commitRevisions.length-1; i >= 0; i--) {
								int csetWithFileChange = commitRevisions[i];
								repo.getChangelog().range(csetWithFileChange, csetWithFileChange, filterInsp);
							}
						}
					} else {
						// report complete file history (XXX may narrow range with [startRev, endRev], but need to go from file rev to link rev)
						int fileStartRev = curRename.fileFrom();
						int fileEndRev = curRename.file().getLastRevision(); //curRename.fileTo();
						if (iterateDirection == HgIterateDirection.OldToNew) {
							fileNode.history(fileStartRev, fileEndRev, filterInsp);
							csetTransform.checkFailure();
						} else {
							assert iterateDirection == HgIterateDirection.NewToOld;
							BatchRangeHelper brh = new BatchRangeHelper(fileStartRev, fileEndRev, BATCH_SIZE, true);
							BatchChangesetInspector batchInspector = new BatchChangesetInspector(Math.min(fileEndRev-fileStartRev+1, BATCH_SIZE));
							filterInsp.delegateTo(batchInspector);
							while (brh.hasNext()) {
								brh.next();
								fileNode.history(brh.start(), brh.end(), filterInsp);
								for (BatchChangesetInspector.BatchRecord br : batchInspector.iterate(true /*iterateDirection == IterateDirection.FromNewToOld*/)) {
									csetTransform.next(br.csetIndex, br.csetRevision, br.cset);
									csetTransform.checkFailure();
								}
								batchInspector.reset();
							}
						}
					}
					if (withCopyHandler != null && nameIndex + 1 < fileRenamesSize) {
						QueueElement nextRename = fileRenames.get(nameIndex+1);
						HgFileRevision src, dst;
						// A -> B
						if (iterateDirection == HgIterateDirection.OldToNew) {
							// curRename: A, nextRename: B
							src = curRename.last();
							dst = nextRename.first(src);
						} else {
							assert iterateDirection == HgIterateDirection.NewToOld;
							// curRename: B, nextRename: A
							src = nextRename.last();
							dst = curRename.first(src);
						}
						withCopyHandler.copy(src, dst);
					}
					progressHelper.worked(1);
				} // for renames
				frqBuilder.reportRenameIfNotInQueue(fileRenames, withCopyHandler);
			} // file != null
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		} finally {
			csetTransform = null;
			progressHelper.done();
		}
	}
	
	private static class BatchChangesetInspector extends AdapterPlug implements HgChangelog.Inspector {
		private static class BatchRecord {
			public final int csetIndex;
			public final Nodeid csetRevision;
			public final RawChangeset cset;
			
			public BatchRecord(int index, Nodeid nodeid, RawChangeset changeset) {
				csetIndex = index;
				csetRevision = nodeid;
				cset = changeset;
			}
		}
		private final ArrayList<BatchRecord> batch;

		public BatchChangesetInspector(int batchSizeHint) {
			batch = new ArrayList<BatchRecord>(batchSizeHint);
		}

		public BatchChangesetInspector reset() {
			batch.clear();
			return this;
		}
		
		public void next(int revisionIndex, Nodeid nodeid, RawChangeset cset) {
			batch.add(new BatchRecord(revisionIndex, nodeid, cset.clone()));
		}
		
		public Iterable<BatchRecord> iterate(final boolean reverse) {
			return reverse ? ReverseIterator.reversed(batch) : batch;
		}
		
		// alternative would be dispatch(HgChangelog.Inspector) and dispatchReverse()
		// methods, but progress and cancellation might get messy then
	}
	
//	public static void main(String[] args) {
//		int[] r = new int[] {17, 19, 21, 23, 25, 29};
//		System.out.println(Arrays.toString(narrowChangesetRange(r, 0, 45)));
//		System.out.println(Arrays.toString(narrowChangesetRange(r, 0, 25)));
//		System.out.println(Arrays.toString(narrowChangesetRange(r, 5, 26)));
//		System.out.println(Arrays.toString(narrowChangesetRange(r, 20, 26)));
//		System.out.println(Arrays.toString(narrowChangesetRange(r, 26, 28)));
//	}

	private static int[] narrowChangesetRange(int[] csetRange, int startCset, int endCset) {
		int lastInRange = csetRange[csetRange.length-1];
		assert csetRange.length < 2 || csetRange[0] < lastInRange; // sorted
		assert startCset >= 0 && startCset <= endCset;
		if (csetRange[0] >= startCset && lastInRange <= endCset) {
			// completely fits in
			return csetRange;
		}
		if (csetRange[0] > endCset || lastInRange < startCset) {
			return new int[0]; // trivial
		}
		int i = 0;
		while (i < csetRange.length && csetRange[i] < startCset) {
			i++;
		}
		int j = csetRange.length - 1;
		while (j > i && csetRange[j] > endCset) {
			j--;
		}
		if (i == j) {
			// no values in csetRange fit into [startCset, endCset]
			return new int[0];
		}
		int[] rv = new int[j-i+1];
		System.arraycopy(csetRange, i, rv, 0, rv.length);
		return rv;
	}
	
	/**
	 * Tree-wise iteration of a file history, with handy access to parent-child relations between changesets.
	 * When file history is being followed, handler may additionally implement {@link HgFileRenameHandlerMixin} 
	 * to get notified about switching between history chunks that belong to different names.   
	 *  
	 * @param handler callback to process changesets.
	 * @see HgFileRenameHandlerMixin
 	 * @throws HgCallbackTargetException propagated exception from the handler
	 * @throws HgException subclass thereof to indicate specific issue with the command arguments or repository state
	 * @throws CancelledException if execution of the command was cancelled
	 * @throws IllegalArgumentException if command is not satisfied with its arguments 
	 * @throws ConcurrentModificationException if this log command instance is already running
	 */
	public void execute(final HgChangesetTreeHandler handler) throws HgCallbackTargetException, HgException, CancelledException {
		if (handler == null) {
			throw new IllegalArgumentException();
		}
		if (csetTransform != null) {
			throw new ConcurrentModificationException();
		}
		if (file == null) {
			throw new IllegalArgumentException("History tree is supported for files only (at least now), please specify file");
		}
		final int firstCset = startRev;
		final int lastCset = endRev == TIP ? repo.getChangelog().getLastRevision() : endRev;
		// XXX pretty much like HgInternals.checkRevlogRange
		if (lastCset < 0 || lastCset > repo.getChangelog().getLastRevision()) {
			throw new HgBadArgumentException(String.format("Bad value %d for end revision", lastCset), null);
		}
		if (firstCset < 0 || startRev > lastCset) {
			throw new HgBadArgumentException(String.format("Bad value %d for start revision for range [%1$d..%d]", startRev, lastCset), null);
		}
		final ProgressSupport progressHelper = getProgressSupport(handler);
		final CancelSupport cancelHelper = getCancelSupport(handler, true);
		final HgFileRenameHandlerMixin renameHandler = Adaptable.Factory.getAdapter(handler, HgFileRenameHandlerMixin.class, null);

		try {

			// XXX rename. dispatcher is not a proper name (most of the job done - managing history chunk interconnection)
			final HandlerDispatcher dispatcher = new HandlerDispatcher() {
	
				@Override
				protected void once(HistoryNode n) throws HgCallbackTargetException, CancelledException, HgRuntimeException {
					handler.treeElement(ei.init(n, currentFileNode));
					cancelHelper.checkCancelled();
				}
			};
	
			// renamed files in the queue are placed with respect to #iterateDirection
			// i.e. if we iterate from new to old, recent filenames come first
			FileRenameQueueBuilder frqBuilder = new FileRenameQueueBuilder();
			List<QueueElement> fileRenamesQueue = frqBuilder.buildFileRenamesQueue(firstCset, lastCset);
			// XXX perhaps, makes sense to look at selected file's revision when followAncestry is true
			// to ensure file we attempt to trace is in the WC's parent. Native hg aborts if not.
			progressHelper.start(4 * fileRenamesQueue.size());
			for (int namesIndex = 0, renamesQueueSize = fileRenamesQueue.size(); namesIndex < renamesQueueSize; namesIndex++) {
	 
				final QueueElement renameInfo = fileRenamesQueue.get(namesIndex);
				dispatcher.prepare(progressHelper, renameInfo);
				cancelHelper.checkCancelled();
				if (namesIndex > 0) {
					dispatcher.connectWithLastJunctionPoint(renameInfo, fileRenamesQueue.get(namesIndex - 1));
				}
				if (namesIndex + 1 < renamesQueueSize) {
					// there's at least one more name we are going to look at
					dispatcher.updateJunctionPoint(renameInfo, fileRenamesQueue.get(namesIndex+1), renameHandler != null);
				} else {
					dispatcher.clearJunctionPoint();
				}
				dispatcher.dispatchAllChanges();
				if (renameHandler != null && namesIndex + 1 < renamesQueueSize) {
					dispatcher.reportRenames(renameHandler);
				}
			} // for fileRenamesQueue;
			frqBuilder.reportRenameIfNotInQueue(fileRenamesQueue, renameHandler);
		} catch (HgRuntimeException ex) {
			throw new HgLibraryFailureException(ex);
		}
		progressHelper.done();
	}
	
	private static class QueueElement {
		private final HgDataFile df;
		private final Nodeid lastRev;
		private final int firstRevIndex, lastRevIndex;

		QueueElement(HgDataFile file, Nodeid fileLastRev) {
			df = file;
			lastRev = fileLastRev;
			firstRevIndex = 0;
			lastRevIndex = lastRev == null ? df.getLastRevision() : df.getRevisionIndex(lastRev);
		}
		QueueElement(HgDataFile file, int firstFileRev, int lastFileRev) {
			df = file;
			firstRevIndex = firstFileRev;
			lastRevIndex = lastFileRev;
			lastRev = null;
		}
		HgDataFile file() {
			return df;
		}
		int fileFrom() {
			return firstRevIndex;
		}
		int fileTo() {
			return lastRevIndex;
		}
		// never null
		Nodeid lastFileRev() {
			return lastRev == null ? df.getRevision(fileTo()) : lastRev;
		}
		HgFileRevision last() {
			return new HgFileRevision(df, lastFileRev(), null);
		}
		HgFileRevision first(HgFileRevision from) {
			return new HgFileRevision(df, df.getRevision(0), from.getPath());
		}
	}
	
	/**
	 * Utility to build sequence of file renames
	 */
	private class FileRenameQueueBuilder {
		
		/**
		 * Follows file renames and build a list of all corresponding file nodes and revisions they were 
		 * copied/renamed/branched at (IOW, their latest revision to look at).
		 *  
		 * @param followRename when <code>false</code>, the list contains one element only, 
		 * file node with the name of the file as it was specified by the user.
		 * 
		 * @param followAncestry the most recent file revision reported depends on this parameter, 
		 * and it is file revision from working copy parent in there when it's true. 
		 * <code>null</code> as Pair's second indicates file's TIP revision shall be used.
		 * 
		 * TODO may use HgFileRevision (after some refactoring to accept HgDataFile and Nodeid) instead of Pair
		 * and possibly reuse this functionality
		 * 
		 * @return list of file renames, ordered with respect to {@link #iterateDirection}
		 * @throws HgRuntimeException 
		 */
		public List<QueueElement> buildFileRenamesQueue(int csetStart, int csetEnd) throws HgPathNotFoundException, HgRuntimeException {
			LinkedList<QueueElement> rv = new LinkedList<QueueElement>();
			Nodeid startRev = null;
			HgDataFile fileNode = repo.getFileNode(file);
			if (!fileNode.exists()) {
				throw new HgPathNotFoundException(String.format("File %s not found in the repository", file), file);
			}
			if (followAncestry) {
				// TODO subject to dedicated method either in HgRepository (getWorkingCopyParentRevisionIndex)
				// or in the HgDataFile (getWorkingCopyOriginRevision)
				Nodeid wdParentChangeset = repo.getWorkingCopyParents().first();
				if (!wdParentChangeset.isNull()) {
					int wdParentRevIndex = repo.getChangelog().getRevisionIndex(wdParentChangeset);
					startRev = repo.getManifest().getFileRevision(wdParentRevIndex, fileNode.getPath());
				}
				// else fall-through, assume null (eventually, lastRevision()) is ok here
			}
			QueueElement p = new QueueElement(fileNode, startRev);
			if (!followRenames) {
				rv.add(p);
				return rv;
			}
			FileRenameHistory frh = new FileRenameHistory(csetStart, csetEnd);
			frh.build(fileNode, p.fileTo());
			for (Chunk c : frh.iterate(iterateDirection)) {
				rv.add(new QueueElement(c.file(), c.firstFileRev(), c.lastFileRev()));
			}
			return rv;
		}
		
		/**
		 * Shall report renames based solely on HgFileRenameHandlerMixin presence,
		 * even if queue didn't get rename information due to followRenames == false
		 *  
		 * @param queue value from {@link #buildFileRenamesQueue()}
		 * @param renameHandler may be <code>null</code>
		 */
		public void reportRenameIfNotInQueue(List<QueueElement> queue, HgFileRenameHandlerMixin renameHandler) throws HgCallbackTargetException, HgRuntimeException {
			if (renameHandler != null && !followRenames) {
				// If followRenames is true, all the historical names were in the queue and are processed already.
				// Hence, shall process origin explicitly only when renameHandler is present but followRenames is not requested.
				assert queue.size() == 1; // see the way queue is constructed above
				QueueElement curRename = queue.get(0);
				if (curRename.file().isCopy(curRename.fileFrom())) {
					final HgFileRevision src = curRename.file().getCopySource(curRename.fileFrom());
					HgFileRevision dst = curRename.first(src);
					renameHandler.copy(src, dst);
				}
			}
		}
	}
	
	/**
	 * Builds list of {@link HistoryNode HistoryNodes} to visit for a given chunk of file rename history
	 */
	private static class TreeBuildInspector implements HgChangelog.ParentInspector, HgChangelog.RevisionInspector {
		private final boolean followAncestry;

		private HistoryNode[] completeHistory;
		private int[] commitRevisions;
		private List<HistoryNode> resultHistory;
		
		TreeBuildInspector(boolean _followAncestry) {
			followAncestry = _followAncestry;
		}

		public void next(int revisionNumber, Nodeid revision, int linkedRevision) {
			commitRevisions[revisionNumber] = linkedRevision;
		}

		public void next(int revisionNumber, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2) {
			HistoryNode p1 = null, p2 = null;
			// IMPORTANT: method #one(), below, doesn't expect this code expects reasonable values at parent indexes
			if (parent1 != -1) {
				p1 = completeHistory[parent1];
			}
			if (parent2!= -1) {
				p2 = completeHistory[parent2];
			}
			completeHistory[revisionNumber] = new HistoryNode(commitRevisions[revisionNumber], revision, p1, p2);
		}
		
		HistoryNode one(HgDataFile fileNode, Nodeid fileRevision) throws HgRuntimeException {
			int fileRevIndexToVisit = fileNode.getRevisionIndex(fileRevision);
			return one(fileNode, fileRevIndexToVisit);
		}

		HistoryNode one(HgDataFile fileNode, int fileRevIndexToVisit) throws HgRuntimeException {
			resultHistory = null;
			if (fileRevIndexToVisit == HgRepository.TIP) {
				fileRevIndexToVisit = fileNode.getLastRevision();
			}
			// still, allocate whole array, for #next to be able to get null parent values
			completeHistory = new HistoryNode[fileRevIndexToVisit+1];
			commitRevisions = new int[completeHistory.length];
			fileNode.indexWalk(fileRevIndexToVisit, fileRevIndexToVisit, this);
			// it's only single revision, no need to care about followAncestry
			// but won't hurt to keep resultHistory != null and commitRevisions initialized just in case
			HistoryNode rv = completeHistory[fileRevIndexToVisit];
			commitRevisions = new int[] { commitRevisions[fileRevIndexToVisit] };
			completeHistory = null; // no need to keep almost empty array in memory
			resultHistory = Collections.singletonList(rv);
			return rv;
		}
		
		/**
		 * FIXME pretty much the same as FileRevisionHistoryChunk
		 * 
		 * Builds history of file changes (in natural order, from oldest to newest) up to (and including) file revision specified.
		 * If {@link TreeBuildInspector} follows ancestry, only elements that are on the line of ancestry of the revision at 
		 * lastRevisionIndex would be included.
		 * 
		 * @return list of history elements, from oldest to newest. In case {@link #followAncestry} is <code>true</code>, the list
		 * is modifiable (to further augment with last/first elements of renamed file histories)
		 */
		List<HistoryNode> go(QueueElement qe) throws HgRuntimeException {
			resultHistory = null;
			HgDataFile fileNode = qe.file();
			// TODO int fileLastRevIndexToVisit = qe.fileTo
			int fileLastRevIndexToVisit = followAncestry ? fileNode.getRevisionIndex(qe.lastFileRev()) : fileNode.getLastRevision();
			completeHistory = new HistoryNode[fileLastRevIndexToVisit+1];
			commitRevisions = new int[completeHistory.length];
			fileNode.indexWalk(qe.fileFrom(), fileLastRevIndexToVisit, this);
			if (!followAncestry) {
				resultHistory = new ArrayList<HistoryNode>(fileLastRevIndexToVisit - qe.fileFrom() + 1);
				// items in completeHistory with index < qe.fileFrom are empty
				for (int i = qe.fileFrom(); i <= fileLastRevIndexToVisit; i++) {
					resultHistory.add(completeHistory[i]);
				}
				completeHistory = null;
				commitRevisions = null;
				return resultHistory;
			}
			/*
			 * Changesets, newest at the top:
			 * o              <-- cset from working dir parent (as in dirstate), file not changed (file revision recorded points to that from A)  
			 * |   x          <-- revision with file changed (B')
			 * x  /           <-- revision with file changed (A)
			 * | x            <-- revision with file changed (B)
			 * |/
			 * o              <-- another changeset, where file wasn't changed
			 * |
			 * x              <-- revision with file changed (C)
			 * 
			 * File history: B', A, B, C
			 * 
			 * When "follow", SHALL NOT report B and B', but A and C
			 */
			// strippedHistory: only those HistoryNodes from completeHistory that are on the same
			// line of descendant, in order from older to newer
			LinkedList<HistoryNode> strippedHistoryList = new LinkedList<HistoryNode>();
			LinkedList<HistoryNode> queue = new LinkedList<HistoryNode>();
			// look for ancestors of the selected history node
			queue.add(completeHistory[fileLastRevIndexToVisit]);
			do {
				HistoryNode withFileChange = queue.removeFirst();
				if (strippedHistoryList.contains(withFileChange)) {
					// fork  point for the change that was later merged (and we traced
					// both lines of development by now.
					continue;
				}
				if (withFileChange.children != null) {
					withFileChange.children.retainAll(strippedHistoryList);
				}
				strippedHistoryList.addFirst(withFileChange);
				if (withFileChange.parent1 != null) {
					queue.addLast(withFileChange.parent1);
				}
				if (withFileChange.parent2 != null) {
					queue.addLast(withFileChange.parent2);
				}
			} while (!queue.isEmpty());
			Collections.sort(strippedHistoryList, new Comparator<HistoryNode>() {

				public int compare(HistoryNode o1, HistoryNode o2) {
					return o1.changeset - o2.changeset;
				}
			});
			completeHistory = null;
			commitRevisions = null;
			return resultHistory = strippedHistoryList;
		}
		
		/**
		 * handy access to all HistoryNode[i].changeset values
		 */
		int[] getCommitRevisions() {
			if (commitRevisions == null) {
				commitRevisions = new int[resultHistory.size()];
				int i = 0;
				for (HistoryNode n : resultHistory) {
					commitRevisions[i++] = n.changeset;
				}
			}
			return commitRevisions;
		}
	};

	/**
	 * Sends {@link ElementImpl} for each {@link HistoryNode}, and keeps track of junction points - revisions with renames
	 */
	private abstract class HandlerDispatcher {
		private final int CACHE_CSET_IN_ADVANCE_THRESHOLD = 100; /* XXX is it really worth it? */
		// builds tree of nodes according to parents in file's revlog
		private final TreeBuildInspector treeBuildInspector = new TreeBuildInspector(followAncestry);
		private List<HistoryNode> changeHistory;
		protected ElementImpl ei = null;
		private ProgressSupport progress;
		protected HgDataFile currentFileNode;
		// node where current file history chunk intersects with same file under other name history
		// either mock of B(0) or A(k), depending on iteration order
		private HistoryNode junctionNode;
		// initialized when there's HgFileRenameHandlerMixin
		private HgFileRevision copiedFrom, copiedTo; 

		// parentProgress shall be initialized with 4 XXX refactor all this stuff with parentProgress 
		public void prepare(ProgressSupport parentProgress, QueueElement renameInfo) throws HgRuntimeException {
			changeHistory = treeBuildInspector.go(renameInfo);
			assert changeHistory.size() > 0;
			parentProgress.worked(1);
			int historyNodeCount = changeHistory.size();
			if (ei == null) {
				// when follow is true, changeHistory.size() of the first revision might be quite short 
				// (e.g. bad fname recognized soon), hence ensure at least cache size at once
				ei = new ElementImpl(Math.max(CACHE_CSET_IN_ADVANCE_THRESHOLD, historyNodeCount));
			}
			if (historyNodeCount < CACHE_CSET_IN_ADVANCE_THRESHOLD ) {
				int[] commitRevisions = treeBuildInspector.getCommitRevisions();
				assert commitRevisions.length == changeHistory.size();
				// read bunch of changesets at once and cache 'em
				ei.initTransform();
				repo.getChangelog().range(ei, commitRevisions);
				parentProgress.worked(1);
				progress = new ProgressSupport.Sub(parentProgress, 2);
			} else {
				progress = new ProgressSupport.Sub(parentProgress, 3);
			}
			progress.start(historyNodeCount);
			// switch to present chunk's file node 
			switchTo(renameInfo.file());
		}
		
		public void updateJunctionPoint(QueueElement curRename, QueueElement nextRename, boolean needCopyFromTo) throws HgRuntimeException {
			copiedFrom = copiedTo = null;
			//
			// A (old) renamed to B(new).  A(0..k..n) -> B(0..m). If followAncestry, k == n
			// curRename.second() points to A(k)
			if (iterateDirection == HgIterateDirection.OldToNew) {
				// looking at A chunk (curRename), nextRename points to B
				HistoryNode junctionSrc = findJunctionPointInCurrentChunk(curRename.lastFileRev()); // A(k)
				HistoryNode junctionDestMock = treeBuildInspector.one(nextRename.file(), 0); // B(0)
				// junstionDestMock is mock object, once we iterate next rename, there'd be different HistoryNode
				// for B's first revision. This means we read it twice, but this seems to be reasonable
				// price for simplicity of the code (and opportunity to follow renames while not following ancestry)
				junctionSrc.bindChild(junctionDestMock);
				// Save mock A(k) 1) not to keep whole A history in memory 2) Don't need it's parent and children once get to B
				// moreover, children of original A(k) (junctionSrc) would list mock B(0) which is undesired once we iterate over real B
				junctionNode = new HistoryNode(junctionSrc.changeset, junctionSrc.fileRevision, null, null);
				if (needCopyFromTo) {
					copiedFrom = new HgFileRevision(curRename.file(), junctionNode.fileRevision, null); // "A", A(k)
					copiedTo = new HgFileRevision(nextRename.file(), junctionDestMock.fileRevision, copiedFrom.getPath()); // "B", B(0)
				}
			} else {
				assert iterateDirection == HgIterateDirection.NewToOld;
				// looking at B chunk (curRename), nextRename points at A
				HistoryNode junctionDest = changeHistory.get(0); // B(0)
				// prepare mock A(k)
				HistoryNode junctionSrcMock = treeBuildInspector.one(nextRename.file(), nextRename.lastFileRev()); // A(k)
				// B(0) to list A(k) as its parent
				// NOTE, A(k) would be different when we reach A chunk on the next iteration,
				// but we do not care as long as TreeElement needs only parent/child changesets
				// and not other TreeElements; so that it's enough to have mock parent node (just 
				// for the sake of parent cset revisions). We have to, indeed, update real A(k),
				// once we get to iteration over A, with B(0) (junctionDest) as one more child.
				junctionSrcMock.bindChild(junctionDest);
				// Save mock B(0), for reasons see above for opposite direction
				junctionNode = new HistoryNode(junctionDest.changeset, junctionDest.fileRevision, null, null);
				if (needCopyFromTo) {
					copiedFrom = new HgFileRevision(nextRename.file(), junctionSrcMock.fileRevision, null); // "A", A(k)
					copiedTo = new HgFileRevision(curRename.file(), junctionNode.fileRevision, copiedFrom.getPath()); // "B", B(0)
				}
			}
		}
		
		public void reportRenames(HgFileRenameHandlerMixin renameHandler) throws HgCallbackTargetException, HgRuntimeException {
			if (renameHandler != null) { // shall report renames
				assert copiedFrom != null;
				assert copiedTo != null;
				renameHandler.copy(copiedFrom, copiedTo);
			}
		}
		
		public void clearJunctionPoint() {
			junctionNode = null;
			copiedFrom = copiedTo = null;
		}
		
		/**
		 * Replace mock src/dest HistoryNode connected to junctionNode with a real one
		 */
		public void connectWithLastJunctionPoint(QueueElement curRename, QueueElement prevRename) {
			assert junctionNode != null;
			// A renamed to B. A(0..k..n) -> B(0..m). If followAncestry: k == n  
			if (iterateDirection == HgIterateDirection.OldToNew) {
				// forward, from old to new:
				// changeHistory points to B 
				// Already reported: A(0)..A(n), A(k) is in junctionNode
				// Shall connect histories: A(k).bind(B(0))
				HistoryNode junctionDest = changeHistory.get(0); // B(0)
				// junctionNode is A(k)
				junctionNode.bindChild(junctionDest); 
			} else {
				assert iterateDirection == HgIterateDirection.NewToOld;
				// changeHistory points to A
				// Already reported B(m), B(m-1)...B(0), B(0) is in junctionNode
				// Shall connect histories A(k).bind(B(0))
				// if followAncestry: A(k) is latest in changeHistory (k == n)
				HistoryNode junctionSrc = findJunctionPointInCurrentChunk(curRename.lastFileRev()); // A(k)
				junctionSrc.bindChild(junctionNode);
			}
		}
		
		private HistoryNode findJunctionPointInCurrentChunk(Nodeid fileRevision) {
			if (followAncestry) {
				// use the fact we don't go past junction point when followAncestry == true
				HistoryNode rv = changeHistory.get(changeHistory.size() - 1);
				assert rv.fileRevision.equals(fileRevision);
				return rv;
			}
			for (HistoryNode n : changeHistory) {
				if (n.fileRevision.equals(fileRevision)) {
					return n;
				}
			}
			int csetStart = changeHistory.get(0).changeset;
			int csetEnd = changeHistory.get(changeHistory.size() - 1).changeset;
			throw new HgInvalidStateException(String.format("For change history (cset[%d..%d]) could not find node for file change %s", csetStart, csetEnd, fileRevision.shortNotation()));
		}

		protected abstract void once(HistoryNode n) throws HgCallbackTargetException, CancelledException, HgRuntimeException;
		
		public void dispatchAllChanges() throws HgCallbackTargetException, CancelledException, HgRuntimeException {
			// XXX shall sort changeHistory according to changeset numbers?
			Iterator<HistoryNode> it;
			if (iterateDirection == HgIterateDirection.OldToNew) {
				it = changeHistory.listIterator();
			} else {
				assert iterateDirection == HgIterateDirection.NewToOld;
				it = new ReverseIterator<HistoryNode>(changeHistory);
			}
			while(it.hasNext()) {
				HistoryNode n = it.next();
				once(n);
				progress.worked(1);
			}
			changeHistory = null;
		}

		public void switchTo(HgDataFile df) {
			// from now on, use df in TreeElement
			currentFileNode = df;
		}
	}


	//
	
	private class FilteringInspector extends AdapterPlug implements HgChangelog.Inspector, Adaptable {
	
		private int firstCset = BAD_REVISION, lastCset = BAD_REVISION;
		private HgChangelog.Inspector delegate;
		// we use lifecycle to stop when limit is reached.
		// delegate, however, may use lifecycle, too, so give it a chance
		private LifecycleProxy lifecycleProxy;
		
		// limit to changesets in this range only
		public void changesets(int start, int end) {
			firstCset = start;
			lastCset = end;
		}
		
		public void delegateTo(HgChangelog.Inspector inspector) {
			delegate = inspector;
			// let delegate control life cycle, too
			if (lifecycleProxy == null) {
				super.attachAdapter(Lifecycle.class, lifecycleProxy = new LifecycleProxy(inspector));
			} else {
				lifecycleProxy.init(inspector);
			}
		}

		public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) throws HgRuntimeException {
			if (limit > 0 && count >= limit) {
				return;
			}
			// XXX may benefit from optional interface with #isInterested(int csetRev) - to avoid
			// RawChangeset instantiation
			if (firstCset != BAD_REVISION && revisionNumber < firstCset) {
				return;
			}
			if (lastCset != BAD_REVISION && revisionNumber > lastCset) {
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
			delegate.next(revisionNumber, nodeid, cset);
			count++;
			if (limit > 0 && count >= limit) {
				lifecycleProxy.stop();
			}
		}
	}

	private HgParentChildMap<HgChangelog> getParentHelper(boolean create) throws HgRuntimeException {
		if (parentHelper == null && create) {
			parentHelper = new HgParentChildMap<HgChangelog>(repo.getChangelog());
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
		HistoryNode parent1; // there's special case when we can alter it, see #bindChild()
		final HistoryNode parent2;
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
		
		private void addChild(HistoryNode child) {
			if (children == null) {
				children = new ArrayList<HistoryNode>(2);
			}
			children.add(child);
		}
		
		/**
		 * method to merge two history chunks for renamed file so that
		 * this node's history continues (or forks, if we don't followAncestry)
		 * with that of child
		 * @param child
		 */
		public void bindChild(HistoryNode child) {
			assert child.parent1 == null && child.parent2 == null;
			child.parent1 = this;
			addChild(child);
		}
		
		public String toString() {
			return String.format("<cset:%d, parents: %s, %s>", changeset, parent1 == null ? "-" : String.valueOf(parent1.changeset), parent2 == null ? "-" : String.valueOf(parent2.changeset));
		}
	}

	private class ElementImpl implements HgChangesetTreeHandler.TreeElement, HgChangelog.Inspector {
		private HistoryNode historyNode;
		private HgDataFile fileNode;
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

		ElementImpl init(HistoryNode n, HgDataFile df) {
			historyNode = n;
			fileNode = df;
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
		
		public HgDataFile file() {
			return fileNode;
		}

		public HgChangeset changeset() throws HgRuntimeException {
			return get(historyNode.changeset)[0];
		}

		public Pair<HgChangeset, HgChangeset> parents() throws HgRuntimeException {
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

		public Collection<HgChangeset> children() throws HgRuntimeException {
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
		
		private HgChangeset[] get(int... changelogRevisionIndex) throws HgRuntimeException {
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
						throw new HgInvalidStateException(String.format("Can't get changeset for revision %d", changeset2read));
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
						repo.getSessionContext().getLog().dump(getClass(), Error, "Index of revision %d:%s doesn't match any of requested", cs.getRevisionIndex(), cs.getNodeid().shortNotation());
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

		public Nodeid changesetRevision() throws HgRuntimeException {
			if (changesetRevision == null) {
				changesetRevision = getRevision(historyNode.changeset);
			}
			return changesetRevision;
		}

		public Pair<Nodeid, Nodeid> parentRevisions() throws HgRuntimeException {
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

		public Collection<Nodeid> childRevisions() throws HgRuntimeException {
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
		private Nodeid getRevision(int changelogRevisionNumber) throws HgRuntimeException {
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
