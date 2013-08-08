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

import java.io.CharArrayWriter;
import java.io.IOException;
import java.io.InputStream;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import org.tmatesoft.hg.auth.HgAuthFailedException;
import org.tmatesoft.hg.auth.HgAuthMethod;

import com.trilead.ssh2.Connection;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class SshAuthMethod implements HgAuthMethod {

	private final Connection conn;

	public SshAuthMethod(Connection connection) {
		conn = connection;
	}

	public void tryWithUserInfo(String uriUserInfo) throws HgAuthFailedException {
		assert uriUserInfo != null && uriUserInfo.trim().length() > 0;
		int colon = uriUserInfo.indexOf(':');
		if (colon == -1) {
			String username = uriUserInfo;
			withPassword(username, null);
		} else {
			String username = uriUserInfo.substring(0, colon);
			String password = uriUserInfo.substring(colon+1);
			withPassword(username, password);
		}
		return;
	}

	public void noCredentials() throws HgAuthFailedException {
		try {
			String username = System.getProperty("user.name");
			if (!conn.authenticateWithNone(username)) {
				throw authFailed(username);
			}
		} catch (IOException ex) {
			throw commFailed(ex);
		}
	}

	public void withPublicKey(String username, InputStream privateKey, String passphrase) throws HgAuthFailedException {
		if (username == null) {
			// FIXME AuthFailure and AuthFailed or similar distinct exceptions to tell true authentication issues from
			// failures around it.
			throw new HgAuthFailedException("Need username", null);
		}
		if (privateKey == null) {
			throw new HgAuthFailedException("Need private key", null);
		}
		CharArrayWriter a = new CharArrayWriter(2048);
		int r;
		try {
			while((r = privateKey.read()) != -1) {
				a.append((char) r);
			}
		} catch (IOException ex) {
			throw new HgAuthFailedException("Failed to read private key", ex);
		}
		try {
			boolean success = conn.authenticateWithPublicKey(username, a.toCharArray(), passphrase);
			if (!success) {
				throw authFailed(username);
			}
		} catch (IOException ex) {
			throw commFailed(ex);
		}
	}

	public void withPassword(String username, String password) throws HgAuthFailedException {
		if (username == null) {
			throw new HgAuthFailedException("Need username", null);
		}
		try {
			boolean success;
			if (password == null) {
				success = conn.authenticateWithNone(username);
			} else {
				success = conn.authenticateWithPassword(username, password);
			}
			if (!success) {
				throw authFailed(username);
			}
		} catch (IOException ex) {
			throw commFailed(ex);
		}
	}

	public void withCertificate(X509Certificate[] clientCert) throws HgAuthFailedException {
	}

	public boolean supportsPublicKey() {
		return true;
	}

	public boolean supportsPassword() {
		return true;
	}

	public boolean supportsCertificate() {
		return true;
	}

	private HgAuthFailedException commFailed(IOException ex) {
		return new HgAuthFailedException("Communication failure while authenticating", ex);
	}

	private HgAuthFailedException authFailed(String username) throws IOException {
		final String[] authMethodsLeft = conn.getRemainingAuthMethods(username);
		return new HgAuthFailedException(String.format("Failed to authenticate, other methods to try: %s", Arrays.toString(authMethodsLeft)), null);
	}
}