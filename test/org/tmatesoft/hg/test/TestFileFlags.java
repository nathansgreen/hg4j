/*
 * Copyright (c) 2012 TMate Software Ltd
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
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.internal.RelativePathRewrite;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.FileInfo;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * {junit-test-repos}/test-flags/
 * 
 * <p>Node, JAR can't keep symlinks. Solution would be to keep 
 * repo without WC and perform an `hg co -C` before use if we 
 * need files from working copy.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestFileFlags {
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	
	@Test
	public void testFlagsInManifest() {
		HgDataFile link = repo.getFileNode("file-link");
		HgDataFile exec = repo.getFileNode("file-exec");
		HgDataFile file = repo.getFileNode("regular-file");
		assertEquals(Flags.Link, link.getFlags(TIP));
		assertEquals(Flags.Exec, exec.getFlags(TIP));
		assertEquals(Flags.RegularFile, file.getFlags(TIP));
	}
	
	@Test
	public void testFlagsInWorkingCopy() throws Exception {
		File repoRoot = repo.getWorkingDir();
		Path.Source pathSrc = new Path.SimpleSource(new PathRewrite.Composite(new RelativePathRewrite(repoRoot), repo.getToRepoPathHelper()));
		FileWalker fw = new FileWalker(repo, repoRoot, pathSrc, null);
		
		if (Internals.runningOnWindows()) {
			System.out.println("Executing tests on Windows, no actual file flags in working area are checked");
			assertFalse(fw.supportsExecFlag());
			assertFalse(fw.supportsLinkFlag());
			return;
		} else {
			assertTrue(fw.supportsExecFlag());
			assertTrue(fw.supportsLinkFlag());
		}
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(false), repo.getWorkingDir());
		eh.run("hg", "checkout", "-C");

		boolean exec, link, file;
		exec = link = file = false;
		while (fw.hasNext()) {
			fw.next();
			FileInfo fi = fw.file();
			String fn = fw.name().toString();
			if (fn.equals("file-link")) {
				link = true;
				errorCollector.assertTrue("Symlink shall exist despite the fact it points to nowhere", fi.exists());
				errorCollector.assertFalse(fi.isExecutable());
				errorCollector.assertTrue(fi.isSymlink());
			} else if (fn.equals("file-exec")) {
				exec = true;
				errorCollector.assertTrue(fi.isExecutable());
				errorCollector.assertFalse(fi.isSymlink());
			} else if (fn.equals("regular-file")) {
				file = true;
				errorCollector.assertFalse(fi.isExecutable());
				errorCollector.assertFalse(fi.isSymlink());
			}
		}
		errorCollector.assertTrue("Missing executable file in WC", exec);
		errorCollector.assertTrue("Missing symlink in WC", link);
		errorCollector.assertTrue("Missing regular file in WC", file);
	}
	
	@Before
	public void assignRepo() throws Exception {
		repo = Configuration.get().find("test-flags");
	}

	@After
	public void cleanFiles() {
		File link = new File(repo.getWorkingDir(), "file-link");
		File exec = new File(repo.getWorkingDir(), "file-exec");
		File file = new File(repo.getWorkingDir(), "regular-file");
		if (link.exists()) {
			link.deleteOnExit();
		}
		if (exec.exists()) {
			exec.deleteOnExit();
		}
		if (file.exists()) {
			file.deleteOnExit();
		}
	}
}
