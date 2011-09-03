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

import static org.tmatesoft.hg.core.Nodeid.NULL;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathPool;
import org.tmatesoft.hg.util.PathRewrite;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgMergeState {
	private Nodeid wcp1, wcp2, stateParent;
	
	public enum Kind {
		Resolved, Unresolved;
	}
	
	public static class Entry {
		private final Kind state;
		private final HgFileRevision parent1;
		private final HgFileRevision parent2;
		private final HgFileRevision ancestor;
		private final Path wcFile;

		/*package-local*/Entry(Kind s, Path actualCopy, HgFileRevision p1, HgFileRevision p2, HgFileRevision ca) {
			if (p1 == null || p2 == null || ca == null || actualCopy == null) {
				throw new IllegalArgumentException();
			}
			state = s;
			wcFile = actualCopy;
			parent1 = p1;
			parent2 = p2;
			ancestor = ca;
		}
		
		public Kind getState() {
			return state;
		}
		public Path getActualFile() {
			return wcFile;
		}
		public HgFileRevision getFirstParent() {
			return parent1;
		}
		public HgFileRevision getSecondParent() {
			return parent2;
		}
		public HgFileRevision getCommonAncestor() {
			return ancestor;
		}
	}

	private final HgRepository repo;
	private Entry[] entries;

	HgMergeState(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public void refresh() throws IOException/*XXX it's unlikely caller can do anything reasonable about IOException */ {
		entries = null;
		final File f = new File(repo.getRepositoryRoot(), "merge/state");
		if (!f.canRead()) {
			// empty state
			return;
		}
		Pool<Nodeid> nodeidPool = new Pool<Nodeid>();
		Pool<String> fnamePool = new Pool<String>();
		Pair<Nodeid, Nodeid> wcParents = repo.getWorkingCopyParents();
		wcp1 = nodeidPool.unify(wcParents.first()); wcp2 = nodeidPool.unify(wcParents.second());
		ArrayList<Entry> result = new ArrayList<Entry>();
		PathPool pathPool = new PathPool(new PathRewrite.Empty());
		final ManifestRevision m1 = new ManifestRevision(nodeidPool, fnamePool);
		final ManifestRevision m2 = new ManifestRevision(nodeidPool, fnamePool);
		if (!wcp2.isNull()) {
			final int rp2 = repo.getChangelog().getLocalRevision(wcp2);
			repo.getManifest().walk(rp2, rp2, m2);
		}
		BufferedReader br = new BufferedReader(new FileReader(f));
		String s = br.readLine();
		stateParent = nodeidPool.unify(Nodeid.fromAscii(s));
		final int rp1 = repo.getChangelog().getLocalRevision(stateParent);
		repo.getManifest().walk(rp1, rp1, m1);
		while ((s = br.readLine()) != null) {
			String[] r = s.split("\\00");
			Nodeid nidP1 = m1.nodeid(r[3]);
			Nodeid nidCA = nodeidPool.unify(Nodeid.fromAscii(r[5]));
			HgFileRevision p1 = new HgFileRevision(repo, nidP1, pathPool.path(r[3]));
			HgFileRevision ca;
			if (nidCA == nidP1 && r[3].equals(r[4])) {
				ca = p1;
			} else {
				ca = new HgFileRevision(repo, nidCA, pathPool.path(r[4]));
			}
			HgFileRevision p2;
			if (!wcp2.isNull() || !r[6].equals(r[4])) {
				Nodeid nidP2 = m2.nodeid(r[6]);
				if (nidP2 == null) {
					assert false : "There's not enough information (or I don't know where to look) in merge/state to find out what's the second parent";
					nidP2 = NULL;
				}
				p2 = new HgFileRevision(repo, nidP2, pathPool.path(r[6]));
			} else {
				// no second parent known. no idea what to do here, assume linear merge, use common ancestor as parent
				p2 = ca;
			}
			final Kind k;
			if ("u".equals(r[1])) {
				k = Kind.Unresolved;
			} else if ("r".equals(r[1])) {
				k = Kind.Resolved;
			} else {
				throw new HgBadStateException(r[1]);
			}
			Entry e = new Entry(k, pathPool.path(r[0]), p1, p2, ca);
			result.add(e);
		}
		entries = result.toArray(new Entry[result.size()]);
		br.close();
		pathPool.clear();
	}

	
	public boolean isMerging() {
		return !getFirstParent().isNull() && !getSecondParent().isNull() && !isStale();
	}
	
	/**
	 * @return <code>true</code> when recorded merge state doesn't seem to correspond to present working copy
	 */
	public boolean isStale() {
		if (wcp1 == null) {
			throw new HgBadStateException("Call #refresh() first");
		}
		return !wcp1.equals(stateParent); 
	}
	
	public Nodeid getFirstParent() {
		if (wcp1 == null) {
			throw new HgBadStateException("Call #refresh() first");
		}
		return wcp1;
	}
	
	public Nodeid getSecondParent() {
		if (wcp2 == null) {
			throw new HgBadStateException("Call #refresh() first");
		}
		return wcp2;
	}
	
	public Nodeid getStateParent() {
		if (stateParent == null) {
			throw new HgBadStateException("Call #refresh() first");
		}
		return stateParent;
	}

	public List<Entry> getConflicts() {
		return entries == null ? Collections.<Entry>emptyList() : Arrays.asList(entries);
	}
}
