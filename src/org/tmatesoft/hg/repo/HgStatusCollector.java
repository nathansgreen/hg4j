/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.*;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Convertor;
import org.tmatesoft.hg.util.Path;


/**
 * Collect status information for changes between two repository revisions.
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgStatusCollector {

	private final HgRepository repo;
	private final IntMap<ManifestRevision> cache; // sparse array, in fact
	// with cpython repository, ~70 000 changes, complete Log (direct out, no reverse) output 
	// no cache limit, no nodeids and fname caching - OOME on changeset 1035
	// no cache limit, but with cached nodeids and filenames - 1730+
	// cache limit 100 - 19+ minutes to process 10000, and still working (too long, stopped)
	private final int cacheMaxSize = 50; // do not keep too much manifest revisions
	private Convertor<Path> pathPool;
	private final Pool<Nodeid> cacheNodes;
	private final Pool<Path> cacheFilenames;
	private final ManifestRevision emptyFakeState;
	private Path.Matcher scope = new Path.Matcher.Any();
	// @see #detectCopies()
	private boolean detectCopies = true;
	

	public HgStatusCollector(HgRepository hgRepo) {
		this.repo = hgRepo;
		cache = new IntMap<ManifestRevision>(cacheMaxSize);
		cacheNodes = new Pool<Nodeid>();
		cacheFilenames = new Pool<Path>();

		emptyFakeState = createEmptyManifestRevision();
	}
	
	public HgRepository getRepo() {
		return repo;
	}
	
	private ManifestRevision get(int rev) throws HgRuntimeException {
		ManifestRevision i = cache.get(rev);
		if (i == null) {
			if (rev == NO_REVISION) {
				return emptyFakeState;
			}
			ensureCacheSize();
			i = new ManifestRevision(cacheNodes, cacheFilenames);
			cache.put(rev, i);
			repo.getManifest().walk(rev, rev, i);
		}
		return i;
	}

	private boolean cached(int revision) {
		return cache.containsKey(revision) || revision == NO_REVISION;
	}
	
	private void ensureCacheSize() {
		if (cache.size() > cacheMaxSize) {
			// assume usually we go from oldest to newest, hence remove oldest as most likely to be no longer necessary
			cache.removeFromStart(cache.size() - cacheMaxSize + 1 /* room for new element */);
		}
	}
	
	private void initCacheRange(int minRev, int maxRev) throws HgRuntimeException {
		ensureCacheSize();
		// In fact, walk(minRev, maxRev) doesn't imply
		// there would be maxRev-minRev+1 revisions visited. For example,
		// check cpython repo with 'hg log -r 22418:22420 --debug' and admire
		// manifest revisions 66650, 21683, 21684.  Thus, innocent walk(22418,22420) results in 40k+ revisions and OOME
		// Instead, be explicit of what revisions are of interest
		assert minRev <= maxRev;
		int[] revisionsToCollect = new int[maxRev - minRev + 1];
		for (int x = minRev, i = 0; x <= maxRev; i++, x++) {
			revisionsToCollect[i] = x;
		}
		repo.getManifest().walk(new HgManifest.Inspector() {
			private ManifestRevision delegate;
			private boolean cacheHit; // range may include revisions we already know about, do not re-create them

			public boolean begin(int manifestRevision, Nodeid nid, int changelogRevision) {
				assert delegate == null;
				if (cache.containsKey(changelogRevision)) { // don't need to check emptyFakeState hit as revision never NO_REVISION here
					cacheHit = true;
				} else {
					cache.put(changelogRevision, delegate = new ManifestRevision(cacheNodes, cacheFilenames));
					// cache may grow bigger than max size here, but it's ok as present simplistic cache clearing mechanism may
					// otherwise remove entries we just added
					delegate.begin(manifestRevision, nid, changelogRevision);
					cacheHit = false;
				}
				return true;
			}

			public boolean next(Nodeid nid, Path fname, HgManifest.Flags flags) {
				if (!cacheHit) {
					delegate.next(nid, fname, flags);
				}
				return true;
			}
			
			public boolean end(int revision) {
				if (!cacheHit) {
					delegate.end(revision);
				}
				cacheHit = false;				
				delegate = null;
				return true;
			}
		}, revisionsToCollect);
	}
	
	/*package-local*/ static ManifestRevision createEmptyManifestRevision() {
		ManifestRevision fakeEmptyRev = new ManifestRevision(null, null);
		fakeEmptyRev.begin(NO_REVISION, null, NO_REVISION);
		fakeEmptyRev.end(NO_REVISION);
		return fakeEmptyRev;
	}
	
	/**
	 * Access specific manifest revision
	 * @param rev 
	 * @return
	 * @throws HgInvalidControlFileException
	 */
	/*package-local*/ ManifestRevision raw(int rev) throws HgRuntimeException {
		return get(rev);
	}
	/*package-local*/ Convertor<Path> getPathPool() {
		if (pathPool == null) {
			pathPool = cacheFilenames;
		}
		return pathPool;
	}

	/**
	 * Allows sharing of a common path cache 
	 */
	public void setPathPool(Convertor<Path> pathConvertor) {
		pathPool = pathConvertor;
	}

	/**
	 * Limit activity of the collector to certain sub-tree of the repository.
	 * @param scopeMatcher tells whether collector shall report specific path, can be <code>null</code>
	 */
	public void setScope(Path.Matcher scopeMatcher) {
		// do not assign null, ever
		scope = scopeMatcher == null ? new Path.Matcher.Any() : scopeMatcher;
	}

	/**
	 * Select whether Collector shall tell "added-new" from "added-by-copy/rename" files.
	 * This is analogous to '-C' switch of 'hg status' command.
	 * 
	 * <p>With copy detection turned off, files continue be reported as plain 'added' files.
	 * 
	 * <p>By default, copy detection is <em>on</em>, as it's reasonably cheap. However,
	 * in certain scenarios it may be reasonable to turn it off, for example when it's a merge
	 * of two very different branches and there are a lot of files added/moved.
	 *  
	 * Another legitimate reason to set detection to off if you're lazy to 
	 * implement {@link HgStatusInspector#copied(Path, Path)} ;)
	 * 
	 * @param detect <code>true</code> if copies detection is desirable
	 */
	public void detectCopies(boolean detect) {
		// cpython, revision:72161, p1:72159, p2:72160
		// p2 comes from another branch with 321 file added (looks like copied/moved, however, the isCopy
		// record present only for couple of them). With 2,5 ms per isCopy() operation, almost a second
		// is spent detecting origins (according to Marc, of little use in this scenario, as it's second parent 
		// in the merge) - in fact, most of the time of the status operation
		detectCopies = detect;
	}
	
	/**
	 * 'hg status --change REV' command counterpart.
	 * 
	 * @throws CancelledException if operation execution was cancelled
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public void change(int revisionIndex, HgStatusInspector inspector) throws CancelledException, HgRuntimeException {
		int p;
		if (revisionIndex == 0) {
			p = NO_REVISION;
		} else {
			int[] parents = new int[2];
			repo.getChangelog().parents(revisionIndex, parents, null, null);
			// #parents call above is responsible for NO_REVISION
			p = parents[0]; // hg --change alsways uses first parent, despite the fact there might be valid (-1, 18) pair of parents
		}
		walk(p, revisionIndex, inspector);
	}
	
	/**
	 * Parameters <b>rev1</b> and <b>rev2</b> are changelog revision indexes, shall not be the same. Argument order matters.
	 * Either rev1 or rev2 may be {@link HgRepository#NO_REVISION} to indicate comparison to empty repository
	 * 
	 * @param rev1 <em>from</em> changeset index, non-negative or {@link HgRepository#TIP}
	 * @param rev2 <em>to</em> changeset index, non-negative or {@link HgRepository#TIP}
	 * @param inspector callback for status information
	 * @throws CancelledException if operation execution was cancelled
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 * @throws IllegalArgumentException inspector other incorrect argument values
	 */
	public void walk(int rev1, int rev2, HgStatusInspector inspector) throws CancelledException, HgRuntimeException, IllegalArgumentException {
		if (rev1 == rev2) {
			throw new IllegalArgumentException();
		}
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		final int lastChangelogRevision = repo.getChangelog().getLastRevision();
		if (rev1 == TIP) {
			rev1 = lastChangelogRevision;
		}
		if (rev2 == TIP) {
			rev2 = lastChangelogRevision; 
		}
		if (rev1 != NO_REVISION && (HgInternals.wrongRevisionIndex(rev1) || rev1 == WORKING_COPY || rev1 == BAD_REVISION || rev1 > lastChangelogRevision)) {
			throw new HgInvalidRevisionException(rev1);
		}
		if (rev2 != NO_REVISION && (HgInternals.wrongRevisionIndex(rev2) || rev2 == WORKING_COPY || rev2 == BAD_REVISION || rev2 > lastChangelogRevision)) {
			throw new HgInvalidRevisionException(rev2);
		}
		if (inspector instanceof Record) {
			((Record) inspector).init(rev1, rev2, this);
		}
		// in fact, rev1 and rev2 are often next (or close) to each other,
		// thus, we can optimize Manifest reads here (manifest.walk(rev1, rev2))
		ManifestRevision r1, r2 ;
		boolean need1 = !cached(rev1), need2 = !cached(rev2);
		if (need1 || need2) {
			int minRev, maxRev;
			if (need1 && need2 && Math.abs(rev1 - rev2) < 5 /*subjective equivalent of 'close enough'*/) {
				minRev = rev1 < rev2 ? rev1 : rev2;
				maxRev = minRev == rev1 ? rev2 : rev1;
				if (minRev > 0) {
					minRev--; // expand range a bit
				}
				initCacheRange(minRev, maxRev);
				need1 = need2 = false;
			}
			// either both unknown and far from each other, or just one of them.
			// read with neighbors to save potential subsequent calls for neighboring elements
			// XXX perhaps, if revlog.baseRevision is cheap, shall expand minRev up to baseRevision
			// which going to be read anyway
			if (need1) {
				minRev = rev1;
				maxRev = rev1 < lastChangelogRevision-5 ? rev1+5 : lastChangelogRevision;
				initCacheRange(minRev, maxRev);
			}
			if (need2) {
				minRev = rev2;
				maxRev = rev2 < lastChangelogRevision-5 ? rev2+5 : lastChangelogRevision;
				initCacheRange(minRev, maxRev);
			}
		}
		r1 = get(rev1);
		r2 = get(rev2);

		final CancelSupport cs = CancelSupport.Factory.get(inspector);

		TreeSet<Path> r1Files = new TreeSet<Path>(r1.files());
		for (Path r2fname : r2.files()) {
			if (!scope.accept(r2fname)) {
				continue;
			}
			if (r1Files.remove(r2fname)) {
				Nodeid nidR1 = r1.nodeid(r2fname);
				Nodeid nidR2 = r2.nodeid(r2fname);
				HgManifest.Flags flagsR1 = r1.flags(r2fname);
				HgManifest.Flags flagsR2 = r2.flags(r2fname);
				if (nidR1.equals(nidR2) && flagsR2 == flagsR1) {
					inspector.clean(r2fname);
				} else {
					inspector.modified(r2fname);
				}
				cs.checkCancelled();
			} else {
				try {
					Path copyTarget = r2fname;
					Path copyOrigin = detectCopies ? getOriginIfCopy(repo, copyTarget, r1Files, rev1) : null;
					if (copyOrigin != null) {
						inspector.copied(getPathPool().mangle(copyOrigin) /*pipe through pool, just in case*/, copyTarget);
					} else {
						inspector.added(copyTarget);
					}
				} catch (HgInvalidFileException ex) {
					// record exception to a mediator and continue, 
					// for a single file not to be irresolvable obstacle for a status operation
					inspector.invalid(r2fname, ex);
				}
				cs.checkCancelled();
			}
		}
		for (Path r1fname : r1Files) {
			if (scope.accept(r1fname)) {
				inspector.removed(r1fname);
				cs.checkCancelled();
			}
		}
	}
	
	/**
	 * Collects status between two revisions, changes from <b>rev1</b> up to <b>rev2</b>.
	 * 
	 * @param rev1 <em>from</em> changeset index 
	 * @param rev2 <em>to</em> changeset index
	 * @return information object that describes change between the revisions
	 * @throws HgInvalidRevisionException if any supplied revision doesn't identify changeset revision. <em>Runtime exception</em>
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public Record status(int rev1, int rev2) throws HgRuntimeException {
		Record rv = new Record();
		try {
			walk(rev1, rev2, rv);
		} catch (CancelledException ex) {
			// can't happen as long our Record class doesn't implement CancelSupport
			HgInvalidStateException t = new HgInvalidStateException("Internal error");
			t.initCause(ex);
			throw t;
		}
		return rv;
	}
	
	/*package-local*/static Path getOriginIfCopy(HgRepository hgRepo, Path fname, Collection<Path> originals, int originalChangelogRevision) throws HgRuntimeException {
		HgDataFile df = hgRepo.getFileNode(fname);
		if (!df.exists()) {
			String msg = String.format("Didn't find file '%s' in the repo. Perhaps, bad storage name conversion?", fname);
			throw new HgInvalidFileException(msg, null).setFileName(fname).setRevisionIndex(originalChangelogRevision);
		}
		while (df.isCopy()) {
			Path original = df.getCopySourceName();
			if (originals.contains(original)) {
				df = hgRepo.getFileNode(original);
				int changelogRevision = df.getChangesetRevisionIndex(0);
				if (changelogRevision <= originalChangelogRevision) {
					// copy/rename source was known prior to rev1 
					// (both r1Files.contains is true and original was created earlier than rev1)
					// without r1Files.contains changelogRevision <= rev1 won't suffice as the file
					// might get removed somewhere in between (changelogRevision < R < rev1)
					return original;
				}
				break; // copy/rename done later
			} 
			df = hgRepo.getFileNode(original); // try more steps away
		}
		return null;
	}

	// XXX for r1..r2 status, only modified, added, removed (and perhaps, clean) make sense
	// XXX Need to specify whether copy targets are in added or not (@see Inspector#copied above)
	/**
	 * Straightforward {@link HgStatusInspector} implementation that collects all status values.
	 * 
	 * <p>Naturally, {@link Record Records} originating from {@link HgStatusCollector} would report only <em>modified, added,
	 * removed</em> and <em>clean</em> values, other are available only when using {@link Record} with {@link HgWorkingCopyStatusCollector}.
	 * 
	 * <p>Note, this implementation records copied files as added, thus key values in {@link #getCopied()} map are subset of paths
	 * from {@link #getAdded()}.  
	 */
	public static class Record implements HgStatusInspector {
		// NOTE, shall not implement CancelSupport, or methods that use it and don't expect this exception shall be changed
		private List<Path> modified, added, removed, clean, missing, unknown, ignored;
		private Map<Path, Path> copied;
		private Map<Path, Exception> failures;
		
		private int startRev, endRev;
		private HgStatusCollector statusHelper;
		
		// XXX StatusCollector may additionally initialize Record instance to speed lookup of changed file revisions
		// here I need access to ManifestRevisionInspector via #raw(). Perhaps, non-static class (to get
		// implicit reference to StatusCollector) may be better?
		// Since users may want to reuse Record instance we've once created (and initialized), we need to  
		// ensure functionality is correct for each/any call (#walk checks instanceof Record and fixes it up)
		// Perhaps, distinct helper (sc.getRevisionHelper().nodeid(fname)) would be better, just not clear
		// how to supply [start..end] values there easily
		/*package-local*/void init(int startRevision, int endRevision, HgStatusCollector self) {
			startRev = startRevision;
			endRev = endRevision;
			statusHelper = self;
		}
		
		public Nodeid nodeidBeforeChange(Path fname) throws HgRuntimeException {
			if (statusHelper == null || startRev == BAD_REVISION) {
				return null;
			}
			if ((modified == null || !modified.contains(fname)) && (removed == null || !removed.contains(fname))) {
				return null;
			}
			return statusHelper.raw(startRev).nodeid(fname);
		}
		public Nodeid nodeidAfterChange(Path fname) throws HgRuntimeException {
			if (statusHelper == null || endRev == BAD_REVISION) {
				return null;
			}
			if ((modified == null || !modified.contains(fname)) && (added == null || !added.contains(fname))) {
				return null;
			}
			return statusHelper.raw(endRev).nodeid(fname);
		}
		
		public List<Path> getModified() {
			return proper(modified);
		}

		public List<Path> getAdded() {
			return proper(added);
		}

		public List<Path> getRemoved() {
			return proper(removed);
		}

		/**
		 * Map files from {@link #getAdded()} to their original filenames, if were copied/moved.
		 */
		public Map<Path,Path> getCopied() {
			if (copied == null) {
				return Collections.emptyMap();
			}
			return Collections.unmodifiableMap(copied);
		}

		public List<Path> getClean() {
			return proper(clean);
		}

		public List<Path> getMissing() {
			return proper(missing);
		}

		public List<Path> getUnknown() {
			return proper(unknown);
		}

		public List<Path> getIgnored() {
			return proper(ignored);
		}

		public Map<Path, Exception> getInvalid() {
			if (failures == null) {
				return Collections.emptyMap();
			}
			return Collections.unmodifiableMap(failures);
		}
		
		private static List<Path> proper(List<Path> l) {
			if (l == null) {
				return Collections.emptyList();
			}
			return Collections.unmodifiableList(l);
		}

		//
		//
		
		public void modified(Path fname) {
			modified = doAdd(modified, fname);
		}

		public void added(Path fname) {
			added = doAdd(added, fname);
		}

		public void copied(Path fnameOrigin, Path fnameAdded) {
			if (copied == null) {
				copied = new LinkedHashMap<Path, Path>();
			}
			added(fnameAdded);
			copied.put(fnameAdded, fnameOrigin);
		}

		public void removed(Path fname) {
			removed = doAdd(removed, fname);
		}

		public void clean(Path fname) {
			clean = doAdd(clean, fname);
		}

		public void missing(Path fname) {
			missing = doAdd(missing, fname);
		}

		public void unknown(Path fname) {
			unknown = doAdd(unknown, fname);
		}

		public void ignored(Path fname) {
			ignored = doAdd(ignored, fname);
		}
		
		public void invalid(Path fname, Exception ex) {
			if (failures == null) {
				failures = new LinkedHashMap<Path, Exception>();
			}
			failures.put(fname, ex);
		}

		private static List<Path> doAdd(List<Path> l, Path p) {
			if (l == null) {
				l = new LinkedList<Path>();
			}
			l.add(p);
			return l;
		}
	}

}
