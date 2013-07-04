/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.repo.Revlog.ParentInspector;

/**
 * Helper class to deal with parent-child relationship between revisions <i>en masse</i>.
 * Works in terms of {@link Nodeid nodeids}, there's no need to deal with revision indexes. 
 * For a given revision, answers questions like "who's my parent and what are my immediate children".
 * 
 * <p>Comes handy when multiple revisions are analyzed and distinct {@link Revlog#parents(int, int[], byte[], byte[])} 
 * queries are ineffective. 
 * 
 * <p>Next code snippet shows typical use: 
 * <pre>
 *   HgChangelog clog = repo.getChangelog();
 *   ParentWalker&lt;HgChangelog> pw = new ParentWalker&lt;HgChangelog>(clog);
 *   pw.init();
 *   
 *   Nodeid me = Nodeid.fromAscii("...");
 *   List<Nodei> immediateChildren = pw.directChildren(me);
 * </pre>
 * 
 * 
 * <p> Perhaps, later may add alternative way to access (and reuse) map instance, Revlog#getParentWalker(), 
 * that instantiates and initializes ParentWalker, and keep SoftReference to allow its reuse.
 * 
 * @see HgRevisionMap
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgParentChildMap<T extends Revlog> implements ParentInspector {

	// IMPORTANT: Nodeid instances shall be shared between all arrays

	private final T revlog;
	private Nodeid[] sequential; // natural repository order, childrenOf rely on ordering
	private Nodeid[] sorted; // for binary search, just an origin of the actual value in use, the one inside seqWrapper
	private Nodeid[] firstParent; // parents by natural order (i.e. firstParent[A] is parent of revision with index A)
	private Nodeid[] secondParent;
	private IntMap<Nodeid> heads;
	private BitSet headsBitSet; // 1 indicates revision got children, != null only during init;
	private HgRevisionMap<T> revisionIndexMap;
	private ArrayHelper<Nodeid> seqWrapper; 


	public HgParentChildMap(T owner) {
		revlog = owner;
	}
	
	public HgRepository getRepo() {
		return revlog.getRepo();
	}
	
	public void next(int revisionNumber, Nodeid revision, int parent1Revision, int parent2Revision, Nodeid nidParent1, Nodeid nidParent2) {
		if (parent1Revision >= revisionNumber || parent2Revision >= revisionNumber) {
			throw new IllegalStateException(); // sanity, revisions are sequential
		}
		int ix = revisionNumber;
		sequential[ix] = sorted[ix] = revision;
		if (parent1Revision != -1) {
			firstParent[ix] = sequential[parent1Revision];
			headsBitSet.set(parent1Revision);
		}
		if (parent2Revision != -1) { // revlog of DataAccess.java has p2 set when p1 is -1
			secondParent[ix] = sequential[parent2Revision];
			headsBitSet.set(parent2Revision);
		}
	}
	
	/**
	 * Prepare the map 
	 * @throws HgInvalidControlFileException if failed to access revlog index/data entry. <em>Runtime exception</em>
	 * @throws HgRuntimeException subclass thereof to indicate other issues with the library. <em>Runtime exception</em>
	 */
	public void init() throws HgRuntimeException {
		final int revisionCount = revlog.getRevisionCount();
		firstParent = new Nodeid[revisionCount];
		// TODO [post 1.1] Branches/merges are less frequent, and most of secondParent would be -1/null, hence 
		// IntMap might be better alternative here, but need to carefully analyze (test) whether this brings
		// real improvement (IntMap has 2n capacity, and element lookup is log(n) instead of array's constant).
		// FWIW: in cpython's repo, with 70k+ revisions, there are 2618 values in secondParent 
		secondParent = new Nodeid[revisionCount];
		//
		sequential = new Nodeid[revisionCount];
		sorted = new Nodeid[revisionCount]; 
		headsBitSet = new BitSet(revisionCount);
		revlog.indexWalk(0, TIP, this);
		seqWrapper = new ArrayHelper<Nodeid>(sequential);
		// HgRevisionMap doesn't keep sorted, try alternative here.
		// reference this.sorted (not only from ArrayHelper) helps to track ownership in hprof/mem dumps
		seqWrapper.sort(sorted, false, true);
		// no reason to keep BitSet, number of heads is usually small
		IntMap<Nodeid> _heads = new IntMap<Nodeid>(headsBitSet.size() - headsBitSet.cardinality());
		int index = 0;
		while (index < sequential.length) {
			index = headsBitSet.nextClearBit(index);
			// nextClearBit(length-1) gives length when bit is set,
			// however, last revision can't be a parent of any other, and
			// the last bit would be always 0, and no AIOOBE 
			_heads.put(index, sequential[index]);
			index++;
		} 
		headsBitSet = null;
		heads = _heads;
	}
	
	private void assertSortedIndex(int x) {
		if (x < 0) {
			throw new HgInvalidStateException(String.format("Bad index", x));
		}
	}
	
	/**
	 * Tells whether supplied revision is from the walker's associated revlog.
	 * Note, {@link Nodeid#NULL}, although implicitly present as parent of a first revision, is not recognized as known. 
	 * @param nid revision to check, not <code>null</code>
	 * @return <code>true</code> if revision matches any revision in this revlog
	 */
	public boolean knownNode(Nodeid nid) {
		return seqWrapper.binarySearchSorted(nid) >= 0;
	}

	/**
	 * null if none. only known nodes (as per #knownNode) are accepted as arguments
	 */
	public Nodeid firstParent(Nodeid nid) {
		int x = seqWrapper.binarySearchSorted(nid);
		assertSortedIndex(x);
		int i = seqWrapper.getReverseIndex(x);
		return firstParent[i];
	}

	// never null, Nodeid.NULL if none known
	public Nodeid safeFirstParent(Nodeid nid) {
		Nodeid rv = firstParent(nid);
		return rv == null ? Nodeid.NULL : rv;
	}
	
	public Nodeid secondParent(Nodeid nid) {
		int x = seqWrapper.binarySearchSorted(nid);
		assertSortedIndex(x);
		int i = seqWrapper.getReverseIndex(x);
		return secondParent[i];
	}

	public Nodeid safeSecondParent(Nodeid nid) {
		Nodeid rv = secondParent(nid);
		return rv == null ? Nodeid.NULL : rv;
	}

	public boolean appendParentsOf(Nodeid nid, Collection<Nodeid> c) {
		int x = seqWrapper.binarySearchSorted(nid);
		assertSortedIndex(x);
		int i = seqWrapper.getReverseIndex(x);
		Nodeid p1 = firstParent[i];
		boolean modified = false;
		if (p1 != null) {
			modified = c.add(p1);
		}
		Nodeid p2 = secondParent[i];
		if (p2 != null) {
			modified = c.add(p2) || modified;
		}
		return modified;
	}

	// XXX alternative (and perhaps more reliable) approach would be to make a copy of allNodes and remove 
	// nodes, their parents and so on.
	
	// @return ordered collection of all children rooted at supplied nodes. Nodes shall not be descendants of each other!
	// Nodeids shall belong to this revlog
	public List<Nodeid> childrenOf(Collection<Nodeid> roots) {
		if (roots.isEmpty()) {
			return Collections.emptyList();
		}
		HashSet<Nodeid> parents = new HashSet<Nodeid>();
		LinkedList<Nodeid> result = new LinkedList<Nodeid>();
		int earliestRevision = Integer.MAX_VALUE;
		assert sequential.length == firstParent.length && firstParent.length == secondParent.length;
		// first, find earliest index of roots in question, as there's  no sense 
		// to check children among nodes prior to branch's root node
		for (Nodeid r : roots) {
			int x = seqWrapper.binarySearchSorted(r);
			assertSortedIndex(x);
			int i = seqWrapper.getReverseIndex(x);
			if (i < earliestRevision) {
				earliestRevision = i;
			}
			parents.add(sequential[i]); // add canonical instance in hope equals() is bit faster when can do a ==
		}
		for (int i = earliestRevision + 1; i < sequential.length; i++) {
			if (parents.contains(firstParent[i]) || parents.contains(secondParent[i])) {
				parents.add(sequential[i]); // to find next child
				result.add(sequential[i]);
			}
		}
		return result;
	}
	
	/**
	 * @return revisions that have supplied revision as their immediate parent
	 */
	public List<Nodeid> directChildren(Nodeid nid) {
		int x = seqWrapper.binarySearchSorted(nid);
		assertSortedIndex(x);
		int start = seqWrapper.getReverseIndex(x);
		nid = sequential[start]; // canonical instance
		if (!hasChildren(start)) {
			return Collections.emptyList();
		}
		ArrayList<Nodeid> result = new ArrayList<Nodeid>(5);
		for (int i = start + 1; i < sequential.length; i++) {
			if (nid == firstParent[i] || nid == secondParent[i]) {
				result.add(sequential[i]);
			}
		}
		return result;
	}
	
	/**
	 * @param nid possibly parent node, shall be {@link #knownNode(Nodeid) known} in this revlog.
	 * @return <code>true</code> if there's any node in this revlog that has specified node as one of its parents. 
	 */
	public boolean hasChildren(Nodeid nid) {
		int x = seqWrapper.binarySearchSorted(nid);
		assertSortedIndex(x);
		int i = seqWrapper.getReverseIndex(x);
		return hasChildren(i);
	}

	/**
	 * @return all revisions this map knows about
	 */
	public List<Nodeid> all() {
		return Arrays.asList(sequential);
	}

	/**
	 * Find out whether a given node is among descendants of another.
	 * 
	 * @param root revision to check for being (grand-)*parent of a child
	 * @param wannaBeChild candidate descendant revision
	 * @return <code>true</code> if <code>wannaBeChild</code> is among children of <code>root</code>
	 */
	public boolean isChild(Nodeid root, Nodeid wannaBeChild) {
		int x = seqWrapper.binarySearchSorted(root);
		assertSortedIndex(x);
		final int start = seqWrapper.getReverseIndex(x);
		root = sequential[start]; // canonical instance
		if (!hasChildren(start)) {
			return false; // root got no children at all
		}
		int y = seqWrapper.binarySearchSorted(wannaBeChild);
		if (y < 0) {
			return false; // not found
		}
		final int end = seqWrapper.getReverseIndex(y);
		wannaBeChild = sequential[end]; // canonicalize
		if (end <= start) {
			return false; // potential child was in repository earlier than root
		}
		HashSet<Nodeid> parents = new HashSet<Nodeid>();
		parents.add(root);
		for (int i = start + 1; i < end; i++) {
			if (parents.contains(firstParent[i]) || parents.contains(secondParent[i])) {
				parents.add(sequential[i]); // collect ancestors line
			}
		}
		return parents.contains(firstParent[end]) || parents.contains(secondParent[end]);
	}
	
	/**
	 * @return elements of this map that do not have a child recorded therein.
	 */
	public Collection<Nodeid> heads() {
		return heads.values();
	}
	
	/**
	 * @return map of revision to indexes
	 */
	public HgRevisionMap<T> getRevisionMap() {
		if (revisionIndexMap == null) {
			revisionIndexMap = new HgRevisionMap<T>(revlog);
			revisionIndexMap.init(seqWrapper);
		}
		return revisionIndexMap;
	}

	private boolean hasChildren(int sequentialIndex) {
		return !heads.containsKey(sequentialIndex);
	}
}