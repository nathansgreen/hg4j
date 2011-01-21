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
 * contact TMate Software at support@svnkit.com
 */
package org.tmatesoft.hg.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.util.LinkedList;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ExecHelper {

	private final OutputParser parser;
	private final File dir;

	public ExecHelper(OutputParser outParser, File workingDir) {
		parser = outParser;
		dir = workingDir;
	}

	public void run(String... cmd) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
//		Process p = Runtime.getRuntime().exec(cmd, null, dir);
		InputStreamReader stdOut = new InputStreamReader(p.getInputStream());
		LinkedList<CharBuffer> l = new LinkedList<CharBuffer>();
		int r = -1;
		CharBuffer b = null;
		do {
			if (b == null || b.remaining() < b.capacity() / 3) {
				b = CharBuffer.allocate(512);
				l.add(b);
			}
			r = stdOut.read(b);
		} while (r != -1);
		int total = 0;
		for (CharBuffer cb : l) {
			total += cb.position();
			cb.flip();
		}
		CharBuffer res = CharBuffer.allocate(total);
		for (CharBuffer cb : l) {
			res.put(cb);
		}
		res.flip();
		p.waitFor();
		parser.parse(res);
	}
}
