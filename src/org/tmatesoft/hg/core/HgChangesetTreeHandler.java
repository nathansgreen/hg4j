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
package org.tmatesoft.hg.core;

import java.util.Collection;

import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Pair;

/**
 * Handler to iterate file history (generally, any revlog) with access to parent-child relations between changesets.
 * 
 * @see HgLogCommand#execute(HgChangesetTreeHandler)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Callback
public interface HgChangesetTreeHandler {
	/**
	 * @param entry access to various pieces of information about current tree node. Instances might be 
	 * reused across calls and shall not be kept by client's code
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce 
	 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
	 */
	public void treeElement(HgChangesetTreeHandler.TreeElement entry) throws HgCallbackTargetException, HgRuntimeException;

	interface TreeElement {
		/**
		 * Revision of the revlog being iterated. For example, when walking file history, return value represents file revisions.
		 * 
		 * @return revision of the revlog being iterated.
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public Nodeid fileRevision() throws HgRuntimeException;
		
		/**
		 * File node, provided revlog being iterated is a {@link HgDataFile}; {@link #fileRevision()} 
		 * references revision from the history of this very {@link HgDataFile file}.
		 * 
		 * Comes handy when file history with renames is being followed to find out 
		 * file name for particular revision in the history.
		 * 
		 * @return instance of the file being walked, or <code>null</code> if it's not a file but other revlog.
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public HgDataFile file() throws HgRuntimeException;

		/**
		 * @return changeset associated with the current file revision
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public HgChangeset changeset() throws HgRuntimeException;

		/**
		 * Lightweight alternative to {@link #changeset()}, identifies changeset in which current file node has been modified 
		 * @return changeset {@link Nodeid revision} 
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public Nodeid changesetRevision() throws HgRuntimeException;

		/**
		 * Identifies parent changes, changesets where file/revlog in question was modified prior to change being visited.
		 * 
		 * Note, these are not necessarily in direct relation to parents of changeset from {@link #changeset()}
		 * 
		 * Imagine next history (grows from bottom to top):
		 * <pre>
		 * o A    o
		 * |   \  |
		 * o B  \/
		 * |    o C
		 * |   /
		 * o  /
		 * | /
		 * o D
		 * </pre>
		 * 
		 * When we are at {@link TreeElement} for <code>A</code>, <code>B</code> and <code>C</code> are changeset parents, naturally. However
		 * if the file/revlog we've been walking has not been changed in <code>B</code> and <code>C</code>, but e.g. in <code>D</code> only,
		 * then this {@link #parents()} call would return pair with single element only, pointing to <code>D</code>
		 * 
		 * @return changesets that correspond to parents of the current file node, either pair element may be <code>null</code>.
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public Pair<HgChangeset, HgChangeset> parents() throws HgRuntimeException;
		
		/**
		 * Lightweight alternative to {@link #parents()}, give {@link Nodeid nodeids} only
		 * @return two values, neither is <code>null</code>, use {@link Nodeid#isNull()} to identify parent not set
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public Pair<Nodeid, Nodeid> parentRevisions() throws HgRuntimeException;

		/**
		 * Changes that originate from the given change and bear it as their parent. 
		 * @return collection (possibly empty) of immediate children of the change
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public Collection<HgChangeset> children() throws HgRuntimeException;

		/**
		 * Lightweight alternative to {@link #children()}.
		 * @return never <code>null</code>
		 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
		 */
		public Collection<Nodeid> childRevisions() throws HgRuntimeException;
	}
}