/*
 * Copyright (c) 2013 TMate Software Ltd
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

import java.io.File;

import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidFileException;

/**
 * Thrown to indicate integrity issues with a revision, namely, when digest (SHA1) over revision data
 * doesn't match revision id.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgRevisionIntegrityException extends HgInvalidControlFileException {
	
	/**
	 * See {@link HgInvalidFileException#HgInvalidFileException(String, Throwable, File)} for parameters description.
	 */
	public HgRevisionIntegrityException(String message, Throwable th, File file) {
		super(message, th, file);
	}

}
