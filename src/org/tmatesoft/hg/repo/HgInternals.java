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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.*;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;

import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.util.Path;


/**
 * DO NOT USE THIS CLASS, INTENDED FOR TESTING PURPOSES.
 * 
 * Debug helper, to access otherwise restricted (package-local) methods
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.

 */
public class HgInternals {

	private final HgRepository repo;

	public HgInternals(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public void dumpDirstate() {
		repo.loadDirstate().dump();
	}

	public boolean[] checkIgnored(String... toCheck) {
		HgIgnore ignore = repo.getIgnore();
		boolean[] rv = new boolean[toCheck.length];
		for (int i = 0; i < toCheck.length; i++) {
			rv[i] = ignore.isIgnored(Path.create(toCheck[i]));
		}
		return rv;
	}

	public File getRepositoryDir() {
		return repo.getRepositoryRoot();
	}
	
	public ConfigFile getRepoConfig() {
		return repo.getConfigFile();
	}

	// in fact, need a setter for this anyway, shall move to internal.Internals perhaps?
	public String getNextCommitUsername() {
		String hgUser = System.getenv("HGUSER");
		if (hgUser != null && hgUser.trim().length() > 0) {
			return hgUser.trim();
		}
		String configValue = getRepoConfig().getString("ui", "username", null);
		if (configValue != null) {
			return configValue;
		}
		String email = System.getenv("EMAIL");
		if (email != null && email.trim().length() > 0) {
			return email;
		}
		String username = System.getProperty("user.name");
		try {
			String hostname = InetAddress.getLocalHost().getHostName();
			return username + '@' + hostname; 
		} catch (UnknownHostException ex) {
			return username;
		}
	}

	// Convenient check of local revision number for validity (not all negative values are wrong as long as we use negative constants)
	public static boolean wrongLocalRevision(int rev) {
		return rev < 0 && rev != TIP && rev != WORKING_COPY && rev != BAD_REVISION; 
	}
}
