/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgDataFile;
import com.tmate.hgkit.ll.HgRepository;

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
				}
			} else {
				System.out.println(">>>Not found!");
			}
		}
	}
}
