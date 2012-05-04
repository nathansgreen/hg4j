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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.util.Path.CompareResult.*;

import java.util.ArrayList;

import org.tmatesoft.hg.util.FileIterator;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.Path.CompareResult;

/**
 * <ul>
 * <li> Specify folder to get all files in there included, but no subdirs
 * <li> Specify folder to get all files and files in subdirectories included
 * <li> Specify exact set files (with option to accept or not paths leading to them) 
 * </ul>
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class PathScope implements Path.Matcher {
	private final Path[] files;
	private final Path[] dirs;
	private final boolean includeNestedDirs;
	private final boolean includeParentDirs;
	private final boolean includeDirContent;
	
	/**
	 * See {@link PathScope#PathScope(boolean, boolean, Path...)} 
	 */
	public PathScope(boolean recursiveDirs, Path... paths) {
		this(true, recursiveDirs, true, paths);
	}

	/**
	 * With <code>matchParentDirs</code>, <code>recursiveDirs</code> and <code>matchDirContent</code> set to <code>false</code>, 
	 * this scope matches only exact paths specified.
	 * <p> 
	 * With <code>matchParentDirs</code> set to <code>true</code>, parent directories for files and folders listed in 
	 * the <code>paths</code> would get accepted as well (handy for {@link FileIterator FileIterators}). 
	 * Note, if supplied path lists a file, parent directory for the file is not matched unless <code>matchParentDirs</code>
	 * is <code>true</code>. To match file's immediate parent without matching all other parents up to the root, just add file parent
	 * along with the file to <code>paths</code>.
	 * <p> 
	 * With <code>recursiveDirs</code> set to <code>true</code>, subdirectories (with files) of directories listed in <code>paths</code> would 
	 * be matched as well. Similar to `a/b/**`
	 * <p>
	 * With <code>matchDirContent</code> set to <code>true</code>, files right under any directory listed in <code>path</code> would be matched.
	 * Similar to `a/b/*`. Makes little sense to set to <code>false</code> when <code>recursiceDirs</code> is <code>true</code>, although may still 
	 * be useful in certain scenarios, e.g. PathScope(false, true, false, "a/") matches files under "a/b/*" and "a/b/c/*", but not files "a/*".
	 * 
	 * @param matchParentDirs <code>true</code> to accept parent dirs of supplied paths
	 * @param recursiveDirs <code>true</code> to include subdirectories and files of supplied paths
	 * @param includeDirContent
	 * @param paths files and folders to match
	 */
	public PathScope(boolean matchParentDirs, boolean recursiveDirs, boolean matchDirContent, Path... paths) {
		if (paths == null) {
			throw new IllegalArgumentException();
		}
		includeParentDirs = matchParentDirs;
		includeNestedDirs = recursiveDirs;
		includeDirContent = matchDirContent;
		ArrayList<Path> f = new ArrayList<Path>(5);
		ArrayList<Path> d = new ArrayList<Path>(5);
		for (Path p : paths) {
			if (p.isDirectory()) {
				d.add(p);
			} else {
				f.add(p);
			}
		}
		files = f.toArray(new Path[f.size()]);
		dirs = d.toArray(new Path[d.size()]);
	}

	public boolean accept(Path path) {
		if (path.isDirectory()) {
			// either equals to or a parent of a directory we know about (i.e. configured dir is *nested* in supplied arg). 
			// Also, accept arg if it happened to be nested into configured dir (i.e. one of them is *parent* for the arg), 
			//       and recursiveDirs is true. 
			for (Path d : dirs) {
				switch(d.compareWith(path)) {
				case Same : return true;
				case ImmediateChild :
				case Nested : return includeParentDirs; // path is parent to one of our locations
				case ImmediateParent :
				case Parent : return includeNestedDirs; // path is nested in one of our locations
				}
			}
			if (!includeParentDirs) {
				return false;
			}
			// If one of configured files is nested under the path, and we shall report parents, accept.
			// Note, I don't respect includeDirContent here as with file it's easy to add parent to paths explicitly, if needed.
			// (if includeDirContent == .f and includeParentDirs == .f, directory than owns a scope file won't get reported)  
			for (Path f : files) {
				CompareResult cr = f.compareWith(path);
				if (cr == Nested || cr == ImmediateChild) {
					return true;
				}
			}
		} else {
			for (Path f : files) {
				if (f.equals(path)) {
					return true;
				}
			}
			// if interested in nested/recursive dirs, shall check if supplied file is under any of our configured locations 
			if (!includeNestedDirs && !includeDirContent) {
				return false;
			}
			for (Path d : dirs) {
				CompareResult cr = d.compareWith(path);
				if (includeNestedDirs && cr == Parent) {
					// file is nested in one of our locations
					return true;
				}
				if (includeDirContent && cr == ImmediateParent) {
					// file is right under one of our directories, and includeDirContents is .t
					return true;
				}
				// try another directory
			}
		}
		return false;
	}
}