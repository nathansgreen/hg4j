/*
 * Copyright (c) 2013 TMate Software Ltd
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
import java.util.HashMap;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepoConfig;
import org.tmatesoft.hg.repo.HgRepoConfig.ExtensionsSection;
import org.tmatesoft.hg.repo.HgRepoConfig.PathsSection;
import org.tmatesoft.hg.repo.HgRepoConfig.Section;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestConfigFiles {
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testConfigFile() throws Exception {
		ConfigFile configFile = new ConfigFile(new BasicSessionContext(null));
		configFile.addLocation(new File(Configuration.get().getTestDataDir(), "sample.rc"));
		// section1 has key1 unset, key2 overridden from included, key4 from second occurence
		HashMap<String, String> section1 = new HashMap<String, String>();
		section1.put("key2", "alternative value 2");
		section1.put("key3", "value 3");
		section1.put("key4", "value 4");
		// section2 comes from included config
		HashMap<String, String> section2 = new HashMap<String, String>();
		section2.put("key1", "value 1-2");
		HashMap<String, String> section3 = new HashMap<String, String>();
		section3.put("key1", "value 1-3");
		HashMap<String, HashMap<String,String>> sections = new HashMap<String, HashMap<String,String>>();
		sections.put("section1", section1);
		sections.put("section2", section2);
		sections.put("section3", section3);
		//
		for (String s : configFile.getSectionNames()) {
//			System.out.printf("[%s]\n", s);
			final HashMap<String, String> m = sections.remove(s);
			errorCollector.assertTrue(m != null);
			for (Map.Entry<String, String> e : configFile.getSection(s).entrySet()) {
//				System.out.printf("%s = %s\n", e.getKey(), e.getValue());
				if (m.containsKey(e.getKey())) {
					errorCollector.assertEquals(m.remove(e.getKey()), e.getValue());
				} else {
					errorCollector.fail("Unexpected key:" + e.getKey());
				}
			}
		}
		errorCollector.assertEquals(0, sections.size());
		errorCollector.assertEquals(0, section1.size());
		errorCollector.assertEquals(0, section2.size());
		errorCollector.assertEquals(0, section3.size());
	}

	@Test
	public void testRepositoryConfig() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-repocfg", false);
		File hgrc = new File(repoLoc, ".hg/hgrc");
		String username = "John Q. Public <john.public@acme.com>";
		String path1_key = "hg4j.gc";
		String path1_value = "https://code.google.com/p/hg4j/";
		String ext1_key = "ext.one";
		String ext2_key = "ext.disabled"; // disabled
		String ext3_key = "hgext.two"; // check if found by "two" key 
		String hgrcContent = String.format("#comment\n[ui]\nusername = %s\n\n[paths]\n%s = %s\ndefault=%3$s\n\n[extensions]\n%s = \n%s = !\n%s=\n", username, path1_key, path1_value, ext1_key, ext2_key, ext3_key);
		RepoUtils.createFile(hgrc, hgrcContent);
		//
		HgRepository repo = new HgLookup().detect(repoLoc);
		final HgRepoConfig cfg = repo.getConfiguration();
		assertNotNull(cfg.getPaths());
		assertNotNull(cfg.getExtensions());
		final Section dne = cfg.getSection("does-not-exist");
		assertNotNull(dne);
		assertFalse(dne.exists());
		assertEquals(username, cfg.getSection("ui").getString("username", null));
		final PathsSection p = cfg.getPaths();
		assertTrue(p.getPathSymbolicNames().contains(path1_key));
		assertEquals(path1_value, p.getString(path1_key, null));
		assertTrue(p.hasDefault());
		assertEquals(path1_value, p.getDefault());
		assertFalse(p.hasDefault() ^ p.getDefault() != null);
		assertFalse(p.hasDefaultPush() ^ p.getDefaultPush() != null);
		final ExtensionsSection e = cfg.getExtensions();
		assertTrue(e.isEnabled(ext1_key));
		assertTrue(e.getString(ext2_key, null).length() > 0);
		assertFalse(e.isEnabled(ext2_key));
		assertNotNull(e.getString(ext3_key, null));
		assertTrue(e.isEnabled(ext3_key.substring("hgext.".length())));
		//
		assertEquals(username, new HgInternals(repo).getNextCommitUsername());
	}
}
