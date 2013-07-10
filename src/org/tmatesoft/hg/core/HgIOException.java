/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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
import java.io.IOException;

/**
 * Tailored wrap for {@link IOException} and similar I/O-related issues. Unlike {@link IOException}, 
 * keeps track of {@link File} that caused the problem. Besides, additional information (like revision, 
 * see {@link HgException}) may be attached.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgIOException extends HgException {
	private File file;

	public HgIOException(String message, File troubleFile) {
		this(message, null, troubleFile);
	}

	/**
	 * @param message describes the issue, never <code>null</code>
	 * @param cause root cause for the error, likely {@link IOException} or its subclass, but not necessarily, and may be omitted. 
	 * @param troubleFile file we tried to deal with, or <code>null</code> if set later
	 */
	public HgIOException(String message, Throwable cause, File troubleFile) {
		super(message, cause);
		file = troubleFile;
	}

	/**
	 * @return file that causes trouble, may be <code>null</code>
	 */
	public File getFile() {
		return file;
	}

	/**
	 * @return <code>this</code> for convenience
	 */
	public HgIOException setFile(File f) {
		file = f;
		return this;
	}
}
