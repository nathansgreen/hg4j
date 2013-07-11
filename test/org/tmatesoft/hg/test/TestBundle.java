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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.BundleGenerator;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgBundle.GroupElement;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestBundle {
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testCreateBundle() throws Exception {
		final HgRepository hgRepo = Configuration.get().own();
		BundleGenerator bg = new BundleGenerator(HgInternals.getImplementationRepo(hgRepo));
		ArrayList<Nodeid> l = new ArrayList<Nodeid>();
		l.add(Nodeid.fromAscii("9ef1fab9f5e3d51d70941121dc27410e28069c2d")); // 640
		l.add(Nodeid.fromAscii("2f33f102a8fa59274a27ebbe1c2903cecac6c5d5")); // 639
		l.add(Nodeid.fromAscii("d074971287478f69ab0a64176ce2284d8c1e91c3")); // 638
		File bundleFile = bg.create(l);
		HgBundle b = new HgLookup().loadBundle(bundleFile);
		//
		DumbInspector insp = new DumbInspector();
		b.inspectChangelog(insp);
		errorCollector.assertTrue(insp.clogEnter && insp.clogExit);
		errorCollector.assertFalse(insp.csets.isEmpty());
		errorCollector.assertFalse(insp.manifestEnter || insp.manifestExit);
		Collections.sort(l);
		Collections.sort(insp.csets);
		errorCollector.assertEquals(l, insp.csets);
		errorCollector.assertEquals(0, insp.filesEnter);
		errorCollector.assertEquals(0, insp.filesExit);
		errorCollector.assertTrue(insp.manifests == null || insp.manifests.isEmpty());
		errorCollector.assertTrue(insp.files.isEmpty());
		//
		insp = new DumbInspector();
		b.inspectFiles(insp);
		errorCollector.assertFalse(insp.clogEnter && insp.clogExit);
		errorCollector.assertFalse(insp.manifestEnter || insp.manifestExit);
		// $ hg log -r 638:640 --debug | grep files
		List<String> affectedFiles = Arrays.asList("src/org/tmatesoft/hg/repo/HgDataFile.java", "COPYING", "build.gradle", ".hgtags");
		// "src/org/tmatesoft/hg/repo/HgBlameInspector.java" was deleted in r638 and hence not part of the bundle 
		ArrayList<String> foundFiles = new ArrayList<String>(insp.files.keySet());
		Collections.sort(affectedFiles);
		Collections.sort(foundFiles);
		errorCollector.assertEquals(affectedFiles, foundFiles);
		errorCollector.assertEquals(affectedFiles.size(), insp.filesEnter);
		errorCollector.assertEquals(affectedFiles.size(), insp.filesExit);
		b.unlink();
	}

	private static class DumbInspector implements HgBundle.Inspector {
		public boolean clogEnter, clogExit, manifestEnter, manifestExit;
		public int filesEnter, filesExit;
		public List<Nodeid> csets, manifests;
		public Map<String, List<Nodeid>> files = new HashMap<String, List<Nodeid>>();
		private List<Nodeid> actual;

		public void changelogStart() throws HgRuntimeException {
			assertFalse(clogEnter);
			assertFalse(clogExit);
			clogEnter = true;
			actual = csets = new ArrayList<Nodeid>();
		}

		public void changelogEnd() throws HgRuntimeException {
			assertTrue(clogEnter);
			assertFalse(clogExit);
			clogExit = true;
			actual = null;
		}

		public void manifestStart() throws HgRuntimeException {
			assertFalse(manifestEnter);
			assertFalse(manifestExit);
			manifestEnter = true;
			actual = manifests = new ArrayList<Nodeid>();
		}

		public void manifestEnd() throws HgRuntimeException {
			assertTrue(manifestEnter);
			assertFalse(manifestExit);
			manifestExit = true;
			actual = null;
		}

		public void fileStart(String name) throws HgRuntimeException {
			assertEquals(filesEnter, filesExit);
			filesEnter++;
			files.put(name, actual = new ArrayList<Nodeid>());
		}

		public void fileEnd(String name) throws HgRuntimeException {
			assertEquals(filesEnter, 1 + filesExit);
			filesExit++;
			actual = null;
		}

		public boolean element(GroupElement element) throws HgRuntimeException {
			actual.add(element.node());
			return true;
		}
	}
}
