/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import com.tmate.hgkit.fs.RepositoryFinder;
import com.tmate.hgkit.ll.HgRepository;

/**
 * @author artem
 *
 */
public class Cat {

	public static void main(String[] args) throws Exception {
		RepositoryFinder repoLookup = new RepositoryFinder();
		HgRepository hgRepo = repoLookup.detect(args);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
	}
}
