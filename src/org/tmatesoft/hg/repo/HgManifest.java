/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.repo;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.RevlogStream;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgManifest extends Revlog {

	/*package-local*/ HgManifest(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo, content);
	}

	public void walk(int start, int end, final Inspector inspector) {
		RevlogStream.Inspector insp = new RevlogStream.Inspector() {

			private boolean gtg = true; // good to go

			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				if (!gtg) {
					return;
				}
				gtg = gtg && inspector.begin(revisionNumber, new Nodeid(nodeid, true));
				int i;
				String fname = null;
				String flags = null;
				Nodeid nid = null;
				for (i = 0; gtg && i < actualLen; i++) {
					int x = i;
					for( ; data[i] != '\n' && i < actualLen; i++) {
						if (fname == null && data[i] == 0) {
							fname = new String(data, x, i - x);
							x = i+1;
						}
					}
					if (i < actualLen) {
						assert data[i] == '\n'; 
						int nodeidLen = i - x < 40 ? i-x : 40;
						nid = Nodeid.fromAscii(data, x, nodeidLen);
						if (nodeidLen + x < i) {
							// 'x' and 'l' for executable bits and symlinks?
							// hg --debug manifest shows 644 for each regular file in my repo
							flags = new String(data, x + nodeidLen, i-x-nodeidLen);
						}
						gtg = gtg && inspector.next(nid, fname, flags);
					}
					nid = null;
					fname = flags = null;
				}
				gtg = gtg && inspector.end(revisionNumber);
			}
		};
		content.iterate(start, end, true, insp);
	}

	public interface Inspector {
		boolean begin(int revision, Nodeid nid);
		boolean next(Nodeid nid, String fname, String flags);
		boolean end(int revision);
	}
}