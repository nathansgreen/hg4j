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

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.tmatesoft.hg.core.Nodeid;

/**
 * Unmodifiable collection of revisions with handy set operations
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class RevisionSet {
	
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

	public RevisionSet roots() {
		throw Internals.notImplemented();
	}
	
	public RevisionSet heads() {
		throw Internals.notImplemented();
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
