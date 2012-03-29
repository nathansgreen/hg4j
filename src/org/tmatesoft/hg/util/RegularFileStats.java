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
package org.tmatesoft.hg.util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.ProcessExecHelper;

/**
 * Utility to collect executable files and symbolic links in a directory.
 * 
 * 
 * Not public as present approach (expect file but collect once per directory) may need to be made explicit
 * 
 * TODO post-1.0 Add Linux-specific set of tests (similar to my test-flags repository, with symlink, executable and regular file,
 * and few revisions where link and exec flags change. +testcase when link points to non-existing file (shall not report as missing, 
 * iow either FileInfo.exist() shall respect symlinks or WCSC account for )
 * 
 * TODO post-1.0 Add extraction of link modification time, see RegularFileInfo#lastModified()
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
/*package-local*/ class RegularFileStats {
	private boolean isExec, isSymlink;
	private String symlinkValue;
	private final List<String> command;
	private final ProcessExecHelper execHelper;
	private final Matcher linkMatcher, execMatcher;
	private final SessionContext sessionContext;
	
	
	// directory name to (short link name -> link target)
	private Map<String, Map<String, String>> dir2links = new TreeMap<String, Map<String, String>>();
	// directory name to set of executable file short names
	private Map<String, Set<String>> dir2execs = new TreeMap<String, Set<String>>();


	RegularFileStats(SessionContext ctx) {
		sessionContext = ctx;
		if (Internals.runningOnWindows()) {
			// XXX this implementation is not yet tested against any Windows repository, 
			// only against sample dir listings. As long as Mercurial doesn't handle Windows
			// links, we don't really need this
			command = Arrays.asList("cmd", "/c", "dir");
			// Windows patterns need to work against full directory listing (I didn't find a way 
			// to list single file with its attributes like SYMLINK) 
			Pattern pLink = Pattern.compile("^\\S+.*\\s+<SYMLINK>\\s+(\\S.*)\\s+\\[(.+)\\]$", Pattern.MULTILINE);
			Pattern pExec = Pattern.compile("^\\S+.*\\s+\\d+\\s+(\\S.*\\.exe)$", Pattern.MULTILINE);
			linkMatcher = pLink.matcher("");
			execMatcher = pExec.matcher("");
		} else {
			command = Arrays.asList("/bin/ls", "-l", "-Q"); // -Q is essential to get quoted name - the only way to
			// tell exact file name (which may start or end with spaces.
			Pattern pLink = Pattern.compile("^lrwxrwxrwx\\s.*\\s\"(.*)\"\\s+->\\s+\"(.*)\"$", Pattern.MULTILINE);
			// pLink: group(1) is full name if single file listing (ls -l /usr/bin/java) and short name if directory listing (ls -l /usr/bin)
			//        group(2) is link target
			Pattern pExec = Pattern.compile("^-..[sx]..[sx]..[sx]\\s.*\\s\"(.+)\"$", Pattern.MULTILINE);
			// pExec: group(1) is name of executable file
			linkMatcher = pLink.matcher("");
			execMatcher = pExec.matcher("");
		}
		execHelper = new ProcessExecHelper();
	}

	/**
	 * Fails silently indicating false for both x and l in case interaction with file system failed
	 * @param f file to check, doesn't need to exist
	 */
	public void init(File f) {
		isExec = isSymlink = false;
		symlinkValue = null;
		//
		// can't check isFile because Java would say false for a symlink with non-existing target
		if (f.isDirectory()) {
			// perhaps, shall just collect stats for all files and set false to exec/link flags?
			throw new IllegalArgumentException();
		}
		final String dirName = f.getParentFile().getAbsolutePath();
		final String fileName = f.getName();
		Map<String, String> links = dir2links.get(dirName);
		Set<String> execs = dir2execs.get(dirName);
		if (links == null || execs == null) {
			try {
				ArrayList<String> cmd = new ArrayList<String>(command);
				cmd.add(dirName);
				CharSequence result = execHelper.exec(cmd);
				
				if (execMatcher.reset(result).find()) {
					execs = new HashSet<String>();
					do {
						execs.add(execMatcher.group(1));
					} while (execMatcher.find());
				} else {
					execs = Collections.emptySet(); // indicate we tried and found nothing
				}
				if (linkMatcher.reset(result).find()) {
					links = new HashMap<String, String>();
					do {
						links.put(linkMatcher.group(1), linkMatcher.group(2));
					} while (linkMatcher.find());
				} else {
					links = Collections.emptyMap();
				}
				dir2links.put(dirName, links);
				dir2execs.put(dirName, execs);
				isExec = execs.contains(fileName);
				isSymlink = links.containsKey(fileName);
				if (isSymlink) {
					symlinkValue = links.get(fileName);
				} else {
					symlinkValue = null;
				}
			} catch (InterruptedException ex) {
				sessionContext.getLog().warn(getClass(), ex, String.format("Failed to detect flags for %s", f));
				// try again? ensure not too long? stop right away?
				// IGNORE, keep isExec and isSymlink false
			} catch (IOException ex) {
				sessionContext.getLog().warn(getClass(), ex, String.format("Failed to detect flags for %s", f));
				// IGNORE, keep isExec and isSymlink false
			}
		}
	}

	public boolean isExecutable() {
		return isExec;
	}
	
	public boolean isSymlink() {
		return isSymlink;
	}

	public String getSymlinkTarget() {
		if (isSymlink) {
			return symlinkValue;
		}
		throw new UnsupportedOperationException();
	}
}
