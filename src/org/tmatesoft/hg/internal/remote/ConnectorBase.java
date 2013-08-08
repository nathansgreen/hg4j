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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collection;
import java.util.List;

import org.tmatesoft.hg.auth.HgAuthFailedException;
import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgRemoteRepository.RemoteDescriptor;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgRemoteRepository.Range;

/**
 * An abstract base class for {@link Connector} implementations,
 * to keep binary compatibility once {@link Connector} interface changes.
 * 
 * <p>Provides default implementation for {@link #getServerLocation()} that hides user credentials from uri, if any
 * 
 * <p>Present method implementations are not expected to be invoked and do nothing, this may change in future to return 
 * reasonable error objects. New methods, added to {@link Connector}, will get default implementation in this class as well.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public abstract class ConnectorBase implements Connector {
	protected URI uri;
	
	protected ConnectorBase() {
	}
	
	protected void setURI(URI uri) {
		this.uri = uri;
	}

	// clients may invoke this method, or call #setURI(URI) directly
	public void init(RemoteDescriptor remote, SessionContext sessionContext, Object globalConfig) throws HgRuntimeException {
		setURI(remote.getURI());
	}

	public String getServerLocation() {
		if (uri == null) {
			return "";
		}
		if (uri.getUserInfo() == null) {
			return uri.toString();
		}
		if (uri.getPort() != -1) {
			return String.format("%s://%s:%d%s", uri.getScheme(), uri.getHost(), uri.getPort(), uri.getPath());
		} else {
			return String.format("%s://%s%s", uri.getScheme(), uri.getHost(), uri.getPath());
		}
	}

	public void connect() throws HgAuthFailedException, HgRemoteConnectionException, HgRuntimeException {
	}

	public void disconnect() throws HgRemoteConnectionException, HgRuntimeException {
	}

	public void sessionBegin() throws HgRemoteConnectionException, HgRuntimeException {
	}

	public void sessionEnd() throws HgRemoteConnectionException, HgRuntimeException {
	}

	public String getCapabilities() throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public InputStream heads() throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public InputStream between(Collection<Range> ranges) throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public InputStream branches(List<Nodeid> nodes) throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public InputStream changegroup(List<Nodeid> roots) throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public OutputStream unbundle(long outputLen, List<Nodeid> remoteHeads) throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public InputStream pushkey(String opName, String namespace, String key, String oldValue, String newValue) throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}

	public InputStream listkeys(String namespace, String actionName) throws HgRemoteConnectionException, HgRuntimeException {
		return null;
	}
}
