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

import java.io.File;
import java.util.List;

import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;

/**
 * WORK IN PROGRESS, DO NOT USE
 * 
 * @see http://mercurial.selenic.com/wiki/WireProtocol
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgRemoteRepository {

	// WireProtocol wiki: roots = a list of the latest nodes on every service side changeset branch that both the client and server know about. 
	public HgBundle getChanges(List<Nodeid> roots) throws HgException {
		return new HgLookup().loadBundle(new File("/temp/hg/hg-bundle-000000000000-gz.tmp"));
	}
}
