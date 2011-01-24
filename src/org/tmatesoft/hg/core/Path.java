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

/**
 * Identify repository files (not String nor io.File). Convenient for pattern matching. Memory-friendly.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Path implements CharSequence, Comparable<Path>/*Cloneable? - although clone for paths make no sense*/{
//	private String[] segments;
//	private int flags; // dir, unparsed
	private String path;
	
	/*package-local*/Path(String p) {
		path = p;
	}

	public int length() {
		return path.length();
	}

	public char charAt(int index) {
		return path.charAt(index);
	}

	public CharSequence subSequence(int start, int end) {
		// new Path if start-end matches boundaries of any subpath
		return path.substring(start, end);
	}
	
	@Override
	public String toString() {
		return path; // CharSequence demands toString() impl
	}

	public int compareTo(Path o) {
		return path.compareTo(o.path);
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj != null && getClass() == obj.getClass()) {
			return this == obj || path.equals(((Path) obj).path);
		}
		return false;
	}
	@Override
	public int hashCode() {
		return path.hashCode();
	}

	public static Path create(String path) {
		if (path == null) {
			throw new IllegalArgumentException();
		}
		if (path.indexOf('\\') != -1) {
			throw new IllegalArgumentException();
		}
		Path rv = new Path(path);
		return rv;
	}
	public interface Matcher {
		public boolean accept(Path path);
	}
}
