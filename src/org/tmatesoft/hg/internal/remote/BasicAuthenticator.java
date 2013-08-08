/*
 * Copyright (c) 2013 TMate Software Ltd
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
package org.tmatesoft.hg.internal.remote;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.tmatesoft.hg.auth.HgAuthFailedException;
import org.tmatesoft.hg.auth.HgAuthMethod;
import org.tmatesoft.hg.auth.HgAuthenticator;
import org.tmatesoft.hg.repo.HgRemoteRepository.RemoteDescriptor;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BasicAuthenticator implements HgAuthenticator {
	private final LogFacility log;

	public BasicAuthenticator(LogFacility logFacility) {
		log = logFacility;
	}
			
	public void authenticate(RemoteDescriptor rd, HgAuthMethod authMethod) throws HgAuthFailedException {
		if (authMethod.supportsPublicKey()) {
			if (tryPlatformDefaultKeyLocations(rd, authMethod)) {
				return;
			}
		}
		authMethod.noCredentials();
	}

	// return true is successfully aithenticated
	protected boolean tryPlatformDefaultKeyLocations(RemoteDescriptor rd, HgAuthMethod authMethod) {
		final String userHome = System.getProperty("user.home");
		File sshDir = new File(userHome, ".ssh");
		if (!sshDir.isDirectory()) {
			return false;
		}
		final String username = System.getProperty("user.name");
		for (String fn : new String[] { "id_rsa", "id_dsa", "identity"}) {
			File id = new File(sshDir, fn);
			if (!id.canRead()) {
				continue;
			}
			try {
				FileInputStream fis = new FileInputStream(id);
				authMethod.withPublicKey(username, fis, null);
				fis.close();
				return true;
			} catch (IOException ex) {
				log.dump(getClass(), Severity.Warn, ex, String.format("Attempting default ssh identity key locations: %s", id));
				// ignore
			} catch (HgAuthFailedException ex) {
				log.dump(getClass(), Severity.Debug, ex, String.format("Attempting default ssh identity key locations: %s", id));
				// ignore
			}
		}
		return false;
	}
}