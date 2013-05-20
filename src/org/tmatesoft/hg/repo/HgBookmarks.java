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

import static org.tmatesoft.hg.util.LogFacility.Severity.Error;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.HgRepositoryLockException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.FileChangeMonitor;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.LineReader;
import org.tmatesoft.hg.util.LogFacility;

/**
 * Access to bookmarks state
 * 
 * @see http://mercurial.selenic.com/wiki/Bookmarks
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgBookmarks {
	private final Internals repo;
	private Map<String, Nodeid> bookmarks = Collections.emptyMap();
	private String activeBookmark;
	private FileChangeMonitor activeTracker, bmFileTracker;

	HgBookmarks(Internals internals) {
		repo = internals;
	}
	
	/*package-local*/ void read() throws HgInvalidControlFileException {
		readBookmarks();
		readActiveBookmark();
	}
	
	private void readBookmarks() throws HgInvalidControlFileException {
		final LogFacility log = repo.getLog();
		File all = repo.getRepositoryFile(HgRepositoryFiles.Bookmarks);
		LinkedHashMap<String, Nodeid> bm = new LinkedHashMap<String, Nodeid>();
		if (all.canRead() && all.isFile()) {
			LineReader lr1 = new LineReader(all, log);
			ArrayList<String> c = new ArrayList<String>();
			lr1.read(new LineReader.SimpleLineCollector(), c);
			for (String s : c) {
				int x = s.indexOf(' ');
				try {
					if (x > 0) {
						Nodeid nid = Nodeid.fromAscii(s.substring(0, x));
						String name = new String(s.substring(x+1));
						if (repo.getRepo().getChangelog().isKnown(nid)) {
							// copy name part not to drag complete line
							bm.put(name, nid);
						} else {
							log.dump(getClass(), LogFacility.Severity.Info, "Bookmark %s points to non-existent revision %s, ignored.", name, nid);
						}
					} else {
						log.dump(getClass(), LogFacility.Severity.Warn, "Can't parse bookmark entry: %s", s);
					}
				} catch (IllegalArgumentException ex) {
					log.dump(getClass(), LogFacility.Severity.Warn, ex, String.format("Can't parse bookmark entry: %s", s));
				}
			}
			bookmarks = bm;
		} else {
			bookmarks = Collections.emptyMap();
		}
		if (bmFileTracker == null) {
			bmFileTracker = new FileChangeMonitor(all);
		}
		bmFileTracker.touch(this);
	}
		
	private void readActiveBookmark() throws HgInvalidControlFileException { 
		activeBookmark = null;
		File active = repo.getRepositoryFile(HgRepositoryFiles.BookmarksCurrent);
		if (active.canRead() && active.isFile()) {
			LineReader lr2 = new LineReader(active, repo.getLog());
			ArrayList<String> c = new ArrayList<String>(2);
			lr2.read(new LineReader.SimpleLineCollector(), c);
			if (c.size() > 0) {
				activeBookmark = c.get(0);
			}
		}
		if (activeTracker == null) {
			activeTracker = new FileChangeMonitor(active);
		}
		activeTracker.touch(this);
	}
	
	/*package-local*/void reloadIfChanged() throws HgInvalidControlFileException {
		assert activeTracker != null;
		assert bmFileTracker != null;
		if (bmFileTracker.changed(this)) {
			readBookmarks();
		}
		if (activeTracker.changed(this)) {
			readActiveBookmark();
		}
	}


	/**
	 * Tell name of the active bookmark 
	 * @return <code>null</code> if none active
	 */
	public String getActiveBookmarkName() {
		return activeBookmark;
	}

	/**
	 * Retrieve revision associated with the named bookmark.
	 * 
	 * @param bookmarkName name of the bookmark
	 * @return revision or <code>null</code> if bookmark is not known
	 */
	public Nodeid getRevision(String bookmarkName) {
		return bookmarks.get(bookmarkName);
	}

	/**
	 * Retrieve all bookmarks known in the repository
	 * @return collection with names, never <code>null</code>
	 */
	public Collection<String> getAllBookmarks() {
		// bookmarks are initialized with atomic assignment,
		// hence can use view (not a synchronized copy) here
		return Collections.unmodifiableSet(bookmarks.keySet());
	}

	/**
	 * Update currently bookmark with new commit.
	 * Note, child has to be descendant of a p1 or p2
	 * 
	 * @param p1 first parent, or <code>null</code>
	 * @param p2 second parent, or <code>null</code>
	 * @param child new commit, descendant of one of the parents, not <code>null</code>
	 * @throws HgIOException if failed to write updated bookmark information 
	 * @throws HgRepositoryLockException  if failed to lock repository for modifications
	 */
	public void updateActive(Nodeid p1, Nodeid p2, Nodeid child) throws HgIOException, HgRepositoryLockException {
		if (activeBookmark == null) {
			return;
		}
		Nodeid activeRev = getRevision(activeBookmark);
		if (!activeRev.equals(p1) && !activeRev.equals(p2)) {
			return; // TestCommit#testNoBookmarkUpdate
		}
		if (child.equals(activeRev)) {
			return;
		}
		LinkedHashMap<String, Nodeid> copy = new LinkedHashMap<String, Nodeid>(bookmarks);
		copy.put(activeBookmark, child);
		bookmarks = copy;
		write();
	}
	
	private void write() throws HgIOException, HgRepositoryLockException {
		File bookmarksFile = repo.getRepositoryFile(HgRepositoryFiles.Bookmarks);
		HgRepositoryLock workingDirLock = repo.getRepo().getWorkingDirLock();
		FileWriter fileWriter = null;
		workingDirLock.acquire();
		try {
			fileWriter = new FileWriter(bookmarksFile);
			for (String bm : bookmarks.keySet()) {
				Nodeid nid = bookmarks.get(bm);
				fileWriter.write(String.format("%s %s\n", nid.toString(), bm));
			}
			fileWriter.flush();
		} catch (IOException ex) {
			throw new HgIOException("Failed to serialize bookmarks", ex, bookmarksFile);
		} finally {
			try {
				if (fileWriter != null) {
					fileWriter.close();
				}
			} catch (IOException ex) {
				repo.getLog().dump(getClass(), Error, ex, null);
			}
			workingDirLock.release();
		}
	}
}
