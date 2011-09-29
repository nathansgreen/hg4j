/*
 * Copyright (c) 2011 TMate Software Ltd
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

import org.tmatesoft.hg.internal.Experimental;

/**
 * WORK IN PROGRESS
 * 
 * Subclass of {@link HgInvalidFileException} to indicate failure to deal with one of <b>Mercurial</b> control files (those under .hg/)
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
@Experimental(reason="WORK IN PROGRESS. Name is likely to change. Path argument to be added?")
public class HgInvalidControlFileException extends HgInvalidFileException {

	public HgInvalidControlFileException(String message, Throwable th, File file) {
		super(message, th, file);
	}

}
