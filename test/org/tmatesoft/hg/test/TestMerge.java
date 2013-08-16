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
import java.util.ArrayList;
import java.util.List;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgMergeCommand;
import org.tmatesoft.hg.core.HgMergeCommand.Resolver;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestMerge {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();
	
	@Test
	public void testMediator() throws Exception {
		HgRepository repo = Configuration.get().find("merge-1");
		Assert.assertEquals("[sanity]", repo.getChangelog().getRevisionIndex(repo.getWorkingCopyParents().first()), 1);

		HgMergeCommand cmd = new HgMergeCommand(repo);

		MergeNotificationCollector c;
		// (fastForward(file1, file2, file3) changes, newInB(file5), same(file4))
		cmd.changeset(2).execute(c = new MergeNotificationCollector());
		errorCollector.assertTrue("file1", c.fastForwardA.contains("file1"));
		errorCollector.assertTrue("file2", c.fastForwardB.contains("file2"));
		errorCollector.assertTrue("file3", c.fastForwardA.contains("file3"));
		errorCollector.assertTrue("file4", c.same.contains("file4"));
		errorCollector.assertTrue("file5", c.newInB.contains("file5"));
		// (conflict(file1), onlyInA(file3), same(file4))
		cmd.changeset(3).execute(c = new MergeNotificationCollector());
		errorCollector.assertTrue("file1", c.conflict.contains("file1"));
		errorCollector.assertTrue("file2", c.same.contains("file2"));
		errorCollector.assertTrue("file3", c.onlyA.contains("file3"));
		errorCollector.assertTrue("file4", c.same.contains("file4"));
	}
	
	
	@Test
	public void testResolver() throws Exception {
		File repoLoc1 = RepoUtils.copyRepoToTempLocation("merge-1", "test-merge-no-conflicts");
		File repoLoc2 = RepoUtils.copyRepoToTempLocation("merge-1", "test-merge-with-conflicts");
		HgRepository repo = new HgLookup().detect(repoLoc1);
		Assert.assertEquals("[sanity]", repo.getChangelog().getRevisionIndex(repo.getWorkingCopyParents().first()), 1);

		HgMergeCommand cmd = new HgMergeCommand(repo);
		cmd.changeset(2).execute(new HgMergeCommand.MediatorBase() {
			
			public void resolve(HgFileRevision base, HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
				errorCollector.fail("There's no conflict in changesets 1 and 2 merge");
			}
		});
		// FIXME run hg status to see changes
		repo = new HgLookup().detect(repoLoc2);
		cmd = new HgMergeCommand(repo);
		cmd.changeset(3).execute(new HgMergeCommand.MediatorBase() {
			
			public void resolve(HgFileRevision base, HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
				resolver.unresolved();
			}
		});
		// FIXME run hg status and hg resolve to see changes
	}

	private static class MergeNotificationCollector implements HgMergeCommand.Mediator {
		public final List<String> same = new ArrayList<String>(); 
		public final List<String> onlyA = new ArrayList<String>();
		public final List<String> onlyB = new ArrayList<String>();
		public final List<String> newInA = new ArrayList<String>();
		public final List<String> newInB = new ArrayList<String>();
		public final List<String> fastForwardA = new ArrayList<String>();
		public final List<String> fastForwardB = new ArrayList<String>();
		public final List<String> conflict = new ArrayList<String>();

		
		public void same(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			same.add(rev.getPath().toString());
		}
		public void onlyA(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			onlyA.add(rev.getPath().toString());
		}
		public void onlyB(HgFileRevision base, HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			onlyB.add(rev.getPath().toString());
		}
		public void newInA(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			newInA.add(rev.getPath().toString());
		}
		public void newInB(HgFileRevision rev, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			newInB.add(rev.getPath().toString());
		}
		public void fastForwardA(HgFileRevision base, HgFileRevision first, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			fastForwardA.add(first.getPath().toString());
		}
		public void fastForwardB(HgFileRevision base, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			fastForwardB.add(second.getPath().toString());
		}
		public void resolve(HgFileRevision base, HgFileRevision first, HgFileRevision second, Resolver resolver) throws HgCallbackTargetException {
			assert resolver != null;
			conflict.add(first.getPath().toString());
		}
	}
}
