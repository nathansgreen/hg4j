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
package org.tmatesoft.hg.console;

import java.util.Collections;

import org.tmatesoft.hg.core.HgPullCommand;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;

/**
 * Basic analog to 'hg pull' command line utility
 * @since 1.2
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Pull {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, Collections.<String>emptySet());
		HgRepoFacade hgRepo = new HgRepoFacade();
		if (!hgRepo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getRepository().getLocation());
			return;
		}
		HgRemoteRepository hgRemote = new HgLookup().detectRemote(cmdLineOpts.getSingle(""), hgRepo.getRepository());
		if (hgRemote.isInvalid()) {
			System.err.printf("Remote repository %s is not valid", hgRemote.getLocation());
			return;
		}
		HgPullCommand cmd = hgRepo.createPullCommand();
		cmd.source(hgRemote);
		cmd.execute();
		System.out.printf("Sent %d changesets\n", cmd.getPulledRevisions().size());
	}

}
