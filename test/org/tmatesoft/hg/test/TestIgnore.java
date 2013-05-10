/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.util.Path.create;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.internal.WinToNixPathRewrite;
import org.tmatesoft.hg.repo.HgIgnore;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryFiles;
import org.tmatesoft.hg.util.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestIgnore {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	public static void main(String[] args) throws Throwable {
		TestIgnore test = new TestIgnore();
		test.testGlobWithAlternatives();
		test.testComplexFileParse();
		test.testSegmentsGlobMatch();
		test.testWildcardsDoNotMatchDirectorySeparator();
		test.errorCollector.verify();
	}
	
	@Test
	public void testGlobWithAlternatives() throws Exception {
		String content = "syntax:glob\ndoc/*.[0-9].{x,ht}ml";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(content), null);
		final Path p1 = Path.create("doc/asd.2.xml");
		final Path p2 = Path.create("doc/zxc.6.html");
		errorCollector.assertTrue(p1.toString(), hgIgnore.isIgnored(p1));
		errorCollector.assertTrue(p2.toString(), hgIgnore.isIgnored(p2));
	}

	@Test
	public void testComplexFileParse() throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(new File(Configuration.get().getTestDataDir(), "mercurial.hgignore")));
		HgIgnore hgIgnore = HgInternals.newHgIgnore(br, null);
		br.close();
		Path[] toCheck = new Path[] {
				Path.create("file.so"),
				Path.create("a/b/file.so"),
				Path.create("#abc#"),
				Path.create(".#abc"),
				Path.create("locale/en/LC_MESSAGES/hg.mo"),
		};
		doAssert(hgIgnore, toCheck, null);
	}

	@Test
	public void testSegmentsGlobMatch() throws Exception {
		String s = "syntax:glob\nbin\n.*\nTEST-*.xml";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s), null);
		Path[] toCheck = new Path[] {
				Path.create("bin/org/sample/First.class"),
				Path.create(".ignored-file"),
				Path.create("dir/.ignored-file"),
				Path.create("dir/.ignored-dir/file"),
				Path.create("TEST-a.xml"),
				Path.create("dir/TEST-b.xml"),
		};
		doAssert(hgIgnore, toCheck, null);
		//
		s = "syntax:glob\n.git";
		hgIgnore = HgInternals.newHgIgnore(new StringReader(s), null);
		Path p = Path.create(".git/aa");
		errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		p = Path.create("dir/.git/bb");
		errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		p = Path.create("dir/.gittt/cc");
		errorCollector.assertTrue(p.toString(), !hgIgnore.isIgnored(p));
	}

	@Test
	public void testSegmentsRegexMatch() throws Exception {
		// regex patterns that don't start with explicit ^ are allowed to match anywhere in the string
		String s = "syntax:regexp\n/\\.git\n^abc\n";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s), null);
		Path p = Path.create(".git/aa");
		errorCollector.assertTrue(p.toString(), !hgIgnore.isIgnored(p));
		p = Path.create("dir/.git/bb");
		errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		p = Path.create("dir/abc/aa");
		errorCollector.assertTrue(p.toString(), !hgIgnore.isIgnored(p));
		p = Path.create("abc/bb");
		errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		// Mercurial (in fact, likely pyton's regex match() function) treats
		// regex patterns as having .* at the end (unless there's explicit $). 
		// IOW, matches to the beginning of the string, not to the whole string  
		p = Path.create("abcde/fg"); 
		errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
	}

	@Test
	public void testWildcardsDoNotMatchDirectorySeparator() throws Exception {
		String s = "syntax:glob\na?b\nc*d";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s), null);
		// shall not be ignored
		Path[] toPass = new Path[] {
				Path.create("a/b"),
				Path.create("a/b/x"),
				Path.create("x/a/b"),
				Path.create("axyb"),
				Path.create("c/d"),
				Path.create("c/d/x"),
				Path.create("x/c/d"),
		};
		// shall be ignored
		Path[] toIgnore = new Path[] {
				Path.create("axb"),
				Path.create("a3b"),
				Path.create("a_b"),
				Path.create("cd"),
				Path.create("cxd"),
				Path.create("cxyd"),
				Path.create("x/cd"),
				Path.create("x/cxyd"),
				Path.create("cd/x"),
				Path.create("cxyd/x"),
		};
		doAssert(hgIgnore, toIgnore, toPass);
	}

	@Test
	public void testSyntaxPrefixAtLine() throws Exception {
		String s = "glob:*.c\nregexp:.*\\.d";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s), null);
		Path[] toPass = new Path[] {
				create("a/c"),
				create("a/d"),
				create("a/d.a"),
				create("a/d.e"),
		};
		Path[] toIgnore = new Path[] {
				create("a.c"),
				create("a.d"),
				create("src/a.c"),
				create("src/a.d"),
		};
		doAssert(hgIgnore, toIgnore, toPass);
	}

	@Test
	public void testGlobWithWindowsPathSeparators() throws Exception {
		String s = "syntax:glob\n" + "bin\\*\n" + "*\\dir*\\*.a\n" + "*\\_ReSharper*\\\n";
		// explicit PathRewrite for the test to run on *nix as well
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s), new WinToNixPathRewrite());
		Path[] toPass = new Path[] {
				create("bind/x"),
				create("dir/x.a"),
				create("dir-b/x.a"),
				create("a/dir-b/x.b"),
				create("_ReSharper-1/file"),
		};
		Path[] toIgnore = new Path[] {
//				create("bin/x"),
//				create("a/bin/x"),
//				create("a/dir/c.a"),
//				create("b/dir-c/d.a"),
				create("src/_ReSharper-1/file/x"),
		};
		doAssert(hgIgnore, toIgnore, toPass);
	}
	
	@Test
	public void testRefreshOnChange() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-refresh-hgignore", false);
		File hgignoreFile = new File(repoLoc, HgRepositoryFiles.HgIgnore.getPath());
		RepoUtils.createFile(hgignoreFile, "bin/");
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		final Path p1 = Path.create("bin/a/b/c");
		final Path p2 = Path.create("src/a/b/c");
		HgIgnore ignore = hgRepo.getIgnore();
		errorCollector.assertTrue(ignore.isIgnored(p1));
		errorCollector.assertFalse(ignore.isIgnored(p2));
		Thread.sleep(1000); // Linux granularity for modification time is 1 second 
		// file of the same length
		RepoUtils.createFile(hgignoreFile, "src/");
		ignore = hgRepo.getIgnore();
		errorCollector.assertFalse(ignore.isIgnored(p1));
		errorCollector.assertTrue(ignore.isIgnored(p2));
		
	}
	
	private void doAssert(HgIgnore hgIgnore, Path[] toIgnore, Path[] toPass) {
		if (toIgnore == null && toPass == null) {
			throw new IllegalArgumentException();
		}
		if (toIgnore != null) {
			for (Path p : toIgnore) {
				errorCollector.assertTrue("Shall ignore " + p, hgIgnore.isIgnored(p));
			}
		}
		if (toPass != null) {
			for (Path p : toPass) {
				errorCollector.assertTrue("Shall pass " + p, !hgIgnore.isIgnored(p));
			}
		}
	}
}
