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

import java.io.File;

import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgRepository;


/**
 * WORK IN PROGRESS, DO NOT USE
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Bundle {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
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
