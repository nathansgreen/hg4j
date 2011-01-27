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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.console;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * WORK IN PROGRESS, DO NOT USE
 * hg out
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
		// FIXME detection of 
		List<Nodeid> base = new LinkedList<Nodeid>();
		base.add(Nodeid.fromAscii("d6d2a630f4a6d670c90a5ca909150f2b426ec88f".getBytes(), 0, 40));
		//
		// fill with all known
		Changelog.ParentWalker pw = hgRepo.getChangelog().new ParentWalker();
		pw.init();
		LinkedHashSet<Nodeid> sendToRemote = new LinkedHashSet<Nodeid>(pw.allNodes());
		dump("initial state", sendToRemote);
		// remove base and its parents
		LinkedList<Nodeid> queueToClean = new LinkedList<Nodeid>(base);
		while (!queueToClean.isEmpty()) {
			Nodeid nid = queueToClean.removeFirst();
			if (sendToRemote.remove(nid)) {
				pw.appendParentsOf(nid, queueToClean);
			}
		}
		dump("Clean from known parents", sendToRemote);
		// XXX I think sendToRemote is what we actually need here - everything local, missing from remote
		// however, if we need to send only a subset of these, need to proceed.
		LinkedList<Nodeid> result = new LinkedList<Nodeid>();
		// find among left those without parents
		for (Nodeid nid : sendToRemote) {
			Nodeid p1 = pw.firstParent(nid);
			// in fact, we may assume nulls are never part of sendToRemote
			if (p1 != null && !sendToRemote.contains(p1)) {
				Nodeid p2 = pw.secondParent(nid);
				if (p2 == null || !sendToRemote.contains(p2)) {
					result.add(nid);
				}
			}
		}
		dump("Result", result);
		// final outcome is the collection of nodes between(lastresult and revision/tip)
		//
		System.out.println("TODO: nodes between result and tip");
	}

	private static void dump(String s, Collection<Nodeid> c) {
		System.out.println(s);
		for (Nodeid n : c) {
			System.out.println(n);
		}
	}
}
