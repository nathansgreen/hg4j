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

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.LineReader;
import org.tmatesoft.hg.util.LogFacility;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class HgBookmarks {
	private final Internals internalRepo;
	private Map<String, Nodeid> bookmarks = Collections.emptyMap();
	private String activeBookmark; 

	HgBookmarks(Internals internals) {
		internalRepo = internals;
	}
	
	/*package-local*/ void read() throws HgInvalidControlFileException {
		final LogFacility log = internalRepo.getContext().getLog();
		final HgRepository repo = internalRepo.getRepo();
		File all = internalRepo.getFileFromRepoDir(HgRepositoryFiles.Bookmarks.getName());
		LinkedHashMap<String, Nodeid> bm = new LinkedHashMap<String, Nodeid>();
		if (all.canRead()) {
			LineReader lr1 = new LineReader(all, log);
			ArrayList<String> c = new ArrayList<String>();
			lr1.read(new LineReader.SimpleLineCollector(), c);
			for (String s : c) {
				int x = s.indexOf(' ');
				try {
					if (x > 0) {
						Nodeid nid = Nodeid.fromAscii(s.substring(0, x));
						String name = new String(s.substring(x+1));
						if (repo.getChangelog().isKnown(nid)) {
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
		
		activeBookmark = null;
		File active = internalRepo.getFileFromRepoDir(HgRepositoryFiles.BookmarksCurrent.getName());
		if (active.canRead()) {
			LineReader lr2 = new LineReader(active, log);
			ArrayList<String> c = new ArrayList<String>(2);
			lr2.read(new LineReader.SimpleLineCollector(), c);
			if (c.size() > 0) {
				activeBookmark = c.get(0);
			}
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
}
