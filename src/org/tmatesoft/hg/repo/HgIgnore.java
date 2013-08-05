/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepositoryFiles.HgIgnore;
import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.tmatesoft.hg.internal.FileChangeMonitor;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Handling of ignored paths according to .hgignore configuration
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgIgnore implements Path.Matcher {

	private List<Pattern> entries;
	private final PathRewrite globPathHelper;
	private FileChangeMonitor ignoreFileTracker;
	// if pattern matches first fragment of a path, it will
	// match any other path with this fragment, so we can avoid pattern matching
	// if path starts with one of such fragments (often for e.g. ignored 'bin/' folders)
	private final Set<String> ignoredFirstFragments;

	HgIgnore(PathRewrite globPathRewrite) {
		entries = Collections.emptyList();
		globPathHelper = globPathRewrite;
		ignoredFirstFragments = new HashSet<String>();
	}

	/* package-local */ void read(Internals repo) throws HgInvalidControlFileException {
		File ignoreFile = repo.getRepositoryFile(HgIgnore);
		BufferedReader fr = null;
		try {
			if (ignoreFile.canRead() && ignoreFile.isFile()) {
				fr = new BufferedReader(new FileReader(ignoreFile));
				final List<String> errors = read(fr);
				if (errors != null) {
					repo.getLog().dump(getClass(), Warn, "Syntax errors parsing %s:\n%s", ignoreFile.getName(), Internals.join(errors, ",\n"));
				}
			}
			if (ignoreFileTracker == null) {
				ignoreFileTracker = new FileChangeMonitor(ignoreFile);
			}
			ignoreFileTracker.touch(this);
		} catch (IOException ex) {
			final String m = String.format("Error reading %s file", ignoreFile);
			throw new HgInvalidControlFileException(m, ex, ignoreFile);
		} finally {
			try {
				if (fr != null) {
					fr.close();
				}
			} catch (IOException ex) {
				repo.getLog().dump(getClass(), Warn, ex, null); // it's read, don't treat as error
			}
		}
	}
	
	/*package-local*/ void reloadIfChanged(Internals repo) throws HgInvalidControlFileException {
		assert ignoreFileTracker != null;
		if (ignoreFileTracker.changed(this)) {
			entries = Collections.emptyList();
			read(repo);
		}
	}

	/* package-local */ List<String> read(BufferedReader content) throws IOException {
		final String REGEXP = "regexp", GLOB = "glob";
		final String REGEXP_PREFIX1 = REGEXP + ":", REGEXP_PREFIX2 = "re:", GLOB_PREFIX = GLOB + ":";
		ArrayList<String> errors = new ArrayList<String>();
		ArrayList<Pattern> result = new ArrayList<Pattern>(entries); // start with existing
		String syntax = REGEXP;
		String line;
		while ((line = content.readLine()) != null) {
			line = line.trim();
			if (line.startsWith("syntax:")) {
				syntax = line.substring("syntax:".length()).trim();
				if (!REGEXP.equals(syntax) && !GLOB.equals(syntax)) {
					errors.add(line);
					continue;
					//throw new IllegalStateException(line);
				}
			} else if (line.length() > 0) {
				// shall I account for local paths in the file (i.e.
				// back-slashed on windows)?
				int x, s = 0;
				while ((x = line.indexOf('#', s)) >= 0) {
					if (x > 0 && line.charAt(x-1) == '\\') {
						// remove escape char
						line = line.substring(0, x-1).concat(line.substring(x));
						s = x; // with exclusion of char at [x], s now points to what used to be at [x+1]
					} else {
						line = line.substring(0, x).trim();
					}
				}
				// due to the nature of Mercurial implementation, lines prefixed with syntax kind
				// are processed correctly (despite the fact hgignore(5) suggest "syntax:<kind>" as the 
				// only way to specify it). lineSyntax below leaves a chance for the line to switch 
				// syntax in use without affecting default kind.
				String lineSyntax;
				if (line.startsWith(GLOB_PREFIX)) {
					line = line.substring(GLOB_PREFIX.length()).trim();
					lineSyntax = GLOB;
				} else if (line.startsWith(REGEXP_PREFIX1)) {
					line = line.substring(REGEXP_PREFIX1.length()).trim();
					lineSyntax = REGEXP;
				} else if (line.startsWith(REGEXP_PREFIX2)) {
					line = line.substring(REGEXP_PREFIX2.length()).trim();
					lineSyntax = REGEXP;
				} else {
					lineSyntax = syntax;
				}
				if (line.length() == 0) {
					continue;
				}
				if (GLOB.equals(lineSyntax)) {
					// hgignore(5) says slashes '\' are escape characters,
					// however, for glob patterns on Windows first get backslashes converted to slashes
					if (globPathHelper != null) {
						line = globPathHelper.rewrite(line).toString();
					}
					line = glob2regex(line);
				} else {
					assert REGEXP.equals(lineSyntax);
					// regular expression patterns need not match start of the line unless demanded explicitly 
					line = line.charAt(0) == '^' ? line : ".*" + line;
				}
				try {
					result.add(Pattern.compile(line)); // case-sensitive
				} catch (PatternSyntaxException ex) {
					errors.add(line + "@" + ex.getMessage());
				}
			}
		}
		result.trimToSize();
		entries = result;
		return errors.isEmpty() ? null : errors;
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
	//
	// TODO consider refactoring to reuse in PathGlobMatcher#glob2regexp
	private static String glob2regex(String line) {
		assert line.length() > 0;
		StringBuilder sb = new StringBuilder(line.length() + 10);
		int start = 0, end = line.length() - 1;
		sb.append("(?:|.*/)"); // glob patterns shall match file in any directory

		int inCurly = 0;
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
			} else if (ch == '{') {
				// XXX in fact, need to respect if last char was escaping ('\\'), then don't need to treat this as special
				// see link at javadoc above for reasonable example
				inCurly++;
				sb.append('(');
				continue;
			} else if (ch == '}') {
				if (inCurly > 0) {
					inCurly--;
					sb.append(')');
					continue;
				}
			} else if (ch == ',' && inCurly > 0) {
				sb.append('|');
				continue;
			}
			sb.append(ch);
		}
		// Python impl doesn't keep empty segments in directory names (ntpath.normpath and posixpath.normpath),
		// effectively removing trailing separators, thus patterns like "bin/" get translated into "bin$"
		// Our glob rewriter doesn't strip last empty segment, and "bin/$" would be incorrect pattern, 
		// (e.g. isIgnored("bin/file") performs two matches, against "bin/file" and "bin") hence the check.
		if (sb.charAt(sb.length() - 1) != '/') {
			sb.append('$');
		}
		return sb.toString();
	}

	/**
	 * @param path file or directory name in question
	 * @return <code>true</code> if matches repository configuration of ignored files.
	 */
	public boolean isIgnored(Path path) {
		String ps = path.toString();
		int x = ps.indexOf('/');
		if (x != -1 && ignoredFirstFragments.contains(ps.substring(0, x))) {
			return true;
		}
		for (Pattern p : entries) {
			if (p.matcher(ps).find()) {
				return true;
			}
		}
		boolean firstFragment = true;
		while (x != -1 && x+1 != ps.length() /*skip very last segment not to check complete string twice*/) {
			String fragment = ps.substring(0, x);
			for (Pattern p : entries) {
				if (p.matcher(fragment).matches()) {
					if (firstFragment) {
						ignoredFirstFragments.add(new String(fragment));
					}
					return true;
				}
			}
			x = ps.indexOf('/', x+1);
			firstFragment = false;
		}
		return false;
	}

	/**
	 * A handy wrap of {@link #isIgnored(Path)} into {@link org.tmatesoft.hg.util.Path.Matcher}. Yields same result as {@link #isIgnored(Path)}.
	 * @return <code>true</code> if file is deemed ignored.
	 */
	public boolean accept(Path path) {
		return isIgnored(path);
	}
}
