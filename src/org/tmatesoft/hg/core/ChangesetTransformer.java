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
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.core;

import java.util.Set;

import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.util.PathPool;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Bridges {@link HgChangelog.RawChangeset} with high-level {@link HgChangeset} API
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
/*package-local*/ class ChangesetTransformer implements HgChangelog.Inspector {
	private final HgLogCommand.Handler handler;
	private final HgChangeset changeset;
	private Set<String> branches;

	// repo and delegate can't be null, parent walker can
	public ChangesetTransformer(HgRepository hgRepo, HgLogCommand.Handler delegate, HgChangelog.ParentWalker pw) {
		if (hgRepo == null || delegate == null) {
			throw new IllegalArgumentException();
		}
		HgStatusCollector statusCollector = new HgStatusCollector(hgRepo);
		// files listed in a changeset don't need their names to be rewritten (they are normalized already)
		PathPool pp = new PathPool(new PathRewrite.Empty());
		statusCollector.setPathPool(pp);
		changeset = new HgChangeset(statusCollector, pp);
		changeset.setParentHelper(pw);
		handler = delegate;
	}
	
	public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
		if (branches != null && !branches.contains(cset.branch())) {
			return;
		}

		changeset.init(revisionNumber, nodeid, cset);
		handler.next(changeset);
	}
	
	public void limitBranches(Set<String> branches) {
		this.branches = branches;
	}
}