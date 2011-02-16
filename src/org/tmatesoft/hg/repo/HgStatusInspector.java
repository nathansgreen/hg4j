/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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

import org.tmatesoft.hg.util.Path;

/**
 * Callback to get file status information
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface HgStatusInspector {
	void modified(Path fname);
	void added(Path fname);
	// XXX need to specify whether StatusCollector invokes added() along with copied or not!
	void copied(Path fnameOrigin, Path fnameAdded); // if copied files of no interest, should delegate to self.added(fnameAdded);
	void removed(Path fname);
	void clean(Path fname);
	void missing(Path fname); // aka deleted (tracked by Hg, but not available in FS any more
	void unknown(Path fname); // not tracked
	void ignored(Path fname);
}