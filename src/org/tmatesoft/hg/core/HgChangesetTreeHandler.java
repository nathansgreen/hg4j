/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
 * Handler to iterate file history (generally, any revlog) with access to parent-child relations between changesets.
 * 
 * @see HgLogCommand#execute(HgChangesetTreeHandler)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface HgChangesetTreeHandler {
	/**
	 * @param entry access to various pieces of information about current tree node. Instances might be 
	 * reused across calls and shall not be kept by client's code
	 * @throws HgException allows implementers propagate errors from {@link TreeElement} or other parts of the library.
	 * @throws HgCallbackTargetException.Wrap wrapper object for any exception user code may produce. Wrapped exception would get re-thrown with {@link HgCallbackTargetException} 
	 * @throws CancelledException if execution of the operation was cancelled
	 */
	public void next(HgChangesetTreeHandler.TreeElement entry) throws HgException, HgCallbackTargetException.Wrap, CancelledException;

	interface TreeElement {
		/**
		 * Revision of the revlog being iterated. For example, when walking file history, return value represents file revisions.
		 * 
		 * @return revision of the revlog being iterated.
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public Nodeid fileRevision() throws HgException;

		/**
		 * @return changeset associated with the current revision
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public HgChangeset changeset() throws HgException;

		/**
		 * Lightweight alternative to {@link #changeset()}, identifies changeset in which current file node has been modified 
		 * @return changeset {@link Nodeid revision} 
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public Nodeid changesetRevision() throws HgException;

		/**
		 * Node, these are not necessarily in direct relation to parents of changeset from {@link #changeset()} 
		 * @return changesets that correspond to parents of the current file node, either pair element may be <code>null</code>.
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public Pair<HgChangeset, HgChangeset> parents() throws HgException;
		
		/**
		 * Lightweight alternative to {@link #parents()}, give {@link Nodeid nodeids} only
		 * @return two values, neither is <code>null</code>, use {@link Nodeid#isNull()} to identify parent not set
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public Pair<Nodeid, Nodeid> parentRevisions() throws HgException;

		/**
		 * Changes that originate from the given change and bear it as their parent. 
		 * @return collection (possibly empty) of immediate children of the change
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public Collection<HgChangeset> children() throws HgException;

		/**
		 * Lightweight alternative to {@link #children()}.
		 * @return never <code>null</code>
		 * @throws HgException to indicate failure dealing with Mercurial data
		 */
		public Collection<Nodeid> childRevisions() throws HgException;
	}
}