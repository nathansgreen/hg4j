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
package org.tmatesoft.hg.repo;


/**
 * DO NOT USE THIS CLASS, INTENDED FOR TESTING PURPOSES.
 * 
 * Debug helper, to access otherwise restricted (package-local) methods
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.

 */
public class Internals {

	private final HgRepository repo;

	public Internals(HgRepository hgRepo) {
		repo = hgRepo;
	}

	public void dumpDirstate() {
		repo.loadDirstate().dump();
	}

	public boolean[] checkIgnored(String... toCheck) {
		HgIgnore ignore = repo.loadIgnore();
		boolean[] rv = new boolean[toCheck.length];
		for (int i = 0; i < toCheck.length; i++) {
			rv[i] = ignore.isIgnored(toCheck[i]);
		}
		return rv;
	}
}
