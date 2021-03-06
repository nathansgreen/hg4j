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
package org.tmatesoft.hg.util;

/**
 * Success/failure descriptor. When exception is too much.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Outcome {
	// XXX perhaps private enum and factory method createError() and createOk()?
	public enum Kind {
		Success, Failure;
	}

	private final Kind kind;
	private final String message;
	private final Exception error;

	public Outcome(Kind k, String msg) {
		this(k, msg, null);
	}
	
	public Outcome(Kind k, String msg, Exception err) {
		kind = k;
		message = msg;
		error = err;
	}
	
	public boolean isOk() {
		return kind == Kind.Success;
	}
	
	public Kind getKind() {
		return kind;
	}

	public String getMessage() {
		return message;
	}
	
	public Exception getException() {
		return error;
	}
}
