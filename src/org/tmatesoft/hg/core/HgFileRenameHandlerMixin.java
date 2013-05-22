/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Adaptable;

/**
 * Addition to file history handlers (like {@link HgChangesetHandler} and {@link HgChangesetTreeHandler}) 
 * to receive notification about rename in the history of the file being walked.
 * 
 * This mix-in shall be available from the host handler through the {@link Adaptable} mechanism, see
 * {@link Adaptable.Factory#getAdapter(Object, Class, Object)}. Hence, implementing 
 * this interface in addition to host's would be the easiest way to achieve that.
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface HgFileRenameHandlerMixin {
	// XXX perhaps, should distinguish copy from rename? And what about merged revisions and following them?

	/**
	 * @throws HgCallbackTargetException wrapper object for any exception user code may produce 
	 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
	 */
	void copy(HgFileRevision from, HgFileRevision to) throws HgCallbackTargetException, HgRuntimeException;
}
