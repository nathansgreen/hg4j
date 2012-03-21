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
package org.tmatesoft.hg.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Utility to run shell commands. Not thread-safe.
 * Beware of memory overcommitment issue on Linux - suprocess get allocated virtual memory of parent process size
 * @see http://developers.sun.com/solaris/articles/subprocess/subprocess.html
 * 
 * @author Artem Tikhomirov
 * @author Tmate Software Ltd.
 */
public class ProcessExecHelper {
	private File dir;
	private int exitValue;
	private ProcessBuilder pb;
	
	public ProcessExecHelper() {
	}
	
	protected List<String> prepareCommand(List<String> cmd) {
		return cmd;
	}
	
	public CharSequence exec(String... command) throws IOException, InterruptedException {
		return exec(Arrays.asList(command));
	}

	public CharSequence exec(List<String> command) throws IOException, InterruptedException {
		List<String> cmd = prepareCommand(command);
		if (pb == null) {
			pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
		} else {
			pb.command(cmd); // dir and redirect are set
		}
		Process p = pb.start();
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
		exitValue = p.exitValue();
		return res;
	}
	
	public int exitValue() {
		return exitValue;
	}

	public ProcessExecHelper cwd(File wd) {
		dir = wd;
		if (pb != null) {
			pb.directory(dir);
		}
		return this;
	}
}
