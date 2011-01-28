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

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

import org.junit.Assume;
import org.junit.Test;
import org.tmatesoft.hg.core.LogCommand.FileRevision;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.core.RepositoryTreeWalker;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgLookup;


/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestManifest {

	private final HgRepository repo;
	private ManifestOutputParser manifestParser;
	private ExecHelper eh;
	final LinkedList<FileRevision> revisions = new LinkedList<FileRevision>();
	private RepositoryTreeWalker.Handler handler  = new RepositoryTreeWalker.Handler() {
		
		public void file(FileRevision fileRevision) {
			revisions.add(fileRevision);
		}
		
		public void end(Nodeid manifestRevision) {}
		public void dir(Path p) {}
		public void begin(Nodeid manifestRevision) {}
	};

	public static void main(String[] args) throws Exception {
		TestManifest tm = new TestManifest();
		tm.testTip();
		tm.testFirstRevision();
		tm.testRevisionInTheMiddle();
	}
	
	public TestManifest() throws Exception {
		this(new HgLookup().detectFromWorkingDir());
	}

	private TestManifest(HgRepository hgRepo) {
		repo = hgRepo;
		Assume.assumeTrue(repo.isInvalid());
		eh = new ExecHelper(manifestParser = new ManifestOutputParser(), null);
	}

	@Test
	public void testTip() throws Exception {
		testRevision(TIP);
	}

	@Test
	public void testFirstRevision() throws Exception {
		testRevision(0);
	}
	
	@Test
	public void testRevisionInTheMiddle() throws Exception {
		int rev = repo.getManifest().getRevisionCount() / 2;
		if (rev == 0) {
			throw new IllegalStateException("Need manifest with few revisions");
		}
		testRevision(rev);
	}

	private void testRevision(int rev) throws Exception {
		manifestParser.reset();
		eh.run("hg", "manifest", "--debug", "--rev", String.valueOf(rev));
		revisions.clear();
		new RepositoryTreeWalker(repo).revision(rev).walk(handler);
		report("manifest " + (rev == TIP ? "TIP:" : "--rev " + rev));
	}

	private void report(String what) throws Exception {
		final Map<Path, Nodeid> cmdLineResult = new LinkedHashMap<Path, Nodeid>(manifestParser.getResult());
		boolean error = false;
		for (FileRevision fr : revisions) {
			Nodeid nid = cmdLineResult.remove(fr.getPath());
			if (nid == null) {
				System.out.println("Extra " + fr.getPath() + " in Java result");
				error = true;
			} else {
				if (!nid.equals(fr.getRevision())) {
					System.out.println("Non-matching nodeid:" + nid);
					error = true;
				}
			}
		}
		if (!cmdLineResult.isEmpty()) {
			System.out.println("Non-matched entries from command line:");
			error = true;
			for (Path p : cmdLineResult.keySet()) {
				System.out.println(p);
			}
		}
		System.out.println(what + (error ? " ERROR" : " OK"));
	}
}
