/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * RevisionWalker?
 * @author artem
 */
public class StatusCollector {

	private final HgRepository repo;
	private final Map<Integer, ManifestRevisionInspector> cache; // sparse array, in fact

	public StatusCollector(HgRepository hgRepo) {
		this.repo = hgRepo;
		cache = new HashMap<Integer, ManifestRevisionInspector>();
	}
	
	private ManifestRevisionInspector get(int rev) {
		ManifestRevisionInspector i = cache.get(rev);
		if (i == null) {
			i = new ManifestRevisionInspector(rev, rev);
			cache.put(rev, i);
			repo.getManifest().walk(rev, rev, i);
		}
		return i;
	}

	public void walk(int rev1, int rev2, Inspector inspector) {
		if (rev1 == rev2) {
			throw new IllegalArgumentException();
		}
		// in fact, rev1 and rev2 are often next (or close) to each other,
		// thus, we can optimize Manifest reads here (manifest.walk(rev1, rev2))
 
		ManifestRevisionInspector r1, r2;
		if (!cache.containsKey(rev1) && !cache.containsKey(rev2) && Math.abs(rev1 - rev2) < 5 /*subjective equivalent of 'close enough'*/) {
			int minRev = rev1 < rev2 ? rev1 : rev2;
			int maxRev = minRev == rev1 ? rev2 : rev1;
			r1 = r2 = new ManifestRevisionInspector(minRev, maxRev);
			for (int i = minRev; i <= maxRev; i++) {
				cache.put(i, r1);
			}
			repo.getManifest().walk(minRev, maxRev, r1);
		} else {
			r1 = get(rev1);
			r2 = get(rev2);
		}
		
		TreeSet<String> r1Files = new TreeSet<String>(r1.files(rev1));
		for (String fname : r2.files(rev2)) {
			if (r1Files.remove(fname)) {
				Nodeid nidR1 = r1.nodeid(rev1, fname);
				Nodeid nidR2 = r2.nodeid(rev2, fname);
				String flagsR1 = r1.flags(rev1, fname);
				String flagsR2 = r2.flags(rev2, fname);
				if (nidR1.equals(nidR2) && ((flagsR2 == null && flagsR1 == null) || flagsR2.equals(flagsR1))) {
					inspector.clean(fname);
				} else {
					inspector.modified(fname);
				}
			} else {
				inspector.added(fname);
			}
		}
		for (String left : r1Files) {
			inspector.removed(left);
		}
		// inspector.done() if useful e.g. in UI client
	}
	
	public Record status(int rev1, int rev2) {
		Record rv = new Record();
		walk(rev1, rev2, rv);
		return rv;
	}

	public interface Inspector {
		void modified(String fname);
		void added(String fname);
		void copied(String fnameOrigin, String fnameAdded); // if copied files of no interest, should delegate to self.added(fnameAdded);
		void removed(String fname);
		void clean(String fname);
		void missing(String fname); // aka deleted (tracked by Hg, but not available in FS any more
		void unknown(String fname); // not tracked
		void ignored(String fname);
	}

	// XXX for r1..r2 status, only modified, added, removed (and perhaps, clean) make sense
	public static class Record implements Inspector {
		private List<String> modified, added, removed, clean, missing, unknown, ignored;
		private Map<String, String> copied;
		
		public List<String> getModified() {
			return proper(modified);
		}

		public List<String> getAdded() {
			return proper(added);
		}

		public List<String> getRemoved() {
			return proper(removed);
		}

		public Map<String,String> getCopied() {
			if (copied == null) {
				return Collections.emptyMap();
			}
			return Collections.unmodifiableMap(copied);
		}

		public List<String> getClean() {
			return proper(clean);
		}

		public List<String> getMissing() {
			return proper(missing);
		}

		public List<String> getUnknown() {
			return proper(unknown);
		}

		public List<String> getIgnored() {
			return proper(ignored);
		}
		
		private List<String> proper(List<String> l) {
			if (l == null) {
				return Collections.emptyList();
			}
			return Collections.unmodifiableList(l);
		}

		//
		//
		
		public void modified(String fname) {
			modified = doAdd(modified, fname);
		}

		public void added(String fname) {
			added = doAdd(added, fname);
		}

		public void copied(String fnameOrigin, String fnameAdded) {
			if (copied == null) {
				copied = new LinkedHashMap<String, String>();
			}
			copied.put(fnameOrigin, fnameAdded);
		}

		public void removed(String fname) {
			removed = doAdd(removed, fname);
		}

		public void clean(String fname) {
			clean = doAdd(clean, fname);
		}

		public void missing(String fname) {
			missing = doAdd(missing, fname);
		}

		public void unknown(String fname) {
			unknown = doAdd(unknown, fname);
		}

		public void ignored(String fname) {
			ignored = doAdd(ignored, fname);
		}

		private static List<String> doAdd(List<String> l, String s) {
			if (l == null) {
				l = new LinkedList<String>();
			}
			l.add(s);
			return l;
		}
	}

	public /*XXX private, actually. Made public unless repo.statusLocal finds better place*/ static final class ManifestRevisionInspector implements HgManifest.Inspector {
		private final HashMap<String, Nodeid>[] idsMap;
		private final HashMap<String, String>[] flagsMap;
		private final int baseRevision;
		private int r = -1; // cursor

		/**
		 * [minRev, maxRev]
		 * @param minRev - inclusive
		 * @param maxRev - inclusive
		 */
		@SuppressWarnings("unchecked")
		public ManifestRevisionInspector(int minRev, int maxRev) {
			baseRevision = minRev;
			int range = maxRev - minRev + 1;
			idsMap = new HashMap[range];
			flagsMap = new HashMap[range];
		}
		
		public Collection<String> files(int rev) {
			if (rev < baseRevision || rev >= baseRevision + idsMap.length) {
				throw new IllegalArgumentException();
			}
			return idsMap[rev - baseRevision].keySet();
		}

		public Nodeid nodeid(int rev, String fname) {
			if (rev < baseRevision || rev >= baseRevision + idsMap.length) {
				throw new IllegalArgumentException();
			}
			return idsMap[rev - baseRevision].get(fname);
		}

		public String flags(int rev, String fname) {
			if (rev < baseRevision || rev >= baseRevision + idsMap.length) {
				throw new IllegalArgumentException();
			}
			return flagsMap[rev - baseRevision].get(fname);
		}

		//

		public boolean next(Nodeid nid, String fname, String flags) {
			idsMap[r].put(fname, nid);
			flagsMap[r].put(fname, flags);
			return true;
		}

		public boolean end(int revision) {
			assert revision == r + baseRevision;
			r = -1;
			return revision+1 < baseRevision + idsMap.length;
		}

		public boolean begin(int revision, Nodeid nid) {
			if (revision < baseRevision || revision >= baseRevision + idsMap.length) {
				throw new IllegalArgumentException();
			}
			r = revision - baseRevision;
			idsMap[r] = new HashMap<String, Nodeid>();
			flagsMap[r] = new HashMap<String, String>();
			return true;
		}
	}

}
