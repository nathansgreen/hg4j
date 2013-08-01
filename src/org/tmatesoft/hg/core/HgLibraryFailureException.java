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

/**
 * Sole purpose of this exception is to wrap unexpected errors from the library implementation and 
 * propagate them to clients of hi-level API for graceful (and explicit) processing.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgLibraryFailureException extends HgException {

	public HgLibraryFailureException(HgRuntimeException cause) {
		super(cause);
		assert cause != null;
	}

	public HgLibraryFailureException(String extraDetails, HgRuntimeException cause) {
		super(extraDetails, cause);
		assert cause != null;
	}

	@Override
	public HgRuntimeException getCause() {
		return (HgRuntimeException) super.getCause();
	}
}
