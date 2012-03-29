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

import org.tmatesoft.hg.internal.ExceptionInfo;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * Root class for all <b>Hg4J</b> checked exceptions.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public abstract class HgException extends Exception {
	
	protected final ExceptionInfo<HgException> extras = new ExceptionInfo<HgException>(this);

	protected HgException(String reason) {
		super(reason);
	}

	protected HgException(String reason, Throwable cause) {
		super(reason, cause);
	}

	protected HgException(Throwable cause) {
		super(cause);
	}

	/**
	 * @return not {@link HgRepository#BAD_REVISION} only when revision index was supplied at the construction time
	 */
	public int getRevisionIndex() {
		return extras.getRevisionIndex();
	}

	public HgException setRevisionIndex(int rev) {
		return extras.setRevisionIndex(rev);
	}
	
	public boolean isRevisionIndexSet() {
		return extras.isRevisionIndexSet();
	}

	/**
	 * @return non-null only when revision was supplied at construction time
	 */
	public Nodeid getRevision() {
		return extras.getRevision();
	}

	public HgException setRevision(Nodeid r) {
		return extras.setRevision(r);
	}
	
	public boolean isRevisionSet() {
		return extras.isRevisionSet();
	}

	/**
	 * @return non-null only if file name was set at construction time
	 */
	public Path getFileName() {
		return extras.getFileName();
	}

	public HgException setFileName(Path name) {
		return extras.setFileName(name);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(super.toString());
		sb.append(' ');
		sb.append('(');
		extras.appendDetails(sb);
		sb.append(')');
		return sb.toString();
	}
//	/* XXX CONSIDER capability to pass extra information about errors */
//	public static class Status {
//		public Status(String message, Throwable cause, int errorCode, Object extraData) {
//		}
//	}
}
