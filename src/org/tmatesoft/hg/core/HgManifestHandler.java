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
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Path;

/**
 * Callback to walk file/directory tree of a revision
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Callback
public interface HgManifestHandler {
	/**
	 * Indicates start of manifest revision. Subsequent {@link #file(HgFileRevision)} and {@link #dir(Path)} come 
	 * from the specified manifest revision until {@link #end(Nodeid)} with the matching revision is invoked.
	 * 
	 * @param manifestRevision unique identifier of the manifest revision
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce
	 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
	 */
	void begin(Nodeid manifestRevision) throws HgCallbackTargetException, HgRuntimeException;

	/**
	 * If walker is configured to spit out directories, indicates files from specified directories are about to be reported.
	 * Comes prior to any files from this directory and subdirectories
	 * 
	 * @param path directory known in the manifest
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce
	 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
	 */
	void dir(Path path) throws HgCallbackTargetException, HgRuntimeException; 

	/**
	 * Reports a file revision entry in the manifest
	 * 
	 * @param fileRevision description of the file revision
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce
	 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
	 */
	void file(HgFileRevision fileRevision) throws HgCallbackTargetException, HgRuntimeException;

	/**
	 * Indicates all files from the manifest revision have been reported.
	 * Closes {@link #begin(Nodeid)} with the same revision that came before.
	 * 
	 * @param manifestRevision unique identifier of the manifest revision 
	 * @throws HgCallbackTargetException wrapper for any exception user code may produce
	 * @throws HgRuntimeException propagates library issues. <em>Runtime exception</em>
	 */
	void end(Nodeid manifestRevision) throws HgCallbackTargetException, HgRuntimeException;
}