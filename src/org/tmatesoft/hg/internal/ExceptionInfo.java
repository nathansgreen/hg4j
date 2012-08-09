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
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.repo.HgRepository.WORKING_COPY;

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
	protected Integer revNumber = null;
	protected Nodeid revision;
	protected Path filename;
	protected File localFile;
	// next two make sense only when revNumber was set
	private int rangeLeftBoundary = BAD_REVISION, rangeRightBoundary = BAD_REVISION;

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
		return revNumber == null ? HgRepository.BAD_REVISION : revNumber;
	}

	public T setRevisionIndex(int rev) {
		revNumber = rev;
		return owner;
	}
	
	public boolean isRevisionIndexSet() {
		return revNumber != null;
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
	
	public T setRevisionIndexBoundary(int revisionIndex, int rangeLeft, int rangeRight) {
		setRevisionIndex(revisionIndex);
		rangeLeftBoundary = rangeLeft;
		rangeRightBoundary = rangeRight;
		return owner;
	}

	public StringBuilder appendDetails(StringBuilder sb) {
		if (filename != null) {
			sb.append("path:'");
			sb.append(filename);
			sb.append('\'');
			sb.append(';');
			sb.append(' ');
		}
		if (isRevisionIndexSet() || isRevisionSet()) {
			if (isRevisionIndexSet()) {
				if (rangeLeftBoundary != BAD_REVISION || rangeRightBoundary != BAD_REVISION) {
					String sr;
					switch (getRevisionIndex()) {
					case BAD_REVISION:
						sr = "UNKNOWN"; break;
					case TIP:
						sr = "TIP"; break;
					case WORKING_COPY:
						sr = "WORKING-COPY"; break;
					case NO_REVISION:
						sr = "NO REVISION"; break;
					default:
						sr = String.valueOf(getRevisionIndex());
					}
					sb.append(String.format("%s is not from [%d..%d]", sr, rangeLeftBoundary, rangeRightBoundary));
				} else {
					sb.append("rev:");
					sb.append(getRevisionIndex());
					if (isRevisionSet()) {
						sb.append(':');
						// fall-through to get revision appended
					}
				}
			}
			if (isRevisionSet()) {
				sb.append(getRevision().shortNotation());
			}
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
