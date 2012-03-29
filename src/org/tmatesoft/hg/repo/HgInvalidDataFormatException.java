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

/**
 * Indicates broken, unknown or otherwise bad data structure.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgInvalidDataFormatException extends HgRuntimeException {
	// IMPLEMENTATION NOTE. Perhaps, this might be intermediate class between HgRuntimeException and HgInvalidFileException
	
	public HgInvalidDataFormatException(String message) {
		super(message, null);
	}
	
	public HgInvalidDataFormatException(String message, Throwable cause) {
		super(message, cause);
	}
}
