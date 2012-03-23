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
package org.tmatesoft.hg.repo;

import org.tmatesoft.hg.core.Nodeid;

/**
 * Use of revision or revision local index that is not valid for a given revlog.
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgInvalidRevisionException extends HgRuntimeException {

	/**
	 * 
	 * This exception is not expected to be initialized with another exception, although those who need to, 
	 * may still use {@link #initCause(Throwable)}
	 * 
	 * @param message optional description of the issue
	 * @param revision invalid revision, may be  <code>null</code> if revisionIndex is used
	 * @param revisionIndex invalid revision index, may be <code>null</code> if not known and revision is supplied 
	 */
	public HgInvalidRevisionException(String message, Nodeid revision, Integer revisionIndex) {
		super(message, null);
		assert revision != null || revisionIndex != null;
		if (revision != null) {
			setRevision(revision);
		}
		if (revisionIndex != null) {
			setRevisionIndex(revisionIndex);
		}
	}

	public HgInvalidRevisionException(Nodeid revision) {
		this(null, revision, null);
	}
	
	public HgInvalidRevisionException(int revisionIndex) {
		this(null, null, revisionIndex);
	}

	public HgInvalidRevisionException setRevisionIndex(int revisionIndex, int rangeLeft, int rangeRight) {
		details.setRevisionIndexBoundary(revisionIndex, rangeLeft, rangeRight);
		return this;
	}
}
