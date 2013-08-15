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
package org.tmatesoft.hg.util;

import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.tmatesoft.hg.internal.Internals;

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

	/**
	 * Check if this is directory's path. 
	 * Note, this method doesn't perform any file system operation.
	 * 
	 * @return true when this path points to a directory 
	 */
	public boolean isDirectory() {
		// XXX simple logic for now. Later we may decide to have an explicit factory method to create directory paths
		return path.charAt(path.length() - 1) == '/';
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
	
	public Iterable<String> segments() {
		class SegSeq implements Iterable<String>, Iterator<String> {
			private int pos; // first char to return

			public Iterator<String> iterator() {
				reset();
				return this;
			}
			public boolean hasNext() {
				return pos < path.length();
			}
			public String next() {
				if (pos >= path.length()) {
					throw new NoSuchElementException();
				}
				int x = path.indexOf('/', pos);
				if (x == -1) {
					String rv = path.substring(pos);
					pos = path.length();
					return rv;
				} else {
					String rv = path.substring(pos, x);
					pos = x+1;
					return rv;
				}
			}
			public void remove() {
				throw new UnsupportedOperationException();
			}

			private void reset() {
				pos = 0;
			}
		};
		return new SegSeq();
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
	
	public enum CompareResult {
		Same, Unrelated, ImmediateChild, Nested, ImmediateParent, Parent /* +CommonParent ?*/
	}
	
	/**
	 * @return one of {@link CompareResult} constants to indicate relations between the paths 
	 */
	public CompareResult compareWith(Path another) {
		if (another == null) {
			return CompareResult.Unrelated; // XXX perhaps, IAE?
		}
		if (another == this || (another.length() == length() && equals(another))) {
			return CompareResult.Same;
		}
		// one of the parties can't be parent in parent/nested, the other may be either file or folder 
		if (another.isDirectory() && path.startsWith(another.path)) {
			return isOneSegmentDifference(path, another.path) ? CompareResult.ImmediateChild : CompareResult.Nested;
		}
		if (isDirectory() && another.path.startsWith(path)) {
			return isOneSegmentDifference(another.path, path) ? CompareResult.ImmediateParent : CompareResult.Parent;
		}
		return CompareResult.Unrelated;
	}
	
	// true if p1 is only one segment larger than p2
	private static boolean isOneSegmentDifference(String p1, String p2) {
		assert p1.startsWith(p2);
		String p1Tail= p1.substring(p2.length());
		int slashLoc = p1Tail.indexOf('/');
		return slashLoc == -1 || slashLoc == p1Tail.length() - 1;
	}
	
	private static final boolean runningOnWindows;
	
	static {
		runningOnWindows = Internals.runningOnWindows();
	}

	public static Path create(CharSequence path) {
		if (path == null) {
			throw new IllegalArgumentException();
		}
		if (path instanceof Path) {
			Path o = (Path) path;
			return o;
		}
		String p = path.toString();
		if (runningOnWindows && p.indexOf('\\') != -1) {
			throw new IllegalArgumentException(String.format("Path '%s' contains illegal char at %d", p, p.indexOf('\\')));
		}
		Path rv = new Path(p);
		return rv;
	}

	/**
	 * Path filter.
	 */
	public interface Matcher {
		boolean accept(Path path);
		
		final class Any implements Matcher {
			public boolean accept(Path path) { return true; }
		}
		class Composite implements Matcher {
			private final Path.Matcher[] elements;
			
			public Composite(Collection<Path.Matcher> matchers) {
				elements = matchers.toArray(new Path.Matcher[matchers.size()]);
			}

			public boolean accept(Path path) {
				for (Path.Matcher m : elements) {
					if (m.accept(path)) {
						return true;
					}
				}
				return false;
			}
		}
	}

	/**
	 * Factory for paths
	 */
	public interface Source {
		Path path(CharSequence p);
	}
	
	/**
	 * Straightforward {@link Source} implementation that creates new Path instance for each supplied string
	 * and optionally piping through a converter to get e.g. cached instance 
	 */
	public static class SimpleSource implements Source {
		private final PathRewrite normalizer;
		private final Convertor<Path> convertor;
		private final Path.Source delegate;

		public SimpleSource() {
			this(new PathRewrite.Empty(), null);
		}

		public SimpleSource(PathRewrite pathRewrite) {
			this(pathRewrite, null);
		}

		public SimpleSource(PathRewrite pathRewrite, Convertor<Path> pathConvertor) {
			assert pathRewrite != null;
			normalizer = pathRewrite;
			convertor = pathConvertor;
			delegate = null;
		}

		public SimpleSource(Path.Source actual, Convertor<Path> pathConvertor) {
			assert actual != null;
			normalizer = null;
			delegate = actual;
			convertor = pathConvertor;
		}

		public Path path(CharSequence p) {
			// in fact, it's nicer to have sequence of sources, and a bunch of small
			// Source implementations each responsible for specific aspect, like Convertor
			// or delegation to another Source. However, these classes are just too small 
			// to justify their existence
			Path rv;
			if (delegate != null) {
				rv = delegate.path(p);
			} else {
				rv = Path.create(normalizer.rewrite(p));
			}
			if (convertor != null) {
				return convertor.mangle(rv);
			}
			return rv;
		}
	}
}
