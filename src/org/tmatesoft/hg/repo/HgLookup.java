/*
 * Copyright (c) 2010-2012 TMate Software Ltd
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

import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;

import org.tmatesoft.hg.core.HgBadArgumentException;
import org.tmatesoft.hg.core.HgRepositoryNotFoundException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DataAccessProvider;

/**
 * Utility methods to find Mercurial repository at a given location
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgLookup {

	private ConfigFile globalCfg;
	private SessionContext sessionContext;
	
	public HgLookup() {
	}
	
	public HgLookup(SessionContext ctx) {
		sessionContext = ctx;
	}

	public HgRepository detectFromWorkingDir() throws HgRepositoryNotFoundException {
		return detect(System.getProperty("user.dir"));
	}

	public HgRepository detect(String location) throws HgRepositoryNotFoundException {
		return detect(new File(location));
	}

	// look up in specified location and above
	public HgRepository detect(File location) throws HgRepositoryNotFoundException {
		try {
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
				throw new HgRepositoryNotFoundException(String.format("Can't locate .hg/ directory of Mercurial repository in %s nor in parent dirs", location)).setLocation(location.getPath());
			}
			String repoPath = repository.getParentFile().getCanonicalPath();
			return new HgRepository(getContext(), repoPath, repository);
		} catch (IOException ex) {
			HgRepositoryNotFoundException t = new HgRepositoryNotFoundException("Failed to access repository");
			t.setLocation(location.getPath()).initCause(ex);
			throw t;
		}
	}
	
	public HgBundle loadBundle(File location) throws HgRepositoryNotFoundException {
		if (location == null || !location.canRead()) {
			throw new HgRepositoryNotFoundException(String.format("Can't read file %s", location)).setLocation(String.valueOf(location));
		}
		return new HgBundle(getContext(), new DataAccessProvider(getContext()), location).link();
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
		return new HgRemoteRepository(getContext(), url);
	}
	
	public HgRemoteRepository detect(URL url) throws HgBadArgumentException {
		if (url == null) {
			throw new IllegalArgumentException();
		}
		if (Boolean.FALSE.booleanValue()) {
			throw HgRepository.notImplemented();
		}
		return new HgRemoteRepository(getContext(), url);
	}

	private ConfigFile getGlobalConfig() {
		if (globalCfg == null) {
			globalCfg = new ConfigFile(getContext());
			try {
				globalCfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
			} catch (HgInvalidFileException ex) {
				// XXX perhaps, makes sense to let caller/client know that we've failed to read global config? 
				getContext().getLog().dump(getClass(), Warn, ex, null);
			}
		}
		return globalCfg;
	}

	private SessionContext getContext() {
		if (sessionContext == null) {
			sessionContext = new BasicSessionContext(null);
		}
		return sessionContext;
	}
}
