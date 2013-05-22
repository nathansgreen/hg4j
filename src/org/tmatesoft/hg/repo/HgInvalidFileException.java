/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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
import java.io.IOException;

import org.tmatesoft.hg.util.Path;

/**
 * Thrown when there are troubles working with local file. Most likely (but not necessarily) wraps IOException. Might be 
 * perceived as specialized IOException with optional File and other repository information.
 * 
 * <b>Hg4J</b> tries to minimize chances for IOException to occur (i.e. {@link File#canRead()} is checked before attempt to 
 * read a file that might not exist, and doesn't use this exception to wrap each and any {@link IOException} source (e.g. 
 * <code>#close()</code> calls are unlikely to yield it), hence it is likely to address real cases when I/O error occurs.
 * 
 * On the other hand, when a file is supposed to exist and be readable, this exception might get thrown as well to indicate
 * that's not true. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgInvalidFileException extends HgRuntimeException {
	// IMPLEMENTATION NOTE: Once needed, there might be intermediate e.g. HgDataStreamException 
	// (between HgInvalidFileException and HgRuntimeException) to root data access exceptions
	// that do not originate from local files but e.g. a connection

	public HgInvalidFileException(String message, Throwable th) {
		super(message, th);
	}

	/**
	 * 
	 * @param message description of the trouble, may (although should not) be <code>null</code>
	 * @param th cause, optional
	 * @param file where the trouble is, may be <code>null</code>, can be altered later with {@link #setFile(File)}
	 */
	public HgInvalidFileException(String message, Throwable th, File file) {
		super(message, th);
		details.setFile(file); // allows null
	}

	public HgInvalidFileException setFile(File file) {
		assert file != null; // doesn't allow null not to clear file accidentally
		details.setFile(file);
		return this;
	}
	
	@Override
	public HgInvalidFileException setFileName(Path name) {
		super.setFileName(name);
		return this;
	}

	/**
	 * @return file object that causes troubles, or <code>null</code> if specific file is unknown
	 */
	public File getFile() {
		return details.getFile();
	}
}
