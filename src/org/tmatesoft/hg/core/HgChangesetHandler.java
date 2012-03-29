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

import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.util.Path;

/**
 * Callback to process {@link HgChangeset changesets}.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Callback
public interface HgChangesetHandler {

	/**
	 * @param changeset descriptor of a change, not necessarily a distinct instance each time, {@link HgChangeset#clone() clone()} if need a copy.
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce 
	 */
	void cset(HgChangeset changeset) throws HgCallbackTargetException;


	/**
	 * When {@link HgLogCommand} is executed against file, handler passed to {@link HgLogCommand#execute(HgChangesetHandler)} may optionally
	 * implement this interface to get information about file renames. Method {@link #copy(HgFileRevision, HgFileRevision)} would
	 * get invoked prior any changeset of the original file (if file history being followed) is reported via {@link #cset(HgChangeset)}.
	 * 
	 * For {@link HgLogCommand#file(Path, boolean)} with renamed file path and follow argument set to false, 
	 * {@link #copy(HgFileRevision, HgFileRevision)} would be invoked for the first copy/rename in the history of the file, but not 
	 * followed by any changesets. 
	 */
	@Callback
	public interface WithCopyHistory extends HgChangesetHandler {
		// XXX perhaps, should distinguish copy from rename? And what about merged revisions and following them?

		/**
		 * @throws HgCallbackTargetException wrapper object for any exception user code may produce 
		 */
		void copy(HgFileRevision from, HgFileRevision to) throws HgCallbackTargetException;
	}
}
