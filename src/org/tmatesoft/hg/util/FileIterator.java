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
package org.tmatesoft.hg.util;

import java.io.File;

/**
 * Abstracts iteration over file system.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface FileIterator {

	/**
	 * Brings iterator into initial state to facilitate new use.
	 */
	void reset();

	/**
	 * @return whether can shift to next element
	 */
	boolean hasNext();

	/**
	 * Shift to next element
	 */
	void next();

	/**
	 * @return repository-local path to the current element.
	 */
	Path name();

	/**
	 * @return filesystem element.
	 */
	File file();
}
