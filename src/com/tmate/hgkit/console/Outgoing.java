/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;
import com.tmate.hgkit.ll.Revlog;

/**
 * hg out
 * @author artem
 */
public class Outgoing {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		// FIXME detection of 
		List<Nodeid> base = new LinkedList<Nodeid>();
		base.add(Nodeid.fromAscii("d6d2a630f4a6d670c90a5ca909150f2b426ec88f".getBytes(), 0, 40));
		//
		// fill with all known
		Revlog.ParentWalker pw = hgRepo.getChangelog().new ParentWalker();
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
