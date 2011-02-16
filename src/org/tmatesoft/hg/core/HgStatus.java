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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.core;

public class HgStatus {

	public enum Kind {
		Modified, Added, Removed, Unknown, Missing, Clean, Ignored
	};

	private final HgStatus.Kind kind;
	private final Path path;
	private final Path origin;
		
	HgStatus(HgStatus.Kind kind, Path path) {
		this(kind, path, null);
	}

	HgStatus(HgStatus.Kind kind, Path path, Path copyOrigin) {
		this.kind = kind;
		this.path  = path;
		origin = copyOrigin;
	}

	public HgStatus.Kind getKind() {
		return kind;
	}

	public Path getPath() {
		return path;
	}

	public Path getOriginalPath() {
		return origin;
	}

	public boolean isCopy() {
		return origin != null;
	}
		
//	public String getModificationAuthor() {
//	}
//
//	public Date getModificationDate() {
//	}
}