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
package org.tmatesoft.hg.internal;

import java.util.regex.PatternSyntaxException;

import org.tmatesoft.hg.core.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class PathGlobMatcher implements Path.Matcher {
	
	private final PathRegexpMatcher delegate;
	
	/**
	 * 
	 * @param globPatterns
	 * @throws NullPointerException if argument is null
	 * @throws IllegalArgumentException if any of the patterns is not valid
	 */
	public PathGlobMatcher(String... globPatterns) {
		String[] regexp = new String[globPatterns.length]; //deliberately let fail with NPE
		int i = 0;
		for (String s : globPatterns) {
			regexp[i] = glob2regexp(s);
		}
		try {
			delegate = new PathRegexpMatcher(regexp);
		} catch (PatternSyntaxException ex) {
			ex.printStackTrace();
			throw new IllegalArgumentException(ex);
		}
	}
	

	// HgIgnore.glob2regex is similar, but IsIgnore solves slightly different task 
	// (need to match partial paths, e.g. for glob 'bin' shall match not only 'bin' folder, but also any path below it,
	// which is not generally the case
	private static String glob2regexp(String glob) {
		int end = glob.length() - 1;
		boolean needLineEndMatch = glob.charAt(end) != '*';
		while (end > 0 && glob.charAt(end) == '*') end--; // remove trailing * that are useless for Pattern.find()
		StringBuilder sb = new StringBuilder(end*2);
		for (int i = 0; i <= end; i++) {
			char ch = glob.charAt(i);
			if (ch == '*') {
				if (glob.charAt(i+1) == '*') { // i < end because we've stripped any trailing * earlier
					// any char, including path segment separator
					sb.append(".*?");
				} else {
					// just path segments
					sb.append("[^/]*?");
				}
				continue;
			} else if (ch == '?') {
				sb.append("[^/]");
				continue;
			} else if (ch == '.' || ch == '\\') {
				sb.append('\\');
			}
			sb.append(ch);
		}
		if (needLineEndMatch) {
			sb.append('$');
		}
		return sb.toString();
	}

	public boolean accept(Path path) {
		return delegate.accept(path);
	}

}
