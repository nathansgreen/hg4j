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
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.Filter;
import org.tmatesoft.hg.internal.Filter.Direction;
import org.tmatesoft.hg.internal.KeywordFilter;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRepositoryFiles;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestKeywordFilter {
	
	private HgRepository repo;

	@Test
	public void testSmallBuffer() throws Exception {
		initRepo();
		final Filter kwFilter = createFilter(Filter.Direction.ToRepo);
		final byte[] in = "1\n2\n3\n".getBytes();
		ByteBuffer bb = kwFilter.filter(ByteBuffer.wrap(in));
		final byte[] out = new byte[bb.remaining()];
		bb.get(out);
		Assert.assertArrayEquals(in, out);
	}
	
	@Test
	public void testKeywordDrop() throws Exception {
		initRepo();
		final Filter kwFilter = createFilter(Filter.Direction.ToRepo);
		final byte[] in = "1\n$Revision: cf200271439a7ec256151b30bc4360b21db3542e$\n3\n".getBytes();
		ByteBuffer bb = kwFilter.filter(ByteBuffer.wrap(in));
		final byte[] out = new byte[bb.remaining()];
		bb.get(out);
		Assert.assertArrayEquals("1\n$Revision$\n3\n".getBytes(), out);
	}

	@Test
	public void testKeywordExpansion() throws Exception {
		Assert.fail("Need real repo with changeset to check kw expansion");
	}

	/**
	 * what if keyword is split between two input buffers  
	 */
	@Test
	public void testKeywordSplitInBuffer() throws Exception {
		initRepo();
		final byte[] in1 = "1\n$Id:whatever".getBytes();
		final byte[] in2 = " continues here$\n3\n".getBytes();
		ByteArrayChannel out = new ByteArrayChannel();
		final Filter kwFilter = createFilter(Filter.Direction.ToRepo);
		out.write(kwFilter.filter(ByteBuffer.wrap(in1)));
		out.write(kwFilter.filter(ByteBuffer.wrap(in2)));
		Assert.assertEquals("1\n$Id$\n3\n", new String(out.toArray()));
		// Same as above, to extreme - only $ in the first buffer
		final Filter kwFilter2 = createFilter(Filter.Direction.ToRepo);
		out = new ByteArrayChannel();
		out.write(kwFilter2.filter(ByteBuffer.wrap("1\n$".getBytes())));
		out.write(kwFilter2.filter(ByteBuffer.wrap("Id:whatever continues here$\n3\n".getBytes())));
		Assert.assertEquals("1\n$Id$\n3\n", new String(out.toArray()));
	}
	
	/**
	 * what if input contains smth similar to keyword but unless the second part of the buffer
	 * comes in, it's impossible to tell
	 */
	@Test
	public void testIncompleteKeyword() throws Exception {
		initRepo();
		final byte[] in1 = "1\n$Id:whatever".getBytes();
		final byte[] in2 = " id doesn't close here\n3\n".getBytes();
		ByteArrayChannel out = new ByteArrayChannel();
		final Filter kwFilter = createFilter(Filter.Direction.ToRepo);
		out.write(kwFilter.filter(ByteBuffer.wrap(in1)));
		out.write(kwFilter.filter(ByteBuffer.wrap(in2)));
		byte[] expected = new byte[in1.length + in2.length];
		System.arraycopy(in1, 0, expected, 0, in1.length);
		System.arraycopy(in2, 0, expected, in1.length, in2.length);
		Assert.assertEquals(new String(expected), new String(out.toArray()));
	}
	
	@Test
	public void testIncompleteKeywordAtEOF() throws Exception {
		initRepo();
		final byte[] in = "1\n$Id:whatever\n".getBytes();
		final Filter kwFilter = createFilter(Filter.Direction.ToRepo);
		ByteBuffer outBuf = kwFilter.filter(ByteBuffer.wrap(in));
		byte[] out = new byte[outBuf.remaining()];
		outBuf.get(out);
		Assert.assertEquals(new String(in), new String(out));
		//
		// incomplete $kw is stripped of in case of EOF
		final Filter kwFilter2 = createFilter(Filter.Direction.ToRepo);
		outBuf = kwFilter2.filter(ByteBuffer.wrap("1\n$Id:whatever".getBytes()));
		out = new byte[outBuf.remaining()];
		outBuf.get(out);
		Assert.assertEquals("1\n", new String(out));
	}

	private Filter createFilter(Direction dir) {
		final KeywordFilter.Factory kwFactory = new KeywordFilter.Factory();
		kwFactory.initialize(repo);
		return kwFactory.create(Path.create("a/b"), new Filter.Options(dir));
	}

	private HgRepository initRepo() throws Exception {
		final File repoLoc = RepoUtils.initEmptyTempRepo("test-kw-filter");
		final File hgrc = new File(repoLoc, HgRepositoryFiles.RepoConfig.getPath());
		RepoUtils.createFile(hgrc, "[extensions]\nkeyword=\n\n[keyword]\n**.*=\n");
		repo = new HgLookup().detect(repoLoc);
		return repo;
	}
}
