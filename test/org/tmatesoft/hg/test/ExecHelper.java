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
 * contact TMate Software at support@hg4j.com
 */
package org.tmatesoft.hg.test;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.StringTokenizer;

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
		ProcessBuilder pb = null;
		if (System.getProperty("os.name").startsWith("Windows")) {
			StringTokenizer st = new StringTokenizer(System.getenv("PATH"), ";");
			while (st.hasMoreTokens()) {
				File pe = new File(st.nextToken());
				if (new File(pe, cmd[0] + ".exe").exists()) {
					break;
				}
				// PATHEXT controls precedence of .exe, .bat and .cmd files, ususlly .exe wins
				if (new File(pe, cmd[0] + ".bat").exists() || new File(pe, cmd[0] + ".cmd").exists()) {
					ArrayList<String> command = new ArrayList<String>();
					command.add("cmd.exe");
					command.add("/C");
					command.addAll(Arrays.asList(cmd));
					pb = new ProcessBuilder(command);
					break;
				}
			}
		}
		if (pb == null) {
			pb = new ProcessBuilder(cmd);
		}
		Process p = pb.directory(dir).redirectErrorStream(true).start();
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
