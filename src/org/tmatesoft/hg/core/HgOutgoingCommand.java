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

import java.util.List;

import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelledException;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgOutgoingCommand {
	private final HgRepository repo;
	private boolean includeSubrepo;

	public HgOutgoingCommand(HgRepository hgRepo) {
		repo = hgRepo;
	}

	/**
	 * Select specific branch to pull
	 * @return <code>this</code> for convenience
	 */
	public HgOutgoingCommand branch(String branch) {
		throw HgRepository.notImplemented();
	}
	
	/**
	 * 
	 * @return <code>this</code> for convenience
	 */
	public HgOutgoingCommand subrepo(boolean include) {
		includeSubrepo = include;
		throw HgRepository.notImplemented();
	}

	public List<Nodeid> executeLite(Object context) throws HgException, CancelledException {
		throw HgRepository.notImplemented();
	}

	public void executeFull(HgLogCommand.Handler handler) throws HgException, CancelledException {
		throw HgRepository.notImplemented();
	}
}
