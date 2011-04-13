/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
package org.tmatesoft.hg.repo;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import org.tmatesoft.hg.core.HgBadArgumentException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.Internals;

/**
 * Utility methods to find Mercurial repository at a given location
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgLookup {

	private ConfigFile globalCfg;

	public HgRepository detectFromWorkingDir() throws HgException {
		return detect(System.getProperty("user.dir"));
	}

	public HgRepository detect(String location) throws HgException {
		return detect(new File(location));
	}

	// look up in specified location and above
	public HgRepository detect(File location) throws HgException {
		File dir = location.getAbsoluteFile();
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
			// return invalid repository
			return new HgRepository(location.getPath());
		}
		try {
			String repoPath = repository.getParentFile().getCanonicalPath();
			return new HgRepository(repoPath, repository);
		} catch (IOException ex) {
			throw new HgException(location.toString(), ex);
		}
	}
	
	public HgBundle loadBundle(File location) throws HgException {
		if (location == null || !location.canRead()) {
			throw new IllegalArgumentException();
		}
		return new HgBundle(new DataAccessProvider(), location).link();
	}
	
	/**
	 * Try to instantiate remote server.
	 * @param key either URL or a key from configuration file that points to remote server  
	 * @param hgRepo <em>NOT USED YET<em> local repository that may have extra config, or default remote location
	 * @return an instance featuring access to remote repository, check {@link HgRemoteRepository#isInvalid()} before actually using it
	 * @throws HgBadArgumentException if anything is wrong with the remote server's URL
	 */
	public HgRemoteRepository detectRemote(String key, HgRepository hgRepo) throws HgBadArgumentException {
		URL url;
		Exception toReport;
		try {
			url = new URL(key);
			toReport = null;
		} catch (MalformedURLException ex) {
			url = null;
			toReport = ex;
		}
		if (url == null) {
			String server = getGlobalConfig().getSection("paths").get(key);
			if (server == null) {
				throw new HgBadArgumentException(String.format("Can't find server %s specification in the config", key), toReport);
			}
			try {
				url = new URL(server);
			} catch (MalformedURLException ex) {
				throw new HgBadArgumentException(String.format("Found %s server spec in the config, but failed to initialize with it", key), ex);
			}
		}
		return new HgRemoteRepository(url);
	}
	
	public HgRemoteRepository detect(URL url) throws HgException {
		if (url == null) {
			throw new IllegalArgumentException();
		}
		if (Boolean.FALSE.booleanValue()) {
			throw HgRepository.notImplemented();
		}
		return new HgRemoteRepository(url);
	}

	private ConfigFile getGlobalConfig() {
		if (globalCfg == null) {
			globalCfg = new Internals().newConfigFile();
			globalCfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
		}
		return globalCfg;
	}
}
