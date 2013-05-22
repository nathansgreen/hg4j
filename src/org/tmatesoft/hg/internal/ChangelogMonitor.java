/*
 * Copyright (c) 2013 TMate Software Ltd
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

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * Track changes to a repository based on recent changelog revision.
 * TODO shall be merged with {@link RevlogChangeMonitor} and {@link FileChangeMonitor} into 
 * a single facility available from {@link SessionContext}
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ChangelogMonitor {
	private final HgRepository repo;
	private int changelogRevCount = -1;
	private Nodeid changelogLastRev = null;
	
	public ChangelogMonitor(HgRepository hgRepo) {
		repo = hgRepo;
	}
	
	// memorize the state of the repository's changelog
	public void touch() throws HgRuntimeException {
		changelogRevCount = repo.getChangelog().getRevisionCount();
		changelogLastRev = safeGetRevision(changelogRevCount-1);
	}
	
	// if present state doesn't match the one we remember
	public boolean isChanged() throws HgRuntimeException {
		int rc = repo.getChangelog().getRevisionCount();
		if (rc != changelogRevCount) {
			return true;
		}
		Nodeid r = safeGetRevision(rc-1);
		return !r.equals(changelogLastRev);
	}
	
	// handles empty repository case
	private Nodeid safeGetRevision(int revIndex) throws HgRuntimeException {
		if (revIndex >= 0) {
			return repo.getChangelog().getRevision(revIndex);
		}
		return Nodeid.NULL;
	}
}
