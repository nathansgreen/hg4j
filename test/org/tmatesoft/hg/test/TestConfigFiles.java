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

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.repo.HgRepoConfig;
import org.tmatesoft.hg.repo.HgRepoConfig.PathsSection;
import org.tmatesoft.hg.repo.HgRepoConfig.Section;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Pair;

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
	@Ignore("just a dump for now, to compare values visually")
	public void testRepositoryConfig() throws Exception {
		HgRepository repo = Configuration.get().own();
		final HgRepoConfig cfg = repo.getConfiguration();
		Assert.assertNotNull(cfg.getPaths());
		Assert.assertNotNull(cfg.getExtensions());
		final Section dne = cfg.getSection("does-not-exist");
		Assert.assertNotNull(dne);
		Assert.assertFalse(dne.exists());
		for (Pair<String, String> p : cfg.getSection("ui")) {
			System.out.printf("%s = %s\n", p.first(), p.second());
		}
		final PathsSection p = cfg.getPaths();
		System.out.printf("Known paths: %d. default: %s(%s), default-push: %s(%s)\n", p.getKeys().size(), p.getDefault(), p.hasDefault(), p.getDefaultPush(), p.hasDefaultPush());
		for (String k : cfg.getPaths().getKeys()) {
			System.out.println(k);
		}
		Assert.assertFalse(p.hasDefault() ^ p.getDefault() != null);
		Assert.assertFalse(p.hasDefaultPush() ^ p.getDefaultPush() != null);
	}
}
