/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import org.junit.Rule;
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
	
	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	private HgRepository repo;
	
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

	// ensure code to follow rename history in the command is correct
	@Test
	public void testRenamedFileInCset() throws Exception {
		repo = Configuration.get().find("log-renames");
		HgCatCommand cmd1 = new HgCatCommand(repo);
		HgCatCommand cmd2 = new HgCatCommand(repo);
		cmd1.file(Path.create("a")); // a is initial b through temporary c
		cmd2.file(Path.create("c"));
		ByteArrayChannel sink1, sink2;
		// a from wc/tip was c in rev 4
		cmd1.changeset(4).execute(sink1 = new ByteArrayChannel());
		cmd2.changeset(4).execute(sink2 = new ByteArrayChannel());
		assertArrayEquals(sink2.toArray(), sink1.toArray());
		//
		// d from wc/tip was a in 0..2 and b in rev 3..4. Besides, there's another d in r4 
		cmd2.file(Path.create("d"));
		cmd1.changeset(2).execute(sink1 = new ByteArrayChannel());
		cmd2.changeset(2).execute(sink2 = new ByteArrayChannel());
		assertArrayEquals(sink1.toArray(), sink2.toArray());
		// 
		cmd1.file(Path.create("b"));
		cmd1.changeset(3).execute(sink1 = new ByteArrayChannel());
		cmd2.changeset(3).execute(sink2 = new ByteArrayChannel());
		assertArrayEquals(sink1.toArray(), sink2.toArray());
		//
		cmd2.changeset(4).execute(sink2 = new ByteArrayChannel()); // ensure d in r4 is not from a or b
		assertArrayEquals("d:4\n".getBytes(), sink2.toArray());
		cmd2.changeset(5).execute(sink2 = new ByteArrayChannel()); // d in r5 is copy of b in r4
		cmd1.changeset(4).execute(sink1 = new ByteArrayChannel());
		assertArrayEquals(sink1.toArray(), sink2.toArray());
	}
}
