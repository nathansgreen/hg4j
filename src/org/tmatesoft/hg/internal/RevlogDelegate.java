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

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.RevlogStream.Inspector;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * Does almost nothing, facilitates chains of inspectors and sharing certain information between them 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public abstract class RevlogDelegate implements RevlogStream.Inspector {
	
	private final Inspector next;
	private Nodeid nid;
	private final RevlogDelegate nextAsRD;
	
	protected RevlogDelegate(RevlogStream.Inspector nextInChain) {
		next = nextInChain;
		if (nextInChain instanceof RevlogDelegate) {
			// additional benefits
			nextAsRD = (RevlogDelegate) nextInChain;
		} else {
			nextAsRD = null;
		}
	}

	/**
	 * iterates index only and ensures pre/post processing is invoked for the chain
	 */
	public void walk(HgRepository hgRepo, RevlogStream stream, int from, int to) {
		// hgRepo is handy for present uses, but is generally not appropriate, 
		// it's ok to refactor and let subclasses get what they need through e.g. a cons 
		stream.iterate(from, to, false, this);
		postWalk(hgRepo);
	}
	
	// does nothing but gives a chance to delegate to handle the same
	protected void postWalk(HgRepository hgRepo) {
		if (nextAsRD != null) {
			nextAsRD.postWalk(hgRepo);
		}
	}

	/**
	 * @return Nodeid of current revision if already known, or a new instance otherwise. 
	 * The value will propagate to subsequent {@link RevlogDelegate RevlogDelegates}, if any
	 */
	protected Nodeid getRevision(byte[] nodeid) {
		if (nid == null) {
			nid = Nodeid.fromBinary(nodeid, 0);
		}
		return nid;
	}
	
	protected void setRevision(Nodeid nodeid) {
		nid = nodeid;
	}
	
	public void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) throws HgRuntimeException {
		if (next != null) {
			if (nextAsRD != null) {
				nextAsRD.setRevision(nid); // null is fine
			}
			next.next(revisionIndex, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, nodeid, data);
		}
		nid = null; // forget it, get ready for the next iteration
	}
}
