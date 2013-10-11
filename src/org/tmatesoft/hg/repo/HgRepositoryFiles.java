/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

	HgIgnore(Home.Root, ".hgignore"), HgTags(Home.Root, ".hgtags"), HgEol(Home.Root, ".hgeol"), 
	Dirstate(Home.Repo, "dirstate"), HgLocalTags(Home.Repo, "localtags"),
	HgSub(Home.Root, ".hgsub"), HgSubstate(Home.Root, ".hgsubstate"),
	LastMessage(Home.Repo, "last-message.txt"),
	Bookmarks(Home.Repo, "bookmarks"), BookmarksCurrent(Home.Repo, "bookmarks.current"),
	Branch(Home.Repo, "branch"), 
	UndoBranch(Home.Repo, "undo.branch"), UndoDirstate(Home.Repo, "undo.dirstate"),
	Phaseroots(Home.Store, "phaseroots"), FNCache(Home.Store, "fncache"),
	WorkingCopyLock(Home.Repo, "wlock"), StoreLock(Home.Store, "lock"),
	MergeState(Home.Repo, "merge/state"),
	RepoConfig(Home.Repo, "hgrc");

	/**
	 * Possible file locations
	 * <ul> 
	 * <li>{@link #Root} lives in working copy
	 * <li>{@link #Repo} files under <samp>.hg/</samp>
	 * <li>#{@link #Store} files in storage area, either <samp>.hg/store/</samp> or <samp>.hg/</samp>
	 * </ul>
	 */
	public enum Home {
		Root, Repo, Store
	}

	private final String fname;
	private final Home residesIn; 
	
	private HgRepositoryFiles(Home home, String filename) {
		fname = filename;
		residesIn = home;
	}

	/**
	 * Path to the file, relative to the repository root.
	 * 
	 * For repository files that reside in working directory, return their location relative to the working dir.
	 * For files that reside under repository root, path returned includes '.hg/' prefix.
	 * For files from {@link Home#Store} storage area, path starts with '.hg/store/', although actual use of 'store' folder
	 * is controlled by repository requirements. Returned value shall be deemed as 'most likely' path in a general environment.
	 * @return file location, never <code>null</code>
	 */
	public String getPath() {
		switch (residesIn) {
			case Store : return ".hg/store/" + getName();
			case Repo : return ".hg/" + getName();
			default : return getName();
		}
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
		return residesIn == Home.Root;
	}

	/**
	 * @return <code>true</code> if file lives under '.hg/' 
	 */
	public boolean residesUnderRepositoryRoot() {
		return residesIn == Home.Repo;
	}
	
	/**
	 * Identify a root the file lives under
	 */
	public Home getHome() {
		return residesIn;
	}
}
