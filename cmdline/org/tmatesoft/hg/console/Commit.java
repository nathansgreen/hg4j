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

import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.util.Outcome;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Commit {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args, Collections.<String>emptySet());
		HgRepoFacade repo = new HgRepoFacade();
		if (!repo.init(cmdLineOpts.findRepository())) {
			System.err.printf("Can't find repository in: %s\n", repo.getRepository().getLocation());
			return;
		}
		String message = cmdLineOpts.getSingle("-m", "--message");
		if (message == null) {
			System.err.println("Need a commit message");
			return;
		}
		HgCommitCommand cmd = repo.createCommitCommand();
		cmd.message(message);
		Outcome o = cmd.execute();
		if (!o.isOk()) {
			System.err.println(o.getMessage());
			return;
		}
		System.out.printf("New changeset: %s\n", cmd.getCommittedRevision().shortNotation());
	}
}
