/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.Changeset;
import com.tmate.hgkit.ll.HgDataFile;
import com.tmate.hgkit.ll.HgRepository;

/**
 * @author artem
 */
public class Log {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		final Changeset.Inspector callback = new Changeset.Inspector() {
			
			public void next(Changeset cset) {
				System.out.println("==>");
				cset.dump();
			}
		};
		if (cmdLineOpts.files.isEmpty()) {
			System.out.println("Complete history of the repo:");
			hgRepo.getChangelog().all(callback);
		} else {
			for (String fname : cmdLineOpts.files) {
				HgDataFile f1 = hgRepo.getFileNode(fname);
				System.out.println("History of the file: " + f1.getPath());
				f1.history(callback);
			}
		}
		//
//		System.out.println("\n\n=========================");
//		System.out.println("Range 1-3:");
//		f1.history(1,3, callback);
		//
		//new ChangelogWalker().setFile("hello.c").setRevisionRange(1, 4).accept(new Visitor);
	}
}
