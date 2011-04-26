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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRemoteRepository;
import org.tmatesoft.hg.repo.HgRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Configuration {
	
	private static Configuration inst;
	private File root;
	private final HgLookup lookup;
	private File tempDir;
	private List<String> remoteServers;
	
	private Configuration() {
		lookup = new HgLookup();
	}
	
	private File getRoot() {
		if (root == null) {
			String repo2 = System.getProperty("hg4j.tests.repos");
			assertNotNull("System property hg4j.tests.repos is undefined", repo2);
			root = new File(repo2);
			assertTrue(root.exists());
		}
		return root;
	}
	
	public static Configuration get() {
		if (inst == null) {
			inst = new Configuration();
		}
		return inst;
	}
	
	public HgRepository own() throws Exception {
		return lookup.detectFromWorkingDir();
	}

	// fails if repo not found
	public HgRepository find(String key) throws Exception {
		HgRepository rv = lookup.detect(new File(getRoot(), key));
		assertNotNull(rv);
		assertFalse(rv.isInvalid());
		return rv;
	}

	// easy override for manual test runs
	public void remoteServers(String... keys) {
		remoteServers = Arrays.asList(keys);
	}

	public List<HgRemoteRepository> allRemote() throws Exception {
		if (remoteServers == null) {
			remoteServers = Collections.singletonList("hg4j-gc"); // just a default
		}
		ArrayList<HgRemoteRepository> rv = new ArrayList<HgRemoteRepository>(remoteServers.size());
		for (String key : remoteServers) {
			rv.add(lookup.detectRemote(key, null));
		}
		return rv;
	}

	public File getTempDir() {
		if (tempDir == null) {
			String td = System.getProperty("hg4j.tests.tmpdir", System.getProperty("java.io.tmpdir"));
			tempDir = new File(td);
		}
		return tempDir;
	}
}
