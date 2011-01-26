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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;


/**
 * RevisionWalker?
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StatusCollector {

	private final HgRepository repo;
	private final Map<Integer, ManifestRevisionInspector> cache; // sparse array, in fact

	public StatusCollector(HgRepository hgRepo) {
		this.repo = hgRepo;
		cache = new HashMap<Integer, ManifestRevisionInspector>();
		ManifestRevisionInspector emptyFakeState = new ManifestRevisionInspector(-1, -1);
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
			i = new ManifestRevisionInspector(rev, rev);
			cache.put(rev, i);
			repo.getManifest().walk(rev, rev, i);
		}
		return i;
	}
	
	/*package-local*/ ManifestRevisionInspector raw(int rev) {
		return get(rev);
	}
	
	// hg status --change <rev>
	public void change(int rev, Inspector inspector) {
		int[] parents = new int[2];
		repo.getChangelog().parents(rev, parents, null, null);
		walk(parents[0], rev, inspector);
	}

	// I assume revision numbers are the same for changelog and manifest - here 
	// user would like to pass changelog revision numbers, and I use them directly to walk manifest.
	// if this assumption is wrong, fix this (lookup manifest revisions from changeset).
	public void walk(int rev1, int rev2, Inspector inspector) {
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
			rev1 = repo.getManifest().getRevisionCount() - 1;
		}
		if (rev2 == TIP) {
			rev2 = repo.getManifest().getRevisionCount() - 1; // XXX add Revlog.tip() func ? 
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
				HgDataFile df = repo.getFileNode(fname);
				boolean isCopy = false;
				while (df.isCopy()) {
					Path original = df.getCopySourceName();
					if (r1Files.contains(original.toString())) {
						df = repo.getFileNode(original);
						int changelogRevision = df.getChangesetLocalRevision(0);
						if (changelogRevision <= rev1) {
							// copy/rename source was known prior to rev1 
							// (both r1Files.contains is true and original was created earlier than rev1)
							// without r1Files.contains changelogRevision <= rev1 won't suffice as the file
							// might get removed somewhere in between (changelogRevision < R < rev1)
							inspector.copied(original.toString(), fname);
							isCopy = true;
						}
						break;
					} 
					df = repo.getFileNode(original); // try more steps away
				}
				if (!isCopy) {
					inspector.added(fname);
				}
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
		// XXX need to specify whether StatusCollector invokes added() along with copied or not!
		void copied(String fnameOrigin, String fnameAdded); // if copied files of no interest, should delegate to self.added(fnameAdded);
		void removed(String fname);
		void clean(String fname);
		void missing(String fname); // aka deleted (tracked by Hg, but not available in FS any more
		void unknown(String fname); // not tracked
		void ignored(String fname);
	}

	// XXX for r1..r2 status, only modified, added, removed (and perhaps, clean) make sense
	// XXX Need to specify whether copy targets are in added or not (@see Inspector#copied above)
	public static class Record implements Inspector {
		private List<String> modified, added, removed, clean, missing, unknown, ignored;
		private Map<String, String> copied;
		
		private int startRev, endRev;
		private StatusCollector statusHelper;
		
		// XXX StatusCollector may additionally initialize Record instance to speed lookup of changed file revisions
		// here I need access to ManifestRevisionInspector via #raw(). Perhaps, non-static class (to get
		// implicit reference to StatusCollector) may be better?
		// Since users may want to reuse Record instance we've once created (and initialized), we need to  
		// ensure functionality is correct for each/any call (#walk checks instanceof Record and fixes it up)
		// Perhaps, distinct helper (sc.getRevisionHelper().nodeid(fname)) would be better, just not clear
		// how to supply [start..end] values there easily
		/*package-local*/void init(int startRevision, int endRevision, StatusCollector self) {
			startRev = startRevision;
			endRev = endRevision;
			statusHelper = self;
		}
		
		public Nodeid nodeidBeforeChange(String fname) {
			if (statusHelper == null || startRev == BAD_REVISION) {
				return null;
			}
			if ((modified == null || !modified.contains(fname)) && (removed == null || !removed.contains(fname))) {
				return null;
			}
			return statusHelper.raw(startRev).nodeid(startRev, fname);
		}
		public Nodeid nodeidAfterChange(String fname) {
			if (statusHelper == null || endRev == BAD_REVISION) {
				return null;
			}
			if ((modified == null || !modified.contains(fname)) && (added == null || !added.contains(fname))) {
				return null;
			}
			return statusHelper.raw(endRev).nodeid(endRev, fname);
		}
		
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
			added(fnameAdded);
			copied.put(fnameAdded, fnameOrigin);
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

	// XXX in fact, indexed access brings more trouble than benefits, get rid of it? Distinct instance per revision is good enough
	public /*XXX private, actually. Made public unless repo.statusLocal finds better place*/ static final class ManifestRevisionInspector implements HgManifest.Inspector {
		private final HashMap<String, Nodeid>[] idsMap;
		private final HashMap<String, String>[] flagsMap;
		private final int baseRevision;
		private int r = -1; // cursor

		/**
		 * [minRev, maxRev]
		 * [-1,-1] also accepted (for fake empty instance)
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
