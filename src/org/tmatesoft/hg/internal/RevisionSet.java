/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgParentChildMap;

/**
 * Unmodifiable collection of revisions with handy set operations
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class RevisionSet implements Iterable<Nodeid> {
	
	private final Set<Nodeid> elements;
	
	public RevisionSet(Collection<Nodeid> revisions) {
		this(revisions == null ? new HashSet<Nodeid>() : new HashSet<Nodeid>(revisions));
	}
	
	private RevisionSet(HashSet<Nodeid> revisions) {
		if (revisions.isEmpty()) {
			elements = Collections.<Nodeid>emptySet();
		} else {
			elements = revisions;
		}
	}

	/**
	 * elements of the set with no parents or parents not from the same set 
	 */
	public RevisionSet roots(HgParentChildMap<HgChangelog> ph) {
		HashSet<Nodeid> copy = new HashSet<Nodeid>(elements);
		for (Nodeid n : elements) {
			assert ph.knownNode(n);
			Nodeid p1 = ph.firstParent(n);
			if (p1 != null && elements.contains(p1)) {
				copy.remove(n);
				continue;
			}
			Nodeid p2 = ph.secondParent(n);
			if (p2 != null && elements.contains(p2)) {
				copy.remove(n);
				continue;
			}
		}
		return copy.size() == elements.size() ? this : new RevisionSet(copy);
	}
	
	/**
	 * elements of the set that has no children in this set 
	 */
	public RevisionSet heads(HgParentChildMap<HgChangelog> ph) {
		HashSet<Nodeid> copy = new HashSet<Nodeid>(elements);
		// can't do copy.removeAll(ph.childrenOf(asList())); as actual heads are indeed children of some other node
		for (Nodeid n : elements) {
			assert ph.knownNode(n);
			Nodeid p1 = ph.firstParent(n);
			Nodeid p2 = ph.secondParent(n);
			if (p1 != null && elements.contains(p1)) {
				copy.remove(p1);
			}
			if (p2 != null && elements.contains(p2)) {
				copy.remove(p2);
			}
		}
		return copy.size() == elements.size() ? this : new RevisionSet(copy);
	}

	/**
	 * Any ancestor of an element from the supplied children set found in this one.
	 */
	public RevisionSet ancestors(RevisionSet children, HgParentChildMap<HgChangelog> parentHelper) {
		if (isEmpty()) {
			return this;
		}
		if (children.isEmpty()) {
			return children;
		}
		RevisionSet chRoots = children.roots(parentHelper);
		HashSet<Nodeid> ancestors = new HashSet<Nodeid>();
		Set<Nodeid> childrenToCheck = chRoots.elements;
		while (!childrenToCheck.isEmpty()) {
			HashSet<Nodeid> nextRound = new HashSet<Nodeid>();
			for (Nodeid n : childrenToCheck) {
				Nodeid p1 = parentHelper.firstParent(n);
				Nodeid p2 = parentHelper.secondParent(n);
				if (p1 != null && elements.contains(p1)) {
					nextRound.add(p1);
				}
				if (p2 != null && elements.contains(p2)) {
					nextRound.add(p2);
				}
			}
			ancestors.addAll(nextRound);
			childrenToCheck = nextRound;
		} 
		return new RevisionSet(ancestors);
	}
	
	/**
	 * Revisions that are both direct and indirect children of elements of this revision set
	 * as known in supplied parent-child map
	 */
	public RevisionSet children(HgParentChildMap<HgChangelog> parentHelper) {
		if (isEmpty()) {
			return this;
		}
		List<Nodeid> children = parentHelper.childrenOf(elements);
		return new RevisionSet(new HashSet<Nodeid>(children));
	}

	public RevisionSet intersect(RevisionSet other) {
		if (isEmpty()) {
			return this;
		}
		if (other.isEmpty()) {
			return other;
		}
		HashSet<Nodeid> copy = new HashSet<Nodeid>(elements);
		copy.retainAll(other.elements);
		return copy.size() == elements.size() ? this : new RevisionSet(copy);
	}
	
	public RevisionSet subtract(RevisionSet other) {
		if (isEmpty() || other.isEmpty()) {
			return this;
		}
		HashSet<Nodeid> copy = new HashSet<Nodeid>(elements);
		copy.removeAll(other.elements);
		return copy.size() == elements.size() ? this : new RevisionSet(copy);
	}

	public RevisionSet union(RevisionSet other) {
		if (isEmpty()) {
			return other;
		}
		if (other.isEmpty()) {
			return this;
		}
		HashSet<Nodeid> copy = new HashSet<Nodeid>(elements);
		copy.addAll(other.elements);
		return new RevisionSet(copy);
	}

	/**
	 * A ^ B := (A\B).union(B\A)
	 * A ^ B := A.union(B) \ A.intersect(B)
	 */
	public RevisionSet symmetricDifference(RevisionSet other) {
		if (isEmpty()) {
			return this;
		}
		if (other.isEmpty()) {
			return other;
		}
		HashSet<Nodeid> copyA = new HashSet<Nodeid>(elements);
		HashSet<Nodeid> copyB = new HashSet<Nodeid>(other.elements);
		copyA.removeAll(other.elements);
		copyB.removeAll(elements);
		copyA.addAll(copyB);
		return new RevisionSet(copyA);
	}

	public boolean isEmpty() {
		return elements.isEmpty();
	}


	public List<Nodeid> asList() {
		return new ArrayList<Nodeid>(elements);
	}
	
	public Iterator<Nodeid> iterator() {
		return elements.iterator();
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append('<');
		if (!isEmpty()) {
			sb.append(elements.size());
			sb.append(':');
		}
		for (Nodeid n : elements) {
			sb.append(n.shortNotation());
			sb.append(',');
		}
		if (sb.length() > 1) {
			sb.setCharAt(sb.length() - 1, '>');
		} else {
			sb.append('>');
		}
		return sb.toString();
	}
	
	@Override
	public boolean equals(Object obj) {
		if (false == obj instanceof RevisionSet) {
			return false;
		}
		return elements.equals(((RevisionSet) obj).elements);
	}
	
	@Override
	public int hashCode() {
		return elements.hashCode();
	}
}
