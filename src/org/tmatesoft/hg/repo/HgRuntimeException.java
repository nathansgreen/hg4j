/*
 * Copyright (c) 2012 TMate Software Ltd
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

import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgLibraryFailureException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ExceptionInfo;
import org.tmatesoft.hg.util.Path;

/**
 * Almost any method in <b>Hg4J</b> low-level API (@link org.tmatesoft.hg.repo} may throw subclass of this exception 
 * to indicate unexpected state/condition encountered, flawed data or IO error. 
 * Since most cases can't be handled in a reasonable manner (other than catch all exceptions and tell client 
 * something went wrong), and propagating all possible exceptions up through API is dubious task, low-level 
 * exceptions are made runtime, rooting at this single class.
 * 
 * <p>Hi-level api, {@link org.tmatesoft.hg.core}, where interaction with user-supplied values is more explicit,
 * follows different exception strategy, namely checked exceptions rooted at {@link HgException}.
 * 
 * @see HgException
 * @see HgLibraryFailureException
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public abstract class HgRuntimeException extends RuntimeException {

	protected final ExceptionInfo<HgRuntimeException> details = new ExceptionInfo<HgRuntimeException>(this);
	
	protected HgRuntimeException(String reason, Throwable cause) {
		super(reason, cause);
	}

	/**
	 * @return {@link HgRepository#BAD_REVISION} unless revision index was set during exception instantiation
	 */
	public int getRevisionIndex() {
		return details.getRevisionIndex();
	}

	public HgRuntimeException setRevisionIndex(int rev) {
		return details.setRevisionIndex(rev);
	}
	
	public boolean isRevisionIndexSet() {
		return details.isRevisionIndexSet();
	}


	/**
	 * @return non-<code>null</code> when revision was supplied at construction time
	 */
	public Nodeid getRevision() {
		return details.getRevision();
	}

	public HgRuntimeException setRevision(Nodeid r) {
		return details.setRevision(r);
	}
	
	public boolean isRevisionSet() {
		return details.isRevisionSet();
	}

	/**
	 * @return non-null only if file name was set at construction time
	 */
	public Path getFileName() {
		return details.getFileName();
	}

	public HgRuntimeException setFileName(Path name) {
		return details.setFileName(name);
	}

	@Override
	public String toString() {
		String base = super.toString();
		StringBuilder sb = new StringBuilder();
		details.appendDetails(sb);
		if (sb.length() == 0) {
			return base;
		}
		return new StringBuilder(base).append(' ').append('(').append(sb).append(')').toString();
	}
}
