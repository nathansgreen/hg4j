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

import org.tmatesoft.hg.core.HgRemoteConnectionException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgRemoteRepository.Range;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface Connector {
	static final String CMD_HELLO = "hello"; // TODO enum
	static final String CMD_CAPABILITIES = "capabilities";
	static final String CMD_HEADS = "heads";
	static final String CMD_BETWEEN = "between";
	static final String CMD_BRANCHES = "branches";
	static final String CMD_CHANGEGROUP = "changegroup";
	static final String CMD_UNBUNDLE = "unbundle";
	static final String CMD_PUSHKEY = "pushkey";
	static final String CMD_LISTKEYS = "listkeys";
	static final String NS_BOOKMARKS = "bookmarks";
	static final String NS_PHASES = "phases";
	
	void init(URI uri, SessionContext sessionContext, Object globalConfig) throws HgRuntimeException;
	String getServerLocation();
	//
	void connect() throws HgRemoteConnectionException, HgRuntimeException;
	void disconnect() throws HgRemoteConnectionException, HgRuntimeException;
	void sessionBegin() throws HgRemoteConnectionException, HgRuntimeException;
	void sessionEnd() throws HgRemoteConnectionException, HgRuntimeException;
	// 
	String getCapabilities() throws HgRemoteConnectionException, HgRuntimeException;

	InputStream heads() throws HgRemoteConnectionException, HgRuntimeException;
	InputStream between(Collection<Range> ranges) throws HgRemoteConnectionException, HgRuntimeException;
	InputStream branches(List<Nodeid> nodes) throws HgRemoteConnectionException, HgRuntimeException;
	InputStream changegroup(List<Nodeid> roots) throws HgRemoteConnectionException, HgRuntimeException;
	OutputStream unbundle(long outputLen, List<Nodeid> remoteHeads) throws HgRemoteConnectionException, HgRuntimeException;
	InputStream pushkey(String opName, String namespace, String key, String oldValue, String newValue) throws HgRemoteConnectionException, HgRuntimeException;
	InputStream listkeys(String namespace, String actionName) throws HgRemoteConnectionException, HgRuntimeException;
}
