/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgManifest;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.Nodeid;

/**
 *
 * @author artem
 */
public class Manifest {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		HgManifest.Inspector insp = new HgManifest.Inspector() {
			public boolean begin(int revision, Nodeid nid) {
				System.out.printf("%d : %s\n", revision, nid);
				return true;
			}

			public boolean next(Nodeid nid, String fname, String flags) {
				System.out.println(nid + "\t" + fname + "\t\t" + flags);
				return true;
			}

			public boolean end(int revision) {
				System.out.println();
				return true;
			}
		};
		hgRepo.getManifest().walk(0, TIP, insp);
	}
}
