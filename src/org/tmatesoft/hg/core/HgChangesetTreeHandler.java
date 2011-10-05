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
package org.tmatesoft.hg.core;

import java.util.Collection;

import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface HgChangesetTreeHandler {
	/**
	 * @param entry access to various pieces of information about current tree node. Instances might be 
	 * reused across calls and shall not be kept by client's code 
	 */
	public void next(HgChangesetTreeHandler.TreeElement entry) throws CancelledException;

	interface TreeElement {
		/**
		 * Revision of the revlog being iterated. For example, when walking file history, return value represents file revisions.
		 * 
		 * @return revision of the revlog being iterated.
		 */
		public Nodeid fileRevision();
		/**
		 * @return changeset associated with the current revision
		 */
		public HgChangeset changeset();

		/**
		 * Lightweight alternative to {@link #changeset()}, identifies changeset in which current file node has been modified 
		 * @return changeset {@link Nodeid} 
		 */
		public Nodeid changesetRevision();

		/**
		 * Node, these are not necessarily in direct relation to parents of changeset from {@link #changeset()} 
		 * @return changesets that correspond to parents of the current file node, either pair element may be <code>null</code>.
		 */
		public Pair<HgChangeset, HgChangeset> parents();
		
		/**
		 * Lightweight alternative to {@link #parents()}, give {@link Nodeid nodeids} only
		 * @return two values, neither is <code>null</code>, use {@link Nodeid#isNull()} to identify parent not set
		 */
		public Pair<Nodeid, Nodeid> parentRevisions();

		public Collection<HgChangeset> children();

		/**
		 * Lightweight alternative to {@link #children()}.
		 * @return never <code>null</code>
		 */
		public Collection<Nodeid> childRevisions();
	}
}