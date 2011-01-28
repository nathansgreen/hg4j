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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.tmatesoft.hg.core.Nodeid;

/**
 * @see http://mercurial.selenic.com/wiki/TagDesign
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgTags {
	// global tags come from ".hgtags"
	// local come from ".hg/localtags"

	private final Map<Nodeid, List<String>> globalToName;
	private final Map<Nodeid, List<String>> localToName;
	private final Map<String, List<Nodeid>> globalFromName;
	private final Map<String, List<Nodeid>> localFromName;
	
	
	/*package-local*/ HgTags() {
		globalToName =  new HashMap<Nodeid, List<String>>();
		localToName  =  new HashMap<Nodeid, List<String>>();
		globalFromName = new TreeMap<String, List<Nodeid>>();
		localFromName  = new TreeMap<String, List<Nodeid>>();
	}
	
	/*package-local*/ void readLocal(File localTags) throws IOException {
		if (localTags == null || localTags.isDirectory()) {
			throw new IllegalArgumentException(String.valueOf(localTags));
		}
		read(localTags, localToName, localFromName);
	}
	
	/*package-local*/ void readGlobal(File globalTags) throws IOException {
		if (globalTags == null || globalTags.isDirectory()) {
			throw new IllegalArgumentException(String.valueOf(globalTags));
		}
		read(globalTags, globalToName, globalFromName);
	}
	
	private void read(File f, Map<Nodeid,List<String>> nid2name, Map<String, List<Nodeid>> name2nid) throws IOException {
		if (!f.canRead()) {
			return;
		}
		BufferedReader r = null;
		try {
			r = new BufferedReader(new FileReader(f));
			read(r, nid2name, name2nid);
		} finally {
			if (r != null) {
				r.close();
			}
		}
	}
	
	private void read(BufferedReader reader, Map<Nodeid,List<String>> nid2name, Map<String, List<Nodeid>> name2nid) throws IOException {
		String line;
		while ((line = reader.readLine()) != null) {
			line = line.trim();
			if (line.length() == 0) {
				continue;
			}
			if (line.length() < 40+2 /*nodeid, space and at least single-char tagname*/) {
				System.out.println("Bad tags line:" + line); // FIXME log or otherwise report (IStatus analog?) 
				continue;
			}
			int spacePos = line.indexOf(' ');
			if (spacePos != -1) {
				assert spacePos == 40;
				final byte[] nodeidBytes = line.substring(0, spacePos).getBytes();
				Nodeid nid = Nodeid.fromAscii(nodeidBytes, 0, nodeidBytes.length);
				String tagName = line.substring(spacePos+1);
				List<Nodeid> nids = name2nid.get(tagName);
				if (nids == null) {
					nids = new LinkedList<Nodeid>();
					// tagName is substring of full line, thus need a copy to let the line be GC'ed
					// new String(tagName.toCharArray()) is more expressive, but results in 1 extra arraycopy
					tagName = new String(tagName);
					name2nid.put(tagName, nids);
				}
				// XXX repo.getNodeidCache().nodeid(nid);
				((LinkedList<Nodeid>) nids).addFirst(nid);
				List<String> revTags = nid2name.get(nid);
				if (revTags == null) {
					revTags = new LinkedList<String>();
					nid2name.put(nid, revTags);
				}
				revTags.add(tagName);
			} else {
				System.out.println("Bad tags line:" + line); // FIXME see above
			}
		}
	}

	public List<String> tags(Nodeid nid) {
		ArrayList<String> rv = new ArrayList<String>(5);
		List<String> l;
		if ((l = localToName.get(nid)) != null) {
			rv.addAll(l);
		}
		if ((l = globalToName.get(nid)) != null) {
			rv.addAll(l);
		}
		return rv;
	}

	public boolean isTagged(Nodeid nid) {
		return localToName.containsKey(nid) || globalToName.containsKey(nid);
	}

	public List<Nodeid> tagged(String tagName) {
		ArrayList<Nodeid> rv = new ArrayList<Nodeid>(5);
		List<Nodeid> l;
		if ((l = localFromName.get(tagName)) != null) {
			rv.addAll(l);
		}
		if ((l = globalFromName.get(tagName)) != null) {
			rv.addAll(l);
		}
		return rv;
	}
}
