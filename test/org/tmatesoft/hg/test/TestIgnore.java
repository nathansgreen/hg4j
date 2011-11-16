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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.StringReader;

import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.repo.HgIgnore;
import org.tmatesoft.hg.repo.HgInternals;
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
		test.testSegmentsMatch();
		test.testWildcardsDoNotMatchDirectorySeparator();
		test.errorCollector.verify();
	}
	
	@Test
	public void testGlobWithAlternatives() throws Exception {
		String content = "syntax:glob\ndoc/*.[0-9].{x,ht}ml";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(content));
		final Path p1 = Path.create("doc/asd.2.xml");
		final Path p2 = Path.create("doc/zxc.6.html");
		errorCollector.assertTrue(p1.toString(), hgIgnore.isIgnored(p1));
		errorCollector.assertTrue(p2.toString(), hgIgnore.isIgnored(p2));
	}

	@Test
	public void testComplexFileParse() throws Exception {
		BufferedReader br = new BufferedReader(new FileReader(new File(Configuration.get().getTestDataDir(), "mercurial.hgignore")));
		HgIgnore hgIgnore = HgInternals.newHgIgnore(br);
		br.close();
		Path[] toCheck = new Path[] {
				Path.create("file.so"),
				Path.create("a/b/file.so"),
				Path.create("#abc#"),
				Path.create(".#abc"),
				Path.create("locale/en/LC_MESSAGES/hg.mo"),
		};
		for (Path p : toCheck) {
			errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		}
	}

	@Test
	public void testSegmentsMatch() throws Exception {
		String s = "syntax:glob\nbin\n.*\nTEST-*.xml";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s));
		Path[] toCheck = new Path[] {
				Path.create("bin/org/sample/First.class"),
				Path.create(".ignored-file"),
				Path.create("dir/.ignored-file"),
				Path.create("dir/.ignored-dir/file"),
				Path.create("TEST-a.xml"),
				Path.create("dir/TEST-b.xml"),
		};
		for (Path p : toCheck) {
			errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		}
	}
	
	@Test
	public void testWildcardsDoNotMatchDirectorySeparator() throws Exception {
		String s = "syntax:glob\na?b\nc*d";
		HgIgnore hgIgnore = HgInternals.newHgIgnore(new StringReader(s));
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
		for (Path p : toIgnore) {
			errorCollector.assertTrue(p.toString(), hgIgnore.isIgnored(p));
		}
		for (Path p : toPass) {
			errorCollector.assertTrue(p.toString(), !hgIgnore.isIgnored(p));
		}
	}
}
