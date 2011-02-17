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
import java.util.TreeMap;
import java.util.TreeSet;

import org.tmatesoft.hg.core.Nodeid;
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
	private final Map<Integer, ManifestRevisionInspector> cache; // sparse array, in fact
	private PathPool pathPool;

	public HgStatusCollector(HgRepository hgRepo) {
		this.repo = hgRepo;
		cache = new TreeMap<Integer, ManifestRevisionInspector>();
		ManifestRevisionInspector emptyFakeState = new ManifestRevisionInspector();
		emptyFakeState.begin(-1, null);
		emptyFakeState.end(-1); // FIXME HgRepo.TIP == -1 as well, need to distinguish fake "prior to first" revision from "the very last" 
		cache.put(-1, emptyFakeState);
	}
	
	public HgRepository getRepo() {
		return repo;
	}
	
	private ManifestRevisionInspector get(int rev) {
		ManifestRevisionInspector i = cache.get(rev);
		if (i == null) {
			i = new ManifestRevisionInspector();
			cache.put(rev, i);
			repo.getManifest().walk(rev, rev, i);
		}
		return i;
	}
	
	/*package-local*/ ManifestRevisionInspector raw(int rev) {
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
		
	
	// hg status --change <rev>
	public void change(int rev, HgStatusInspector inspector) {
		int[] parents = new int[2];
		repo.getChangelog().parents(rev, parents, null, null);
		walk(parents[0], rev, inspector);
	}

	// I assume revision numbers are the same for changelog and manifest - here 
	// user would like to pass changelog revision numbers, and I use them directly to walk manifest.
	// if this assumption is wrong, fix this (lookup manifest revisions from changeset).
	public void walk(int rev1, int rev2, HgStatusInspector inspector) {
		if (rev1 == rev2) {
			throw new IllegalArgumentException();
		}
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		if (inspector instanceof Record) {
			((Record) inspector).init(rev1, rev2, this);
		}
		if (rev1 == TIP) {
			rev1 = repo.getManifest().getLastRevision();
		}
		if (rev2 == TIP) {
			rev2 = repo.getManifest().getLastRevision(); 
		}
		// in fact, rev1 and rev2 are often next (or close) to each other,
		// thus, we can optimize Manifest reads here (manifest.walk(rev1, rev2))
		ManifestRevisionInspector r1, r2 ;
		if (!cache.containsKey(rev1) && !cache.containsKey(rev2) && Math.abs(rev1 - rev2) < 5 /*subjective equivalent of 'close enough'*/) {
			int minRev = rev1 < rev2 ? rev1 : rev2;
			int maxRev = minRev == rev1 ? rev2 : rev1;
			if (minRev > 0) {
				minRev--; // expand range a bit
				// XXX perhaps, if revlog.baseRevision is cheap, shall expand minRev up to baseRevision
				// which gonna be read anyway
			}
	
			repo.getManifest().walk(minRev, maxRev, new HgManifest.Inspector() {
				private ManifestRevisionInspector delegate;

				public boolean begin(int revision, Nodeid nid) {
					cache.put(revision, delegate = new ManifestRevisionInspector());
					delegate.begin(revision, nid);
					return true;
				}

				public boolean next(Nodeid nid, String fname, String flags) {
					delegate.next(nid, fname, flags);
					return true;
				}
				
				public boolean end(int revision) {
					delegate.end(revision);
					delegate = null;
					return true;
				}
			});
		}
		r1 = get(rev1);
		r2 = get(rev2);

		PathPool pp = getPathPool();

		TreeSet<String> r1Files = new TreeSet<String>(r1.files());
		for (String fname : r2.files()) {
			if (r1Files.remove(fname)) {
				Nodeid nidR1 = r1.nodeid(fname);
				Nodeid nidR2 = r2.nodeid(fname);
				String flagsR1 = r1.flags(fname);
				String flagsR2 = r2.flags(fname);
				if (nidR1.equals(nidR2) && ((flagsR2 == null && flagsR1 == null) || flagsR2.equals(flagsR1))) {
					inspector.clean(pp.path(fname));
				} else {
					inspector.modified(pp.path(fname));
				}
			} else {
				Path copyTarget = pp.path(fname);
				Path copyOrigin = getOriginIfCopy(repo, copyTarget, r1Files, rev1);
				if (copyOrigin != null) {
					inspector.copied(pp.path(copyOrigin) /*pipe through pool, just in case*/, copyTarget);
				} else {
					inspector.added(copyTarget);
				}
			}
		}
		for (String left : r1Files) {
			inspector.removed(pp.path(left));
		}
	}
	
	public Record status(int rev1, int rev2) {
		Record rv = new Record();
		walk(rev1, rev2, rv);
		return rv;
	}
	
	/*package-local*/static Path getOriginIfCopy(HgRepository hgRepo, Path fname, Collection<String> originals, int originalChangelogRevision) {
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

	/*package-local*/ static final class ManifestRevisionInspector implements HgManifest.Inspector {
		private final TreeMap<String, Nodeid> idsMap;
		private final TreeMap<String, String> flagsMap;

		public ManifestRevisionInspector() {
			idsMap = new TreeMap<String, Nodeid>();
			flagsMap = new TreeMap<String, String>();
		}
		
		public Collection<String> files() {
			return idsMap.keySet();
		}

		public Nodeid nodeid(String fname) {
			return idsMap.get(fname);
		}

		public String flags(String fname) {
			return flagsMap.get(fname);
		}

		//

		public boolean next(Nodeid nid, String fname, String flags) {
			idsMap.put(fname, nid);
			flagsMap.put(fname, flags);
			return true;
		}

		public boolean end(int revision) {
			// in fact, this class cares about single revision
			return false; 
		}

		public boolean begin(int revision, Nodeid nid) {
			return true;
		}
	}

}
