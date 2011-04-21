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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import junit.framework.Assert;

import org.junit.Rule;
import org.tmatesoft.hg.core.HgCloneCommand;
import org.tmatesoft.hg.repo.HgRemoteRepository;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestClone {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	public static void main(String[] args) throws Exception {
		TestClone t = new TestClone();
		t.testSimpleClone();
	}

	public TestClone() {
	}
	
	public void testSimpleClone() throws Exception {
		int x = 0;
		final File tempDir = new File(System.getProperty("java.io.tmpdir"));
		for (HgRemoteRepository hgRemote : Configuration.get().allRemote()) {
			HgCloneCommand cmd = new HgCloneCommand();
			cmd.source(hgRemote);
			File dest = new File(tempDir, "test-clone-" + x++);
			if (dest.exists()) {
				rmdir(dest);
			}
			cmd.destination(dest);
			cmd.execute();
			verify(hgRemote, dest);
		}
	}

	private void verify(HgRemoteRepository hgRemote, File dest) throws Exception {
		OutputParser noop = new OutputParser() {
			public void parse(CharSequence seq) {
				// no-op
			}
		};
		ExecHelper eh = new ExecHelper(noop, dest);
		eh.run("hg", "verify");
		Assert.assertEquals(0, eh.getExitValue());
		eh.run("hg", "out", hgRemote.getLocation());
		Assert.assertEquals(1, eh.getExitValue());
		eh.run("hg", "in", hgRemote.getLocation());
		Assert.assertEquals(1, eh.getExitValue());
	}

	private static void rmdir(File dest) throws IOException {
		LinkedList<File> queue = new LinkedList<File>();
		queue.addAll(Arrays.asList(dest.listFiles()));
		while (!queue.isEmpty()) {
			File next = queue.removeFirst();
			if (next.isDirectory()) {
				List<File> files = Arrays.asList(next.listFiles());
				if (!files.isEmpty()) {
					queue.addAll(files);
					queue.add(next);
				}
				// fall through
			} 
			next.delete();
		}
		dest.delete();
	}
}
