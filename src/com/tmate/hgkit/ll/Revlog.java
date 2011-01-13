/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author artem
 */
public abstract class Revlog {

	private final HgRepository hgRepo;
	protected final RevlogStream content;

	protected Revlog(HgRepository hgRepo, RevlogStream content) {
		if (hgRepo == null) {
			throw new NullPointerException();
		}
		this.hgRepo = hgRepo;
		this.content = content;
	}

	public final HgRepository getRepo() {
		return hgRepo;
	}

	public int getRevisionCount() {
		return content.revisionCount();
	}

	/**
	 * Access to revision data as is (decompressed, but otherwise unprocessed, i.e. not parsed for e.g. changeset or manifest entries) 
	 * @param nodeid
	 */
	public byte[] content(Nodeid nodeid) {
		int revision = content.findLocalRevisionNumber(nodeid);
		return content(revision);
	}
	
	/**
	 * @param revision - repo-local index of this file change (not a changelog revision number!)
	 */
	public byte[] content(int revision) {
		final byte[][] dataPtr = new byte[1][];
		Revlog.Inspector insp = new Revlog.Inspector() {
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				dataPtr[0] = data;
			}
		};
		content.iterate(revision, revision, true, insp);
		return dataPtr[0];
	}

	// FIXME byte[] data might be too expensive, for few usecases it may be better to have intermediate Access object (when we don't need full data 
	// instantly - e.g. calculate hash, or comparing two revisions
	// XXX seems that RevlogStream is better place for this class. 
	public interface Inspector {
		// XXX boolean retVal to indicate whether to continue?
		// TODO specify nodeid and data length, and reuse policy (i.e. if revlog stream doesn't reuse nodeid[] for each call) 
		void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[/*20*/] nodeid, byte[] data);
	}

	/*
	 * XXX think over if it's better to do either:
	 * pw = getChangelog().new ParentWalker(); pw.init() and pass pw instance around as needed
	 * or
	 * add Revlog#getParentWalker(), static class, make cons() and #init package-local, and keep SoftReference to allow walker reuse.
	 * 
	 *  and yes, walker is not a proper name
	 */
	public final class ParentWalker {
		private Map<Nodeid, Nodeid> firstParent;
		private Map<Nodeid, Nodeid> secondParent;
		private Set<Nodeid> allNodes;

		public ParentWalker() {
			firstParent = secondParent = Collections.emptyMap();
			allNodes = Collections.emptySet();
		}
		
		public void init() {
			final RevlogStream stream = Revlog.this.content;
			final int revisionCount = stream.revisionCount();
			firstParent = new HashMap<Nodeid, Nodeid>(revisionCount);
			secondParent = new HashMap<Nodeid, Nodeid>(firstParent.size() >> 1); // assume branches/merges are less frequent
			allNodes = new LinkedHashSet<Nodeid>();
			
			Inspector insp = new Inspector() {
				final Nodeid[] sequentialRevisionNodeids = new Nodeid[revisionCount];
				int ix = 0;
				public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
					if (ix != revisionNumber) {
						// XXX temp code, just to make sure I understand what's going on here
						throw new IllegalStateException();
					}
					if (parent1Revision >= revisionNumber || parent2Revision >= revisionNumber) {
						throw new IllegalStateException(); // sanity, revisions are sequential
					}
					final Nodeid nid = new Nodeid(nodeid, true);
					sequentialRevisionNodeids[ix++] = nid;
					allNodes.add(nid);
					if (parent1Revision != -1) {
						firstParent.put(nid, sequentialRevisionNodeids[parent1Revision]);
						if (parent2Revision != -1) {
							secondParent.put(nid, sequentialRevisionNodeids[parent2Revision]);
						}
					}
				}
			};
			stream.iterate(0, -1, false, insp);
		}
		
		public Set<Nodeid> allNodes() {
			return Collections.unmodifiableSet(allNodes);
		}
		
		// FIXME need to decide whether Nodeid(00 * 20) is always known or not
		public boolean knownNode(Nodeid nid) {
			return allNodes.contains(nid);
		}

		// null if none
		public Nodeid firstParent(Nodeid nid) {
			return firstParent.get(nid);
		}
		
		public Nodeid secondParent(Nodeid nid) {
			return secondParent.get(nid);
		}

		public boolean appendParentsOf(Nodeid nid, Collection<Nodeid> c) {
			Nodeid p1 = firstParent(nid);
			boolean modified = false;
			if (p1 != null) {
				modified = c.add(p1);
				Nodeid p2 = secondParent(nid);
				if (p2 != null) {
					modified = c.add(p2) || modified;
				}
			}
			return modified;
		}
	}
}
