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

import org.tmatesoft.hg.core.HgOutgoingCommand;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;


/**
 * <em>hg outgoing</em>
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Outgoing {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepoFacade hgRepo = new HgRepoFacade();
		if (!hgRepo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getRepository().getLocation());
			return;
		}
		// XXX perhaps, HgRepoFacade shall get detectRemote() analog (to get remote server with respect of facade's repo)
		HgRemoteRepository hgRemote = new HgLookup().detectRemote(cmdLineOpts.getSingle(""), hgRepo.getRepository());
		if (hgRemote.isInvalid()) {
			System.err.printf("Remote repository %s is not valid", hgRemote.getLocation());
			return;
		}
		//
		HgOutgoingCommand cmd = hgRepo.createOutgoingCommand();
		cmd.against(hgRemote);
		
		// find all local children of commonKnown
		List<Nodeid> result = cmd.executeLite();
		dump("Lite", result);
		//
		//
		System.out.println("Full");
		// show all, starting from next to common 
		final ChangesetDumpHandler h = new ChangesetDumpHandler(hgRepo.getRepository());
		h.complete(cmdLineOpts.getBoolean("--debug")).verbose(cmdLineOpts.getBoolean("-v", "--verbose"));
		cmd.executeFull(h);
		h.done();
	}

//	public static class ChangesetFormatter {
//		private final StringBuilder sb = new StringBuilder(1024);
//
//		public CharSequence simple(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
//			sb.setLength(0);
//			sb.append(String.format("changeset:  %d:%s\n", revisionNumber, nodeid.toString()));
//			sb.append(String.format("user:       %s\n", cset.user()));
//			sb.append(String.format("date:       %s\n", cset.dateString()));
//			sb.append(String.format("comment:    %s\n\n", cset.comment()));
//			return sb;
//		}
//	}
	

	static void dump(String s, Collection<Nodeid> c) {
		System.out.println(s);
		for (Nodeid n : c) {
			System.out.println(n);
		}
	}
}
