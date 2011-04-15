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
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.RepositoryComparator;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * WORK IN PROGRESS, DO NOT USE
 * hg outgoing
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Outgoing {

	public static void main(String[] args) throws Exception {
		final boolean debug = true; // perhaps, use hg4j.remote.debug or own property?
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
		if (debug) {
			List<Nodeid> commonKnown = repoCompare.getCommon();
			dump("Nodes known to be both locally and at remote server", commonKnown);
		}
		// find all local children of commonKnown
		List<Nodeid> result = repoCompare.getLocalOnlyRevisions();
		dump("Lite", result);
		//
		//
		System.out.println("Full");
		// show all, starting from next to common 
		repoCompare.visitLocalOnlyRevisions(new HgChangelog.Inspector() {
			private final ChangesetFormatter formatter = new ChangesetFormatter();
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				System.out.println(formatter.simple(revisionNumber, nodeid, cset));
			}
		});
	}

	public static class ChangesetFormatter {
		private final StringBuilder sb = new StringBuilder(1024);

		public CharSequence simple(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
			sb.setLength(0);
			sb.append(String.format("changeset:  %d:%s\n", revisionNumber, nodeid.toString()));
			sb.append(String.format("user:       %s\n", cset.user()));
			sb.append(String.format("date:       %s\n", cset.dateString()));
			sb.append(String.format("comment:    %s\n\n", cset.comment()));
			return sb;
		}
	}
	

	private static void dump(String s, Collection<Nodeid> c) {
		System.out.println(s);
		for (Nodeid n : c) {
			System.out.println(n);
		}
	}
}
