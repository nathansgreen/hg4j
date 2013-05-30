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
import java.util.concurrent.CountDownLatch;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.core.HgStatusCommand;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryLock;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestRepositoryLock {

	@Test
	public void testWorkingDirLock() throws Exception {
		File repoLoc = RepoUtils.cloneRepoToTempLocation("log-1", "test-wc-lock", false);
		// turn off lock timeout, to fail fast
		File hgrc = new File(repoLoc, ".hg/hgrc");
		RepoUtils.createFile(hgrc, "[ui]\ntimeout=0\n"); // or 1
		final OutputParser.Stub p = new OutputParser.Stub();
		ExecHelper eh = new ExecHelper(p, repoLoc);
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		final HgRepositoryLock wdLock = hgRepo.getWorkingDirLock();
		try {
			wdLock.acquire();
			eh.run("hg", "tag", "tag-aaa");
			Assert.assertNotSame(0 /*returns 0 on success*/, eh.getExitValue());
			Assert.assertTrue(p.result().toString().contains("abort"));
		} finally {
			wdLock.release();
		}
	}

	public static void main(String[] args) throws Exception {
		Map<String, Object> po = new HashMap<String, Object>();
		po.put(DataAccessProvider.CFG_PROPERTY_MAPIO_LIMIT, 0);
		final HgLookup hgLookup = new HgLookup(new BasicSessionContext(po , null));
		final File rebaseFromRepoLoc = RepoUtils.cloneRepoToTempLocation(new File("/temp/hg/junit-test-repos/test-annotate"), "repo-lock-remote", false, true);
		final File rebaseToRepoLoc = RepoUtils.cloneRepoToTempLocation(rebaseFromRepoLoc, "repo-lock-local", false, true);
		final File remoteChanges = new File(rebaseFromRepoLoc, "file1");
		//
		// create commit in the "local" repository that will be rebased on top of changes
		// pulled from "remote repository"
		File localChanges = new File(rebaseToRepoLoc, "file-new");
		if (localChanges.exists()) {
			RepoUtils.modifyFileAppend(localChanges, "whatever");
		} else {
			RepoUtils.createFile(localChanges, "whatever");
		}
		commit(rebaseToRepoLoc, "local change");
		//
		final int rebaseRevisionCount = 70;
		final CountDownLatch latch = new CountDownLatch(2);
		Runnable r1 = new Runnable() {
			public void run() {
				for (int i = 0; i < rebaseRevisionCount; i++) {
					commitPullRebaseNative(rebaseFromRepoLoc, rebaseToRepoLoc, remoteChanges);
					sleep(500, 1000);
				}
				latch.countDown();
			}
		};
		Runnable r2 = new Runnable() {
			public void run() {
				for (int i = 0; i < 100; i++) {
					readWithHg4J(hgLookup, rebaseToRepoLoc);
					sleep(800, 400);
				}
				latch.countDown();
			}
		};
		new Thread(r1, "pull-rebase-thread").start();
		new Thread(r2, "hg4j-read-thread").start();
		latch.await();
		System.out.println("DONE.");
		// now `hg log` in rebaseToRepoLoc shall show 
		// all rebaseRevisionCount revisions from rebaseFromRepoLoc + 1 more, "local change", on top of them
	}

	private static int count = 0;

	private static void commitPullRebaseNative(final File rebaseFromRepoLoc, final File rebaseToRepoLoc, final File rebaseFromChanges) {
		try {
			OutputParser.Stub p = new OutputParser.Stub();
			final ExecHelper eh = new ExecHelper(p, rebaseToRepoLoc);
			RepoUtils.modifyFileAppend(rebaseFromChanges, "Change #" + count++);
			commit(rebaseFromRepoLoc, "remote change");
			p.reset();
			eh.run("hg", "--traceback", "pull", rebaseFromRepoLoc.toString());
			if (eh.getExitValue() != 0) {
				System.out.println(p.result());
			}
			Assert.assertEquals(0, eh.getExitValue());
			p.reset();
			eh.run("hg", "--traceback", "--config", "extensions.hgext.rebase=", "rebase");
			if (eh.getExitValue() != 0) {
				System.out.println(p.result());
			}
			System.out.print("X");
			Assert.assertEquals(0, eh.getExitValue());
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException(null, ex); 
		}
	}
	
	private static void readWithHg4J(final HgLookup hgLookup, final File repoLoc) {
		try {
			System.out.print("(");
			final long start = System.nanoTime();
			HgRepository hgRepo = hgLookup.detect(repoLoc);
			final HgRepositoryLock wcLock = hgRepo.getWorkingDirLock();
			final HgRepositoryLock storeLock = hgRepo.getStoreLock();
			wcLock.acquire();
			System.out.print(".");
			storeLock.acquire();
			System.out.print(".");
			try {
				new HgStatusCommand(hgRepo).execute(new TestStatus.StatusCollector());
				System.out.printf("%d ms)\n", (System.nanoTime() - start) / 1000000);
			} finally {
				storeLock.release();
				wcLock.release();
			}
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			ex.printStackTrace();
			throw new RuntimeException(null, ex); 
		}
	}
	
	private static void commit(File repoLoc, String message) throws Exception {
		OutputParser.Stub p = new OutputParser.Stub();
		final ExecHelper eh = new ExecHelper(p, repoLoc);
		eh.run("hg", "commit", "--addremove", "-m", "\"" + message + "\"");
		if (eh.getExitValue() != 0) {
			System.out.println(p.result());
		}
		Assert.assertEquals(0, eh.getExitValue());
	}
	
	private static void sleep(int msBase, int msDelta) {
		try {
			Thread.sleep(msBase + Math.round(Math.random() * msDelta));
		} catch (InterruptedException ex) {
			// IGNORE
		}
	}
}
