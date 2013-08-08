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

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.repo.HgRemoteRepository.RemoteDescriptor;

/**
 * Client may implement this interface if they need more control over authentication process.
 * 
 * @see SessionContext#getAuthenticator(RemoteDescriptor)
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 * @since 1.2
 */
@Experimental(reason="Provisional API. Work in progress")
public interface HgAuthenticator {
	// XXX either another AuthMethod or a separate #authenticate
	// to perform server check. Alternatively, as methods in AuthMethod
	public void authenticate(RemoteDescriptor rd, HgAuthMethod authMethod) throws HgAuthFailedException;
}