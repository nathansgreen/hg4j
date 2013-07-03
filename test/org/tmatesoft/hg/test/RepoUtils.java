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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.tmatesoft.hg.util.LogFacility.Severity.Debug;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import junit.framework.Assert;

import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgInitCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.FileUtils;
import org.tmatesoft.hg.internal.StreamLogFacility;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelledException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RepoUtils {

	static File initEmptyTempRepo(String dirName) throws IOException, HgException {
		File dest = createEmptyDir(dirName);
		try {
			new HgInitCommand().location(dest).revlogV1().execute();
		} catch (CancelledException ex) {
			Assert.fail(ex.toString());
		}
		return dest;
	}

	static File createEmptyDir(String dirName) throws IOException {
		File dest = new File(Configuration.get().getTempDir(), dirName);
		if (dest.exists()) {
			rmdir(dest);
		}
		dest.mkdirs();
		return dest;
	}

	static File cloneRepoToTempLocation(String configRepoName, String name, boolean noupdate) throws HgException, IOException, InterruptedException {
		return cloneRepoToTempLocation(Configuration.get().find(configRepoName), name, noupdate);
	}

	static File cloneRepoToTempLocation(HgRepository repo, String name, boolean noupdate) throws IOException, InterruptedException {
		return cloneRepoToTempLocation(repo.getWorkingDir(), name, noupdate, false);
	}

	static File cloneRepoToTempLocation(File repoLoc, String name, boolean noupdate, boolean usePull) throws IOException, InterruptedException {
		File testRepoLoc = createEmptyDir(name);
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(), testRepoLoc.getParentFile());
		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("hg");
		cmd.add("clone");
		if (noupdate) {
			cmd.add("--noupdate");
		}
		if (usePull) {
			cmd.add("--pull");
		}
		cmd.add(repoLoc.toString());
		cmd.add(testRepoLoc.getName());
		eh.run(cmd.toArray(new String[cmd.size()]));
		assertEquals("[sanity]", 0, eh.getExitValue());
		return testRepoLoc;
	}
	
	static File copyRepoToTempLocation(String configRepoName, String newRepoName) throws HgException, IOException {
		File testRepoLoc = createEmptyDir(newRepoName);
		final File srcDir = Configuration.get().find(configRepoName).getWorkingDir();
		Iterator<File> it = new Iterator<File>() {
			private final LinkedList<File> queue = new LinkedList<File>();
			{
				queue.addAll(Arrays.asList(srcDir.listFiles()));
			}
			public boolean hasNext() {
				return !queue.isEmpty();
			}
			public File next() {
				File n = queue.removeFirst();
				if (n.isDirectory()) {
					queue.addAll(Arrays.asList(n.listFiles()));
				}
				return n;
			}
			public void remove() {
				throw new UnsupportedOperationException();
			}
		};
		FileUtils fu = new FileUtils(new StreamLogFacility(Debug, true, System.err), RepoUtils.class);
		String srcPrefix = srcDir.getAbsolutePath();
		while (it.hasNext()) {
			File next = it.next();
			assert next.getAbsolutePath().startsWith(srcPrefix);
			String relPath = next.getAbsolutePath().substring(srcPrefix.length());
			File dest = new File(testRepoLoc, relPath);
			if (next.isDirectory()) {
				dest.mkdir();
			} else {
				fu.copy(next, dest);
				dest.setLastModified(next.lastModified());
			}
		}
		return testRepoLoc;
	}

	static void modifyFileAppend(File f, Object content) throws IOException {
		assertTrue(f.isFile());
		FileOutputStream fos = new FileOutputStream(f, true);
		if (content == null) {
			content = "XXX".getBytes();
		}
		if (content instanceof byte[]) {
			fos.write((byte[]) content);
		} else {
			fos.write(String.valueOf(content).getBytes());
		}
		fos.close();
	}

	static void createFile(File f, Object content) throws IOException {
		if (content == null) {
			f.createNewFile();
			return;
		}
		if (content instanceof byte[]) {
			FileOutputStream fos = new FileOutputStream(f);
			fos.write((byte[]) content);
			fos.close();
		} else {
			FileWriter fw = new FileWriter(f);
			fw.write(String.valueOf(content));
			fw.close();
		}
	}

	static void exec(File wd, int expectedRetVal, String... args) throws Exception {
		OutputParser.Stub s = new OutputParser.Stub();
		try {
			ExecHelper eh = new ExecHelper(s, wd);
			eh.run(args);
			Assert.assertEquals(expectedRetVal, eh.getExitValue());
		} catch (Exception ex) {
			System.err.println(s.result());
			throw ex;
		}
	}

	static void rmdir(File dest) throws IOException {
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

	static Nodeid[] allRevisions(HgRepository repo) {
		Nodeid[] allRevs = new Nodeid[repo.getChangelog().getRevisionCount()];
		for (int i = 0; i < allRevs.length; i++) {
			allRevs[i] = repo.getChangelog().getRevision(i);
		}
		return allRevs;
	}
}
