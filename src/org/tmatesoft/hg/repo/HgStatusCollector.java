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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.core.HgDataStreamException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathPool;
import org.tmatesoft.hg.util.PathRewrite;


/**
 * RevisionWalker?
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
	private PathPool pathPool;
	private final Pool<Nodeid> cacheNodes;
	private final Pool<String> cacheFilenames; // XXX in fact, need to think if use of PathPool directly instead is better solution
	private final ManifestRevision emptyFakeState;
	private Path.Matcher scope = new Path.Matcher.Any();
	

	public HgStatusCollector(HgRepository hgRepo) {
		this.repo = hgRepo;
		cache = new IntMap<ManifestRevision>(cacheMaxSize);
		cacheNodes = new Pool<Nodeid>();
		cacheFilenames = new Pool<String>();

		emptyFakeState = new ManifestRevision(null, null);
		emptyFakeState.begin(-1, null, -1);
		emptyFakeState.end(-1);
	}
	
	public HgRepository getRepo() {
		return repo;
	}
	
	private ManifestRevision get(int rev) {
		ManifestRevision i = cache.get(rev);
		if (i == null) {
			if (rev == -1) {
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
		return cache.containsKey(revision) || revision == -1;
	}
	
	private void ensureCacheSize() {
		if (cache.size() > cacheMaxSize) {
			// assume usually we go from oldest to newest, hence remove oldest as most likely to be no longer necessary
			cache.removeFromStart(cache.size() - cacheMaxSize + 1 /* room for new element */);
		}
	}
	
	private void initCacheRange(int minRev, int maxRev) {
		ensureCacheSize();
		repo.getManifest().walk(minRev, maxRev, new HgManifest.Inspector() {
			private ManifestRevision delegate;
			private boolean cacheHit; // range may include revisions we already know about, do not re-create them

			public boolean begin(int manifestRevision, Nodeid nid, int changelogRevision) {
				assert delegate == null;
				if (cache.containsKey(changelogRevision)) { // don't need to check emptyFakeState hit as revision never -1 here
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

			public boolean next(Nodeid nid, String fname, String flags) {
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
		});
	}
	
	/*package-local*/ ManifestRevision raw(int rev) {
		return get(rev);
	}
	/*package-local*/ PathPool getPathPool() {
		if (pathPool == null) {
			pathPool = new PathPool(new PathRewrite.Empty());
		}
		return pathPool;
	}

	/**
	 * Allows sharing of a common path cache 
	 */
	public void setPathPool(PathPool pathPool) {
		this.pathPool = pathPool;
	}

	/**
	 * Limit activity of the collector to certain sub-tree of the repository.
	 * @param scopeMatcher tells whether collector shall report specific path, can be <code>null</code>
	 */
	public void setScope(Path.Matcher scopeMatcher) {
		// do not assign null, ever
		scope = scopeMatcher == null ? new Path.Matcher.Any() : scopeMatcher;
	}
	
	// hg status --change <rev>
	public void change(int rev, HgStatusInspector inspector) {
		int[] parents = new int[2];
		repo.getChangelog().parents(rev, parents, null, null);
		walk(parents[0], rev, inspector);
	}
	
	// rev1 and rev2 are changelog revision numbers, argument order matters.
	// Either rev1 or rev2 may be -1 to indicate comparison to empty repository (XXX this is due to use of 
	// parents in #change(), I believe. Perhaps, need a constant for this? Otherwise this hidden knowledge gets
	// exposed to e.g. Record
	public void walk(int rev1, int rev2, HgStatusInspector inspector) {
		if (rev1 == rev2) {
			throw new IllegalArgumentException();
		}
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		final int lastManifestRevision = repo.getChangelog().getLastRevision();
		if (rev1 == TIP) {
			rev1 = lastManifestRevision;
		}
		if (rev2 == TIP) {
			rev2 = lastManifestRevision; 
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
				maxRev = rev1 < lastManifestRevision-5 ? rev1+5 : lastManifestRevision;
				initCacheRange(minRev, maxRev);
			}
			if (need2) {
				minRev = rev2;
				maxRev = rev2 < lastManifestRevision-5 ? rev2+5 : lastManifestRevision;
				initCacheRange(minRev, maxRev);
			}
		}
		r1 = get(rev1);
		r2 = get(rev2);

		PathPool pp = getPathPool();
		TreeSet<String> r1Files = new TreeSet<String>(r1.files());
		for (String fname : r2.files()) {
			final Path r2filePath = pp.path(fname);
			if (!scope.accept(r2filePath)) {
				continue;
			}
			if (r1Files.remove(fname)) {
				Nodeid nidR1 = r1.nodeid(fname);
				Nodeid nidR2 = r2.nodeid(fname);
				String flagsR1 = r1.flags(fname);
				String flagsR2 = r2.flags(fname);
				if (nidR1.equals(nidR2) && ((flagsR2 == null && flagsR1 == null) || (flagsR2 != null && flagsR2.equals(flagsR1)))) {
					inspector.clean(r2filePath);
				} else {
					inspector.modified(r2filePath);
				}
			} else {
				try {
					Path copyTarget = r2filePath;
					Path copyOrigin = getOriginIfCopy(repo, copyTarget, r1Files, rev1);
					if (copyOrigin != null) {
						inspector.copied(pp.path(copyOrigin) /*pipe through pool, just in case*/, copyTarget);
					} else {
						inspector.added(copyTarget);
					}
				} catch (HgDataStreamException ex) {
					ex.printStackTrace();
					// FIXME perhaps, shall record this exception to dedicated mediator and continue
					// for a single file not to be irresolvable obstacle for a status operation
				}
			}
		}
		for (String left : r1Files) {
			final Path r2filePath = pp.path(left);
			if (scope.accept(r2filePath)) {
				inspector.removed(r2filePath);
			}
		}
	}
	
	public Record status(int rev1, int rev2) {
		Record rv = new Record();
		walk(rev1, rev2, rv);
		return rv;
	}
	
	/*package-local*/static Path getOriginIfCopy(HgRepository hgRepo, Path fname, Collection<String> originals, int originalChangelogRevision) throws HgDataStreamException {
		HgDataFile df = hgRepo.getFileNode(fname);
		while (df.isCopy()) {
			Path original = df.getCopySourceName();
			if (originals.contains(original.toString())) {
				df = hgRepo.getFileNode(original);
				int changelogRevision = df.getChangesetLocalRevision(0);
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
	public static class Record implements HgStatusInspector {
		private List<Path> modified, added, removed, clean, missing, unknown, ignored;
		private Map<Path, Path> copied;
		
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
		
		public Nodeid nodeidBeforeChange(Path fname) {
			if (statusHelper == null || startRev == BAD_REVISION) {
				return null;
			}
			if ((modified == null || !modified.contains(fname)) && (removed == null || !removed.contains(fname))) {
				return null;
			}
			return statusHelper.raw(startRev).nodeid(fname.toString());
		}
		public Nodeid nodeidAfterChange(Path fname) {
			if (statusHelper == null || endRev == BAD_REVISION) {
				return null;
			}
			if ((modified == null || !modified.contains(fname)) && (added == null || !added.contains(fname))) {
				return null;
			}
			return statusHelper.raw(endRev).nodeid(fname.toString());
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
		
		private List<Path> proper(List<Path> l) {
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

		private static List<Path> doAdd(List<Path> l, Path p) {
			if (l == null) {
				l = new LinkedList<Path>();
			}
			l.add(p);
			return l;
		}
	}

}
