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

import java.util.Iterator;
import java.util.LinkedList;

import org.junit.Test;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.ext.MqManager;
import org.tmatesoft.hg.repo.ext.MqManager.PatchRecord;

/**
 * {junit-test-repos}/test-mq/
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestMqExtension {

	@Test
	public void testMqManager() throws Exception {
		HgRepository repo = Configuration.get().find("test-mq");
		MqManager mqManager = new MqManager(repo);
		mqManager.refresh();
		OutputParser.Stub output = new OutputParser.Stub();
		ExecHelper eh = new ExecHelper(output, repo.getWorkingDir());
		// `hg qseries`
		eh.run("hg", "qseries");
		LinkedList<PatchRecord> allKnownPatches = new LinkedList<PatchRecord>(mqManager.getAllKnownPatches());
		assertTrue("[sanity]", allKnownPatches.size() > 0);
		for (CharSequence l : output.lines()) {
			for (Iterator<PatchRecord> it = allKnownPatches.listIterator(); it.hasNext(); ) {
				if (it.next().getName().equals(l)) {
					it.remove();
				}
			}
		}
		assertTrue("Known patches shall match those from `hg qseries`", allKnownPatches.isEmpty());
		//
		// `hg qapplied`, patches from the queue already applied to the repo
		eh.run("hg", "qapplied");
		LinkedList<PatchRecord> appliedPatches = new LinkedList<PatchRecord>(mqManager.getAppliedPatches());
		assertTrue("[sanity]", appliedPatches.size() > 0);
		for (CharSequence l : output.lines()) {
			for (Iterator<PatchRecord> it = appliedPatches.listIterator(); it.hasNext(); ) {
				if (it.next().getName().equals(l)) {
					it.remove();
				}
			}
		}
		assertTrue("Each patch reported as applied shall match thos from `hg qapplied`", appliedPatches.isEmpty());
		
		assertTrue("[sanity] ",mqManager.getQueueSize() > 0);
		boolean allAppliedAreKnown = mqManager.getAllKnownPatches().containsAll(mqManager.getAppliedPatches());
		assertTrue(allAppliedAreKnown); // ensure instances are the same, ==

		// `hg qqueue`
		assertTrue("[sanity]",mqManager.getQueueNames().size() > 1);
		assertTrue(mqManager.getActiveQueueName().length() > 0);
		eh.run("hg", "qqueue");
		boolean activeQueueFound = false;
		LinkedList<String> queueNames = new LinkedList<String>(mqManager.getQueueNames());
		for (String l : output.lines()) {
			if (l.endsWith("(active)")) {
				l = l.substring(0, l.length() - 8).trim();
				assertEquals(l, mqManager.getActiveQueueName());
				assertFalse("only single active queue", activeQueueFound);
				activeQueueFound = true;
			}
			assertTrue(queueNames.remove(l));
		}
		assertTrue(activeQueueFound);
		assertTrue(queueNames.isEmpty()); // every queue name we found matches `hg qqueue` output 
	}
}
