/*
 * Copyright (c) 2010-2011 TMate Software Ltd
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
package org.tmatesoft.hg.console;

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.ByteChannel;


/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Cat {

	public static void main(String[] args) throws Exception {
		Options cmdLineOpts = Options.parse(args);
		HgRepository hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		int rev = cmdLineOpts.getSingleInt(TIP, "-r", "--rev");
		OutputStreamChannel out = new OutputStreamChannel(System.out);
		for (String fname : cmdLineOpts.getList("")) {
			System.out.println(fname);
			HgDataFile fn = hgRepo.getFileNode(fname);
			if (fn.exists()) {
				fn.contentWithFilters(rev, out);
				System.out.println();
			} else {
				System.out.printf("%s not found!\n", fname);
			}
		}
	}

	private static class OutputStreamChannel implements ByteChannel {

		private final OutputStream stream;

		public OutputStreamChannel(OutputStream out) {
			stream = out;
		}

		public int write(ByteBuffer buffer) throws IOException {
			int count = buffer.remaining();
			while(buffer.hasRemaining()) {
				stream.write(buffer.get());
			}
			return count;
		}
	}
}
