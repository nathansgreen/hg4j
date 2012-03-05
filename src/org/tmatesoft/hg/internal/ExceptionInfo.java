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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;

import java.io.File;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * Extras to record with exception to describe it better.
 * XXX perhaps, not only with exception, may utilize it with status object? 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ExceptionInfo<T> {
	protected final T owner;
	protected int revNumber = BAD_REVISION;
	protected Nodeid revision;
	protected Path filename;
	protected File localFile;

	/**
	 * @param owner instance to return from setters 
	 */
	public ExceptionInfo(T owner) {
		this.owner = owner;
	}
	
	/**
	 * @return not {@link HgRepository#BAD_REVISION} only when revision index was supplied at the construction time
	 */
	public int getRevisionIndex() {
		return revNumber;
	}

	public T setRevisionIndex(int rev) {
		revNumber = rev;
		return owner;
	}
	
	public boolean isRevisionIndexSet() {
		return revNumber != BAD_REVISION;
	}

	/**
	 * @return non-null only when revision was supplied at construction time
	 */
	public Nodeid getRevision() {
		return revision;
	}

	public T setRevision(Nodeid r) {
		revision = r;
		return owner;
	}
	
	public boolean isRevisionSet() {
		return revision != null;
	}

	/**
	 * @return non-null only if file name was set at construction time
	 */
	public Path getFileName() {
		return filename;
	}

	public T setFileName(Path name) {
		filename = name;
		return owner;
	}

	public T setFile(File file) {
		localFile = file;
		return owner;
	}

	/**
	 * @return file object that causes troubles, or <code>null</code> if specific file is unknown
	 */
	public File getFile() {
		return localFile;
	}

	public StringBuilder appendDetails(StringBuilder sb) {
		if (filename != null) {
			sb.append("path:'");
			sb.append(filename);
			sb.append('\'');
			sb.append(';');
			sb.append(' ');
		}
		sb.append("rev:");
		if (revNumber != BAD_REVISION) {
			sb.append(revNumber);
			if (revision != null) {
				sb.append(':');
			}
		}
		if (revision != null) {
			sb.append(revision.shortNotation());
		}
		if (localFile != null) {
			sb.append(';');
			sb.append(' ');
			sb.append(" file:");
			sb.append(localFile.getPath());
			sb.append(',');
			if (localFile.exists()) {
				sb.append("EXISTS");
			} else {
				sb.append("DOESN'T EXIST");
			}
		}
		return sb;
	}
}
