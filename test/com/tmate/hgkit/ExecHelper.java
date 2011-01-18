/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.util.LinkedList;

/**
 *
 * @author artem
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
