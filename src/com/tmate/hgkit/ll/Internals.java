/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

/**
 * DO NOT USE THIS CLASS, INTENDED FOR TESTING PURPOSES.
 * 
 * Debug helper, to access otherwise restricted (package-local) methods
 * 
 * @author artem
 */
public class Internals {

	private final HgRepository repo;

	public Internals(HgRepository hgRepo) {
		this.repo = hgRepo;
	}

	public void dumpDirstate() {
		if (repo instanceof LocalHgRepo) {
			((LocalHgRepo) repo).loadDirstate().dump();
		}
	}

	public boolean[] checkIgnored(String... toCheck) {
		if (repo instanceof LocalHgRepo) {
			HgIgnore ignore = ((LocalHgRepo) repo).loadIgnore();
			boolean[] rv = new boolean[toCheck.length];
			for (int i = 0; i < toCheck.length; i++) {
				rv[i] = ignore.isIgnored(toCheck[i]);
			}
			return rv;
		}
		return new boolean[0];
	}
}
