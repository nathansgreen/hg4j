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
import static org.tmatesoft.hg.internal.RequiresFile.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;

import org.tmatesoft.hg.internal.RepoInitializer;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RepoUtils {

	static File initEmptyTempRepo(String dirName) throws IOException {
		File dest = createEmptyDir(dirName);
		RepoInitializer ri = new RepoInitializer();
		ri.setRequires(STORE | FNCACHE | DOTENCODE);
		ri.initEmptyRepository(new File(dest, ".hg"));
		return dest;
	}

	static File createEmptyDir(String dirName) throws IOException {
		File dest = new File(Configuration.get().getTempDir(), dirName);
		if (dest.exists()) {
			TestClone.rmdir(dest);
		}
		dest.mkdirs();
		return dest;
	}

	static File cloneRepoToTempLocation(String configRepoName, String name, boolean noupdate) throws Exception, InterruptedException {
		return cloneRepoToTempLocation(Configuration.get().find(configRepoName), name, noupdate);
	}

	static File cloneRepoToTempLocation(HgRepository repo, String name, boolean noupdate) throws IOException, InterruptedException {
		File testRepoLoc = createEmptyDir(name);
		ExecHelper eh = new ExecHelper(new OutputParser.Stub(), testRepoLoc.getParentFile());
		ArrayList<String> cmd = new ArrayList<String>();
		cmd.add("hg");
		cmd.add("clone");
		if (noupdate) {
			cmd.add("--noupdate");
		}
		cmd.add(repo.getWorkingDir().toString());
		cmd.add(testRepoLoc.getName());
		eh.run(cmd.toArray(new String[cmd.size()]));
		assertEquals("[sanity]", 0, eh.getExitValue());
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
}
