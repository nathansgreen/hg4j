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
package org.tmatesoft.hg.console;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;


/**
 * WORK IN PROGRESS, DO NOT USE
 * hg outgoing
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Outgoing {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		HgRemoteRepository hgRemote = new HgLookup().detectRemote(cmdLineOpts.getSingle(""), hgRepo);
		if (hgRemote.isInvalid()) {
			System.err.printf("Remote repository %s is not valid", hgRemote.getLocation());
			return;
		}

		HgChangelog changelog = hgRepo.getChangelog();
		HgChangelog.ParentWalker pw = changelog.new ParentWalker();
		pw.init();
		
		RepositoryComparator repoCompare = new RepositoryComparator(pw, hgRemote);
		repoCompare.compare(null);
		List<Nodeid> commonKnown = repoCompare.getCommon();
		dump("Nodes known to be both locally and at remote server", commonKnown);
		// sanity check
		for (Nodeid n : commonKnown) {
			if (!pw.knownNode(n)) {
				throw new HgException("Unknown node reported as common:" + n);
			}
		}
		// find all local children of commonKnown
		List<Nodeid> result = pw.childrenOf(commonKnown);
		dump("Lite", result);
		// another approach to get all changes after common:
		// find index of earliest revision, and report all that were later
		int earliestRevision = Integer.MAX_VALUE;
		for (Nodeid n : commonKnown) {
			if (pw.childrenOf(Collections.singletonList(n)).isEmpty()) {
				// there might be (old) nodes, known both locally and remotely, with no children
				// hence, we don't need to consider their local revision number
				continue;
			}
			int lr = changelog.getLocalRevision(n);
			if (lr < earliestRevision) {
				earliestRevision = lr;
			}
		}
		if (earliestRevision < 0 || earliestRevision >= changelog.getLastRevision()) {
			throw new HgBadStateException(String.format("Invalid index of common known revision: %d in total of %d", earliestRevision, 1+changelog.getLastRevision()));
		}
		System.out.println("Full");
		// show all, starting from next to common 
		changelog.range(earliestRevision+1, changelog.getLastRevision(), new HgChangelog.Inspector() {
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				System.out.printf("changeset:  %d:%s\n", revisionNumber, nodeid.toString());
				System.out.printf("user:       %s\n", cset.user());
				System.out.printf("date:       %s\n", cset.dateString());
				System.out.printf("comment:    %s\n\n", cset.comment());
			}
		});
	}
	

	private static void dump(String s, Collection<Nodeid> c) {
		System.out.println(s);
		for (Nodeid n : c) {
			System.out.println(n);
		}
	}
}
