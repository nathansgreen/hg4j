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
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.test;

import static org.junit.Assert.*;

import java.io.File;

import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Configuration {
	
	private static Configuration inst;
	private final File root;
	private final HgLookup lookup;
	
	private Configuration(File reposRoot) {
		root = reposRoot;
		lookup = new HgLookup();
	}
	
	public static Configuration get() {
		if (inst == null) {
			String repo2 = System.getProperty("hg4j.tests.repos");
			assertNotNull(repo2);
			File rr = new File(repo2);
			assertTrue(rr.exists());
			inst = new Configuration(rr);
		}
		return inst;
	}
	
	public HgRepository own() throws Exception {
		return lookup.detectFromWorkingDir();
	}

	// fails if repo not found
	public HgRepository find(String key) throws Exception {
		HgRepository rv = lookup.detect(new File(root, key));
		assertNotNull(rv);
		assertFalse(rv.isInvalid());
		return rv;
	}
}
