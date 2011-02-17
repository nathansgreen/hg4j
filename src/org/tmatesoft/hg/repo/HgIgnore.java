/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import org.tmatesoft.hg.util.Path;

/**
 * Handling of ignored paths according to .hgignore configuration
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgIgnore {

	private List<Pattern> entries;

	HgIgnore() {
		entries = Collections.emptyList();
	}

	/* package-local */void read(File hgignoreFile) throws IOException {
		if (!hgignoreFile.exists()) {
			return;
		}
		ArrayList<Pattern> result = new ArrayList<Pattern>(entries); // start with existing
		String syntax = "regex"; // or "glob"
		BufferedReader fr = new BufferedReader(new FileReader(hgignoreFile));
		String line;
		while ((line = fr.readLine()) != null) {
			line = line.trim();
			if (line.startsWith("syntax:")) {
				syntax = line.substring("syntax:".length()).trim();
				if (!"regex".equals(syntax) && !"glob".equals(syntax)) {
					throw new IllegalStateException(line);
				}
			} else if (line.length() > 0) {
				// shall I account for local paths in the file (i.e.
				// back-slashed on windows)?
				int x;
				if ((x = line.indexOf('#')) >= 0) {
					line = line.substring(0, x).trim();
					if (line.length() == 0) {
						continue;
					}
				}
				if ("glob".equals(syntax)) {
					// hgignore(5)
					// (http://www.selenic.com/mercurial/hgignore.5.html) says slashes '\' are escape characters,
					// hence no special  treatment of Windows path
					// however, own attempts make me think '\' on Windows are not treated as escapes
					line = glob2regex(line);
				}
				result.add(Pattern.compile(line)); // case-sensitive
			}
		}
		result.trimToSize();
		entries = result;
	}

	// note, #isIgnored(), even if queried for directories and returned positive reply, may still get
	// a file from that ignored folder to get examined. Thus, patterns like "bin" shall match not only a folder,
	// but any file under that folder as well
	// Alternatively, file walker may memorize folder is ignored and uses this information for all nested files. However,
	// this approach would require walker (a) return directories (b) provide nesting information. This may become
	// troublesome when one walks not over io.File, but Eclipse's IResource or any other custom VFS.
	//
	//
	// might be interesting, although looks like of no direct use in my case 
	// @see http://stackoverflow.com/questions/1247772/is-there-an-equivalent-of-java-util-regex-for-glob-type-patterns
	private String glob2regex(String line) {
		assert line.length() > 0;
		StringBuilder sb = new StringBuilder(line.length() + 10);
		sb.append('^'); // help avoid matcher.find() to match 'bin' pattern in the middle of the filename
		int start = 0, end = line.length() - 1;
		// '*' at the beginning and end of a line are useless for Pattern
		// XXX although how about **.txt - such globs can be seen in a config, are they valid for HgIgnore?
		while (start <= end && line.charAt(start) == '*') start++;
		while (end > start && line.charAt(end) == '*') end--;

		for (int i = start; i <= end; i++) {
			char ch = line.charAt(i);
			if (ch == '.' || ch == '\\') {
				sb.append('\\');
			} else if (ch == '?') {
				// simple '.' substitution might work out, however, more formally 
				// a char class seems more appropriate to avoid accidentally
				// matching a subdirectory with ? char (i.e. /a/b?d against /a/bad, /a/bed and /a/b/d)
				// @see http://pubs.opengroup.org/onlinepubs/009695399/utilities/xcu_chap02.html#tag_02_13_03
				// quote: "The slash character in a pathname shall be explicitly matched by using one or more slashes in the pattern; 
				// it shall neither be matched by the asterisk or question-mark special characters nor by a bracket expression" 
				sb.append("[^/]");
				continue;
			} else if (ch == '*') {
				sb.append("[^/]*?");
				continue;
			}
			sb.append(ch);
		}
		return sb.toString();
	}

	// TODO use PathGlobMatcher
	public boolean isIgnored(Path path) {
		for (Pattern p : entries) {
			if (p.matcher(path).find()) {
				return true;
			}
		}
		return false;
	}
}
