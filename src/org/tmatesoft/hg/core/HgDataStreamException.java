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

import org.tmatesoft.hg.repo.HgDataFile;

/**
 * Any erroneous state with @link {@link HgDataFile} input/output, read/write operations 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgDataStreamException extends HgException {

	public HgDataStreamException(String message, Throwable cause) {
		super(message, cause);
	}
}
