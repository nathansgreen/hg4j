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

import java.io.File;

import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgLookup;

/**
 * Starting point for the library.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgRepoFacade {
	private HgRepository repo;

	public HgRepoFacade() {
	}
	
	public boolean init(HgRepository hgRepo) {
		if (hgRepo == null) {
			throw new IllegalArgumentException();
		}
		repo = hgRepo;
		return !repo.isInvalid();
	}

	public boolean init() throws Exception /*FIXME RepoInitException*/ {
		repo = new HgLookup().detectFromWorkingDir();
		return repo != null && !repo.isInvalid();
	}
	
	public boolean initFrom(File repoLocation) throws Exception {
		repo = new HgLookup().detect(repoLocation.getCanonicalPath());
		return repo != null && !repo.isInvalid();
	}
	
	public HgRepository getRepository() {
		if (repo == null) {
			throw new IllegalStateException("Call any of #init*() methods first first");
		}
		return repo;
	}

	public HgLogCommand createLogCommand() {
		return new HgLogCommand(repo/*, getCommandContext()*/);
	}

	public HgStatusCommand createStatusCommand() {
		return new HgStatusCommand(repo/*, getCommandContext()*/);
	}

	public HgCatCommand createCatCommand() {
		return new HgCatCommand(repo);
	}

	public HgManifestCommand createManifestCommand() {
		return new HgManifestCommand(repo);
	}
}
