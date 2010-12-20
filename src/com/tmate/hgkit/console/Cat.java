/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgRepository;

/**
 * @author artem
 *
 */
public class Cat {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		HgRepository hgRepo = repoLookup.detect(args);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		byte[] tipContent = hgRepo.getFileNode("hello.c").content();
		System.out.println(new String(tipContent));
	}
}
