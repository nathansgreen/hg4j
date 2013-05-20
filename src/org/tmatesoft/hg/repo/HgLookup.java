/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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
import java.net.MalformedURLException;
import java.net.URL;

import org.tmatesoft.hg.core.HgBadArgumentException;
import org.tmatesoft.hg.core.HgRepositoryNotFoundException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.RequiresFile;
import org.tmatesoft.hg.repo.HgRepoConfig.PathsSection;

/**
 * Utility methods to find Mercurial repository at a given location
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgLookup implements SessionContext.Source {

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

	/**
	 * Look up repository in specified location and above
	 * 
	 * @param location where to look for .hg directory, never <code>null</code>
	 * @return repository object, never <code>null</code>
	 * @throws HgRepositoryNotFoundException if no repository found, or repository version is not supported
	 */
	public HgRepository detect(File location) throws HgRepositoryNotFoundException {
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
		String repoPath = repository.getParentFile().getAbsolutePath();
		HgRepository rv = new HgRepository(getSessionContext(), repoPath, repository);
		int requiresFlags = rv.getImplHelper().getRequiresFlags();
		if ((requiresFlags & RequiresFile.REVLOGV1) == 0) {
			throw new HgRepositoryNotFoundException(String.format("%s: repository version is not supported (Mercurial <0.9?)", repoPath)).setLocation(location.getPath());
		}
		return rv;
	}
	
	public HgBundle loadBundle(File location) throws HgRepositoryNotFoundException {
		if (location == null || !location.canRead()) {
			throw new HgRepositoryNotFoundException(String.format("Can't read file %s", location)).setLocation(String.valueOf(location));
		}
		return new HgBundle(getSessionContext(), new DataAccessProvider(getSessionContext()), location).link();
	}
	
	/**
	 * Try to instantiate remote server using an immediate url or an url from configuration files
	 * 
	 * @param key either URL or a key from configuration file that points to remote server; if <code>null</code> or empty string, default remote location of the supplied repository (if any) is looked up
	 * @param hgRepo optional local repository to get default or otherwise configured remote location
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
			String server = null;
			if (hgRepo != null && !hgRepo.isInvalid()) {
				PathsSection ps = hgRepo.getConfiguration().getPaths();
				server = key == null || key.trim().length() == 0 ? ps.getDefault() : ps.getString(key, null); // XXX Java 1.5 isEmpty() 
			} else if (key == null || key.trim().length() == 0) {
				throw new HgBadArgumentException("Can't look up empty key in a global configuration", null);
			}
			if (server == null) {
				server = getGlobalConfig().getSection("paths").get(key);
			}
			if (server == null) {
				throw new HgBadArgumentException(String.format("Can't find server %s specification in the config", key), toReport);
			}
			try {
				url = new URL(server);
			} catch (MalformedURLException ex) {
				throw new HgBadArgumentException(String.format("Found %s server spec in the config, but failed to initialize with it", key), ex);
			}
		}
		return new HgRemoteRepository(getSessionContext(), url);
	}
	
	public HgRemoteRepository detect(URL url) throws HgBadArgumentException {
		if (url == null) {
			throw new IllegalArgumentException();
		}
		if (Boolean.FALSE.booleanValue()) {
			throw Internals.notImplemented();
		}
		return new HgRemoteRepository(getSessionContext(), url);
	}

	private ConfigFile getGlobalConfig() {
		if (globalCfg == null) {
			globalCfg = new ConfigFile(getSessionContext());
			try {
				globalCfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
			} catch (HgInvalidFileException ex) {
				// XXX perhaps, makes sense to let caller/client know that we've failed to read global config? 
				getSessionContext().getLog().dump(getClass(), Warn, ex, null);
			}
		}
		return globalCfg;
	}

	public SessionContext getSessionContext() {
		if (sessionContext == null) {
			sessionContext = new BasicSessionContext(null);
		}
		return sessionContext;
	}
}
