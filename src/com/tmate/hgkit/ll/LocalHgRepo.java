/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.File;
import java.io.IOException;

/**
 * @author artem
 */
public class LocalHgRepo extends HgRepository {

	private File repoDir;
	private final String repoLocation;

	public LocalHgRepo(String repositoryPath) {
		setInvalid(true);
		repoLocation = repositoryPath;
	}
	
	public LocalHgRepo(File repositoryRoot) throws IOException {
		assert ".hg".equals(repositoryRoot.getName()) && repositoryRoot.isDirectory();
		setInvalid(false);
		repoDir = repositoryRoot;
		repoLocation = repositoryRoot.getParentFile().getCanonicalPath();
	}

	@Override
	public String getLocation() {
		return repoLocation;
	}
}
