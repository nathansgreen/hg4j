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
package org.tmatesoft.hg.auth;

import java.io.InputStream;
import java.security.cert.X509Certificate;

import org.tmatesoft.hg.internal.Experimental;

/**
 * Clients do not implement this interface, instead, they invoke appropriate authentication method
 * once they got user input
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 * @since 1.2
 */
@Experimental(reason="Provisional API. Work in progress")
public interface HgAuthMethod {
	public void noCredentials() throws HgAuthFailedException;
	public boolean supportsPassword();
	public void withPassword(String username, String password) throws HgAuthFailedException;
	public boolean supportsPublicKey();
	public void withPublicKey(String username, InputStream privateKey, String passphrase) throws HgAuthFailedException;
	public boolean supportsCertificate();
	public void withCertificate(X509Certificate[] clientCertChain) throws HgAuthFailedException;
}