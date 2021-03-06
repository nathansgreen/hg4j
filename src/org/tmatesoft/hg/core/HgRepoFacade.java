/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgLookup;

/**
 * Starting point for the library.
 * <p>Sample use:
 * <pre>
 *  HgRepoFacade f = new HgRepoFacade();
 *  f.initFrom(System.getenv("whatever.repo.location"));
 *  HgStatusCommand statusCmd = f.createStatusCommand();
 *  HgStatusCommand.Handler handler = ...;
 *  statusCmd.execute(handler);
 * </pre>
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgRepoFacade implements SessionContext.Source {
	private HgRepository repo;
	private final SessionContext context;

	public HgRepoFacade() {
		this(new BasicSessionContext(null));
	}
	
	public HgRepoFacade(SessionContext ctx) {
		if (ctx == null) {
			throw new IllegalArgumentException();
		}
		context = ctx;
	}
	
	/**
	 * @param hgRepo
	 * @return true on successful initialization
	 * @throws IllegalArgumentException when argument is null 
	 */
	public boolean init(HgRepository hgRepo) {
		if (hgRepo == null) {
			throw new IllegalArgumentException();
		}
		repo = hgRepo;
		return !repo.isInvalid();
	}

	/**
	 * Tries to find repository starting from the current working directory.
	 * 
	 * @return <code>true</code> if found valid repository
	 * @throws HgRepositoryNotFoundException if no repository found in working directory
	 */
	public boolean init() throws HgRepositoryNotFoundException {
		repo = new HgLookup(context).detectFromWorkingDir();
		return repo != null && !repo.isInvalid();
	}
	
	/**
	 * Looks up Mercurial repository starting from specified location and up to filesystem root.
	 * 
	 * @param repoLocation path to any folder within structure of a Mercurial repository.
	 * @return <code>true</code> if found valid repository 
	 * @throws HgRepositoryNotFoundException if there's no repository at specified location
	 * @throws IllegalArgumentException if argument is <code>null</code>
	 */
	public boolean initFrom(File repoLocation) throws HgRepositoryNotFoundException {
		if (repoLocation == null) {
			throw new IllegalArgumentException();
		}
		repo = new HgLookup(context).detect(repoLocation);
		return repo != null && !repo.isInvalid();
	}
	
	public HgRepository getRepository() {
		if (repo == null) {
			throw new IllegalStateException("Call any of #init*() methods first first");
		}
		return repo;
	}
	
	public SessionContext getSessionContext() {
		return context;
	}
	
	/**
	 * This factory method doesn't need this facade to be initialized with a repository.
	 * @return command instance, never <code>null</code>
	 */
	public HgInitCommand createInitCommand() {
		return new HgInitCommand(new HgLookup(context));
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

	public HgOutgoingCommand createOutgoingCommand() {
		return new HgOutgoingCommand(repo);
	}

	public HgIncomingCommand createIncomingCommand() {
		return new HgIncomingCommand(repo);
	}
	
	public HgCloneCommand createCloneCommand() {
		return new HgCloneCommand();
	}
	
	public HgUpdateConfigCommand createUpdateRepositoryConfigCommand() {
		return HgUpdateConfigCommand.forRepository(repo);
	}
	
	public HgAddRemoveCommand createAddRemoveCommand() {
		return new HgAddRemoveCommand(repo);
	}
	
	public HgCheckoutCommand createCheckoutCommand() {
		return new HgCheckoutCommand(repo);
	}
	
	public HgRevertCommand createRevertCommand() {
		return new HgRevertCommand(repo);
	}
	
	public HgAnnotateCommand createAnnotateCommand() {
		return new HgAnnotateCommand(repo);
	}
	
	public HgCommitCommand createCommitCommand() {
		return new HgCommitCommand(repo);
	}
	
	public HgDiffCommand createDiffCommand() {
		return new HgDiffCommand(repo);
	}

	public HgPushCommand createPushCommand() {
		return new HgPushCommand(repo);
	}
	
	public HgPullCommand createPullCommand() {
		return new HgPullCommand(repo);
	}

	public HgMergeCommand createMergeCommand() {
		return new HgMergeCommand(repo);
	}
}
