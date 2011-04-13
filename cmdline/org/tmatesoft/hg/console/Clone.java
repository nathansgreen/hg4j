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

import java.io.File;
import java.util.List;

import org.tmatesoft.hg.core.HgCloneCommand;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;

/**
 * Initial clone of a repository. Creates a brand new repository and populates it from specified source. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Clone {

	// ran with args: svnkit c:\temp\hg\test-clone
	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		List<String> noOptsArgs = cmdLineOpts.getList("");
		if (noOptsArgs.isEmpty()) {
			System.err.println("Need at least one argument pointing to remote server to pull changes from");
			return;
		}
		HgCloneCommand cmd = new HgCloneCommand();
		String remoteRepo = noOptsArgs.get(0);
		HgRemoteRepository hgRemote = new HgLookup().detectRemote(remoteRepo, null);
		if (hgRemote.isInvalid()) {
			System.err.printf("Remote repository %s is not valid", hgRemote.getLocation());
			return;
		}
		cmd.source(hgRemote);
		if (noOptsArgs.size() > 1) {
			cmd.destination(new File(noOptsArgs.get(1)));
		} else {
			cmd.destination(new File(System.getProperty("user.dir")));
		}
		cmd.execute();
	}
}
