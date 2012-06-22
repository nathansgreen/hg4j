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
package org.tmatesoft.hg.repo;

import java.io.File;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.util.Path;

/**
 * Subclass of {@link HgInvalidFileException} to indicate failure to deal with one of <b>Mercurial</b> control files 
 * (most likely those under .hg/, but also those residing in the repository, with special meaning to the Mercurial, like .hgtags or .hgignore)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgInvalidControlFileException extends HgInvalidFileException {
	// XXX Perhaps, child HgInvalidRevlogException and parent HgInvalidRepositoryFileException?

	public HgInvalidControlFileException(String message, Throwable th, File file) {
		super(message, th, file);
	}

	@Override
	public HgInvalidControlFileException setFile(File file) {
		super.setFile(file);
		return this;
	}
	
	@Override
	public HgInvalidControlFileException setRevision(Nodeid r) {
		return (HgInvalidControlFileException) super.setRevision(r);
	}

	@Override
	public HgInvalidControlFileException setRevisionIndex(int rev) {
		return (HgInvalidControlFileException) super.setRevisionIndex(rev);
	}
	
	@Override
	public HgInvalidControlFileException setFileName(Path name) {
		return (HgInvalidControlFileException) super.setFileName(name);
	}
}
