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

import org.junit.Test;
import org.tmatesoft.hg.core.HgCatCommand;
import org.tmatesoft.hg.core.HgChangesetFileSneaker;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestCatCommand {
	
	private final HgRepository repo;
	
	public TestCatCommand() throws Exception {
		repo = new HgLookup().detectFromWorkingDir();
	}
	
	@Test
	public void testCatAtCsetRevision() throws Exception {
		HgCatCommand cmd = new HgCatCommand(repo);
		final Path file = Path.create("src/org/tmatesoft/hg/internal/RevlogStream.java");
		cmd.file(file);
		final Nodeid cset = Nodeid.fromAscii("08db726a0fb7914ac9d27ba26dc8bbf6385a0554");
		cmd.changeset(cset);
		final ByteArrayChannel sink = new ByteArrayChannel();
		cmd.execute(sink);
		final int result1 = sink.toArray().length;
		HgChangesetFileSneaker i = new HgChangesetFileSneaker(repo);
		boolean result = i.changeset(cset).checkExists(file);
		assertFalse(result);
		assertFalse(i.exists());
		result = i.followRenames(true).checkExists(file);
		assertTrue(result);
		assertTrue(i.exists());
		HgCatCommand cmd2 = new HgCatCommand(repo).revision(i.getFileRevision());
		final ByteArrayChannel sink2 = new ByteArrayChannel();
		cmd2.execute(sink2);
		final int result2 = sink2.toArray().length;
		assertEquals(result1, result2);
	}

}
