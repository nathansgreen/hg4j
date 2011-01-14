/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.DigestHelper;
import com.tmate.hgkit.ll.HgDataFile;
import com.tmate.hgkit.ll.HgIgnore;
import com.tmate.hgkit.ll.HgRepository;
import com.tmate.hgkit.ll.LocalHgRepo;

/**
 * @author artem
 *
 */
public class Cat {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		HgIgnore ignore = ((LocalHgRepo) hgRepo).loadIgnore();
		for (String s : new String[] {"design.txt", "src/com/tmate/hgkit/ll/Changelog.java", "src/Extras.java", "bin/com/tmate/hgkit/ll/Changelog.class"} ) {
			System.out.println("Ignored " + s + ": " + ignore.isIgnored(s));
		}
		DigestHelper dh = new DigestHelper();
		for (String fname : cmdLineOpts.files) {
			System.out.println(fname);
			HgDataFile fn = hgRepo.getFileNode(fname);
			if (fn.exists()) {
				int total = fn.getRevisionCount();
				System.out.printf("Total revisions: %d\n", total);
				for (int i = 0; i < total; i++) {
					byte[] content = fn.content(i);
					System.out.println("==========>");
					System.out.println(new String(content));
					int[] parentRevisions = new int[2];
					byte[] parent1 = new byte[20];
					byte[] parent2 = new byte[20];
					fn.parents(i, parentRevisions, parent1, parent2);
					System.out.println(dh.sha1(parent1, parent2, content).asHexString());
				}
			} else {
				System.out.println(">>>Not found!");
			}
		}
	}
}
