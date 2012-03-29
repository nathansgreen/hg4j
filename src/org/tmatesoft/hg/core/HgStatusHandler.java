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
import org.tmatesoft.hg.util.Status;

/**
 * Callback to process {@link HgStatus} objects.
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Callback
public interface HgStatusHandler {

	/**
	 * Report status of the next file
	 * 
	 * @param s file status descriptor 
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce
	 */
	void status(HgStatus s) throws HgCallbackTargetException;

	/**
	 * Report non-critical error processing single file during status operation
	 * 
	 * @param file name of the file that caused the trouble
	 * @param s error description object
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce
	 */
	void error(Path file, Status s) throws HgCallbackTargetException;
}
