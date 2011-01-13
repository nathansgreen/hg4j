/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.console;

import java.io.File;

import com.tmate.hgkit.fs.DataAccessProvider;
import com.tmate.hgkit.fs.RepositoryLookup;
import com.tmate.hgkit.ll.HgBundle;
import com.tmate.hgkit.ll.HgRepository;

/**
 *
 * @author artem
 */
public class Bundle {

	public static void main(String[] args) throws Exception {
		RepositoryLookup repoLookup = new RepositoryLookup();
		RepositoryLookup.Options cmdLineOpts = RepositoryLookup.Options.parse(args);
		HgRepository hgRepo = repoLookup.detect(cmdLineOpts);
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		File bundleFile = new File("/temp/hg/hg-bundle-a78c980749e3.tmp");
		DataAccessProvider dap = new DataAccessProvider();
		HgBundle hgBundle = new HgBundle(dap, bundleFile);
//		hgBundle.dump();
		hgBundle.changes(hgRepo);
	}
}
