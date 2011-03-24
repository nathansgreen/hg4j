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
import java.net.URL;
import java.util.Collections;
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
	
	HgRemoteRepository(URL url) {
	}
	
	public List<Nodeid> heads() {
		return Collections.singletonList(Nodeid.fromAscii("71ddbf8603e8e09d54ac9c5fe4bb5ae824589f1d"));
//		return Collections.emptyList();
	}
	
	public List<Nodeid> between(Nodeid base, Nodeid tip) {
		return Collections.emptyList();
	}

	public List<RemoteBranch> branches(List<Nodeid> nodes) {
		return Collections.emptyList();
	}

	// WireProtocol wiki: roots = a list of the latest nodes on every service side changeset branch that both the client and server know about. 
	public HgBundle getChanges(List<Nodeid> roots) throws HgException {
		return new HgLookup().loadBundle(new File("/temp/hg/hg-bundle-000000000000-gz.tmp"));
	}

	public static final class RemoteBranch {
		public final Nodeid head, root, p1, p2;
		
		public RemoteBranch(Nodeid h, Nodeid r, Nodeid parent1, Nodeid parent2) {
			head = h;
			root = r;
			p1 = parent1;
			p2 = parent2;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (false == obj instanceof RemoteBranch) {
				return false;
			}
			RemoteBranch o = (RemoteBranch) obj;
			return head.equals(o.head) && root.equals(o.root) && (p1 == null && o.p1 == null || p1.equals(o.p1)) && (p2 == null && o.p2 == null || p2.equals(o.p2));
		}
	}
}
