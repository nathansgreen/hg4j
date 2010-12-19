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
		HgRepository hgRepo = repoLookup.detect(args);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println(hgRepo.getLocation());
		final Changeset.Callback callback = new Changeset.Callback() {
			
			public void next(Changeset cset) {
				System.out.println();
			}
		};
		HgDataFile f1 = hgRepo.getFileNode("hello.c");
		System.out.println("Complete of a file:");
		f1.history(callback);
		System.out.println("Range 1-3:");
		f1.history(1,3, callback);
		//
		System.out.println("Complete of a repo:");
		hgRepo.getChangelog().all(callback);
		//new ChangelogWalker().setFile("hello.c").setRevisionRange(1, 4).accept(new Visitor);
	}

}
