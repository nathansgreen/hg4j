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
package org.tmatesoft.hg.console;

import static org.tmatesoft.hg.core.Nodeid.NULL;

import java.io.File;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRemoteRepository.RemoteBranch;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * WORK IN PROGRESS, DO NOT USE
 * hg outgoing
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Outgoing {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		String key = "hg4j-gc";
		ConfigFile cfg = new Internals().newConfigFile();
		cfg.addLocation(new File(System.getProperty("user.home"), ".hgrc"));
		String server = cfg.getSection("paths").get(key);
		if (server == null) {
			throw new HgException(String.format("Can't find server %s specification in the config", key));
		}
		HgRemoteRepository hgRemote = new HgLookup().detect(new URL(server));

		HgChangelog.ParentWalker pw = hgRepo.getChangelog().new ParentWalker();
		pw.init();
		
		List<Nodeid> commonKnown = findCommonWithRemote(pw, hgRemote);
		dump("Nodes known to be both locally and at remote server", commonKnown);
		// sanity check
		for (Nodeid n : commonKnown) {
			if (!pw.knownNode(n)) {
				throw new HgException("Unknown node reported as common:" + n);
			}
		}
		// find all local children of commonKnown
		List<Nodeid> result = pw.childrenOf(commonKnown);
		dump("Result", result);
	}
	
	private static List<Nodeid> findCommonWithRemote(HgChangelog.ParentWalker pwLocal, HgRemoteRepository hgRemote) throws HgException {
		List<Nodeid> remoteHeads = hgRemote.heads();
		LinkedList<Nodeid> common = new LinkedList<Nodeid>(); // these remotes are known in local
		LinkedList<Nodeid> toQuery = new LinkedList<Nodeid>(); // these need further queries to find common
		for (Nodeid rh : remoteHeads) {
			if (pwLocal.knownNode(rh)) {
				common.add(rh);
			} else {
				toQuery.add(rh);
			}
		}
		if (toQuery.isEmpty()) {
			return common; 
		}
		LinkedList<RemoteBranch> checkUp2Head = new LinkedList<RemoteBranch>(); // branch.root and branch.head are of interest only.
		// these are branches with unknown head but known root, which might not be the last common known,
		// i.e. there might be children changeset that are also available at remote, [..?..common-head..remote-head] - need to 
		// scroll up to common head.
		while (!toQuery.isEmpty()) {
			List<RemoteBranch> remoteBranches = hgRemote.branches(toQuery);	//head, root, first parent, second parent
			toQuery.clear();
			while(!remoteBranches.isEmpty()) {
				RemoteBranch rb = remoteBranches.remove(0);
				// I assume branches remote call gives branches with head equal to what I pass there, i.e.
				// that I don't need to check whether rb.head is unknown.
				if (pwLocal.knownNode(rb.root)) {
					// we known branch start, common head is somewhere in its descendants line  
					checkUp2Head.add(rb);
				} else {
					// dig deeper in the history, if necessary
					if (!NULL.equals(rb.p1) && !pwLocal.knownNode(rb.p1)) {
						toQuery.add(rb.p1);
					}
					if (!NULL.equals(rb.p2) && !pwLocal.knownNode(rb.p2)) {
						toQuery.add(rb.p2);
					}
				}
			}
		}
		// can't check nodes between checkUp2Head element and local heads, remote might have distinct descendants sequence
		for (RemoteBranch rb : checkUp2Head) {
			// rb.root is known locally
			List<Nodeid> remoteRevisions = hgRemote.between(rb.head, rb.root);
				// between gives result from head to root, I'd like to go in reverse direction
			Collections.reverse(remoteRevisions);
			if (remoteRevisions.isEmpty()) {
				// head is immediate child
				common.add(rb.root);
			} else {
				Nodeid root = rb.root;
				while(!remoteRevisions.isEmpty()) {
					Nodeid n = remoteRevisions.remove(0);
					if (pwLocal.knownNode(n)) {
						if (remoteRevisions.isEmpty()) {
							// this is the last known node before an unknown
							common.add(n);
							break;
						}
						if (remoteRevisions.size() == 1) {
							// there's only one left between known n and unknown head
							// this check is to save extra between query, not really essential
							Nodeid last = remoteRevisions.remove(0);
							common.add(pwLocal.knownNode(last) ? last : n);
							break;
						}
						// might get handy for next between query, to narrow search down
						root = n;
					} else {
						remoteRevisions = hgRemote.between(n, root);
						Collections.reverse(remoteRevisions);
						if (remoteRevisions.isEmpty()) {
							common.add(root);
						}
					}
				}
			}
		}
		// TODO ensure unique elements in the list
		return common;
	}

	private static void dump(String s, Collection<Nodeid> c) {
		System.out.println(s);
		for (Nodeid n : c) {
			System.out.println(n);
		}
	}
}
