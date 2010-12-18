/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.File;

import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.LocalHgRepo;

/**
 * @author artem
 */
public class RepositoryFinder {
	
	public HgRepository detect(String[] commandLineArgs) throws Exception {
		if (commandLineArgs.length == 0) {
			return detectFromWorkingDir();
		}
		return detect(commandLineArgs[0]);
	}

	public HgRepository detectFromWorkingDir() throws Exception {
		return detect(System.getProperty("user.dir"));
	}

	public HgRepository detect(String location) throws Exception /*FIXME Exception type, RepoInitException? */ {
		File dir = new File(location);
		File repository;
		do {
			repository = new File(dir, ".hg");
			if (repository.exists() && repository.isDirectory()) {
				break;
			}
			repository = null;
			dir = dir.getParentFile();
			
		} while(dir != null);
		if (repository == null) {
			return new LocalHgRepo(location);
		}
		return new LocalHgRepo(repository);
	}
}
