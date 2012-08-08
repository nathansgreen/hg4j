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
package org.tmatesoft.hg.repo;


/**
 * Names of some Mercurial configuration/service files.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public enum HgRepositoryFiles {

	HgIgnore(".hgignore"), HgTags(".hgtags"), HgEol(".hgeol"), 
	Dirstate(false, "dirstate"), HgLocalTags(false, "localtags"),
	HgSub(".hgsub"), HgSubstate(".hgsubstate"),
	LastMessage(false, "last-message.txt"),
	Bookmarks(false, "bookmarks"), BookmarksCurrent(false, "bookmarks.current");

	private final String fname;
	private final boolean livesInWC; 
	
	private HgRepositoryFiles(String filename) {
		this(true, filename);
	}

	private HgRepositoryFiles(boolean wcNotRepoRoot, String filename) {
		fname = filename;
		livesInWC = wcNotRepoRoot;
	}

	/**
	 * Path to the file, relative to the parent it lives in.
	 * 
	 * For repository files that reside in working directory, return their location relative to the working dir.
	 * For files that reside under repository root, path returned would include '.hg/' prefix.
	 * @return file location, never <code>null</code>
	 */
	public String getPath() {
		return livesInWC ? getName() : ".hg/" + getName();
	}

	/**
	 * File name without any path information
	 * @return file name, never <code>null</code>
	 */
	public String getName() {
		return fname;
	}

	/**
	 * Files that reside under working directory may be accessed like:
	 * <pre>
	 *   HgRepository hgRepo = ...;
	 *   File f = new File(hgRepo.getWorkingDir(), HgRepositoryFiles.HgIgnore.getPath())
	 * </pre>
	 * @return <code>true</code> if file lives in working tree
	 */
	public boolean residesUnderWorkingDir() {
		return livesInWC;
	}

	/**
	 * @return <code>true</code> if file lives under '.hg/' 
	 */
	public boolean residesUnderRepositoryRoot() {
		return !livesInWC;
	}
}
