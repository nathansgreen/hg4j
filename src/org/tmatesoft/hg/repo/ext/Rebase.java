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
package org.tmatesoft.hg.repo.ext;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

import org.tmatesoft.hg.core.HgBadNodeidFormatException;
import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.LineReader;
import org.tmatesoft.hg.repo.HgInvalidFileException;
import org.tmatesoft.hg.repo.HgInvalidStateException;

/**
 * Support for standard Rebase extension.
 * 
 * @see http://mercurial.selenic.com/wiki/RebaseExtension
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Rebase {
	private Internals repo;
	private Nodeid workingDirParent;
	private Nodeid destRevision;
	private Nodeid externalParent;
	private Map<Nodeid, Nodeid> state;
	private boolean collapse;
	private boolean keepOriginalRevisions;
	private boolean keepBranchNames;
	
	/*package-local*/ Rebase(Internals internalRepo) {
		repo = internalRepo;
	}

	public Rebase refresh() throws HgIOException {
		workingDirParent = null;
		destRevision = null;
		externalParent = null;
		state = null;
		File f = repo.getFileFromRepoDir("rebasestate");
		if (!f.exists()) {
			return this;
		}
		state = new HashMap<Nodeid, Nodeid>();
		try {
			LineReader lr = new LineReader(f, repo.getSessionContext().getLog());
			ArrayList<String> contents = new ArrayList<String>();
			lr.read(new LineReader.SimpleLineCollector(), contents);
			Iterator<String> it = contents.iterator();
			workingDirParent = Nodeid.fromAscii(it.next());
			destRevision = Nodeid.fromAscii(it.next());
			externalParent = Nodeid.fromAscii(it.next());
			collapse = "1".equals(it.next());
			keepOriginalRevisions = "1".equals(it.next());
			keepBranchNames = "1".equals(it.next());
			final String nullmerge = "-2";
			while (it.hasNext()) {
				String line = it.next();
				int x = line.indexOf(':');
				if (x == -1) {
					throw new HgInvalidStateException(line);
				}
				Nodeid oldRev = Nodeid.fromAscii(line.substring(0, x));
				Nodeid newRev;
				if (line.regionMatches(x+1, nullmerge, 0, nullmerge.length())) {
					newRev = null;
				} else {
					newRev = Nodeid.fromAscii(line.substring(x+1));
				}
				state.put(oldRev, newRev);
			}
		} catch (NoSuchElementException ex) {
			throw new HgIOException("Bad format of rebase state file", f);
		} catch (HgBadNodeidFormatException ex) {
			throw new HgIOException("Bad format of rebase state file", ex, f);
		} catch (HgInvalidFileException ex) {
			throw new HgIOException("Bad format of rebase state file", ex, f);
		}
		return this;
	}
	
	/**
	 * Tells whether rebase process was interrupted to manually resolve a merge 
	 * and can be resumed or aborted.
	 * 
	 * @return <code>true</code> when rebase is in progress 
	 */
	public boolean isRebaseInProgress() {
		return state != null;
	}

	public Nodeid getWorkingDirParent() {
		assert isRebaseInProgress();
		return workingDirParent;
	}
	
	public Nodeid getTarget() {
		assert isRebaseInProgress();
		return destRevision;
	}
	
	public Nodeid getExternalParent() {
		assert isRebaseInProgress();
		assert collapse;
		return externalParent;
	}
	
	public boolean isCollapse() {
		return collapse;
	}
	
	public boolean isKeepOriginalRevisions() {
		return keepOriginalRevisions;
	}

	public boolean isKeepBranchNames() {
		return keepBranchNames;
	}
}
