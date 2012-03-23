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
import org.tmatesoft.hg.util.Path;



/**
 * Checked exception that client supplied callback code can use to indicates its own errors.
 * 
 * <p>Generally, client need to pass own error information/exceptions from within implementations of the callback methods they supply. 
 * However, there's no straightforward way to alter throws clause for these methods, and alternatives like generic {@link Exception} or
 * library's own {@link HgException} are rather obscure. Suggested approach is to wrap whatever exception user code produces with
 * {@link HgCallbackTargetException}, the only checked exception allowed out from a callback.
 * 
 *  <p>It's intentionally not a subclass of {@link HgException} to avoid get mixed with library own errors and be processed separately.
 * 
 * FIXME REVISIT shall just throw HgCallbackTargetException from any handler, and do not catch anything in commands at all.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgCallbackTargetException extends Exception {
	
	protected final ExceptionInfo<HgCallbackTargetException> details = new ExceptionInfo<HgCallbackTargetException>(this);

	/**
	 * @param cause can't be <code>null</code>
	 */
	public HgCallbackTargetException(Throwable cause) {
		super((String) null);
		if (cause == null) {
			throw new IllegalArgumentException();
		}
		initCause(cause);
	}

	@SuppressWarnings("unchecked")
	public <T extends Throwable> T getTargetException() {
		return (T) getCause();
	}
	
	/**
	 * Despite this exception is merely a way to give users access to their own exceptions, it may still supply 
	 * valuable debugging information about what led to the error.
	 */
	@Override
	public String getMessage() {
		StringBuilder sb = new StringBuilder();
		sb.append("Error from callback. Original exception thrown: ");
		sb.append(getCause().getClass().getName());
		sb.append(" at ");
		details.appendDetails(sb);
		return sb.toString();
	}

	public HgCallbackTargetException setRevision(Nodeid r) {
		return details.setRevision(r);
	}

	public HgCallbackTargetException setRevisionIndex(int rev) {
		return details.setRevisionIndex(rev);
	}

	public HgCallbackTargetException setFileName(Path name) {
		return details.setFileName(name);
	}
}
