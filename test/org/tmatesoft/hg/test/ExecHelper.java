/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import org.tmatesoft.hg.internal.ProcessExecHelper;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ExecHelper extends ProcessExecHelper {

	private final OutputParser parser;

	public ExecHelper(OutputParser outParser, File workingDir) {
		parser = outParser;
		super.cwd(workingDir);
	}
	
	@Override
	protected List<String> prepareCommand(List<String> cmd) {
		String commandName = cmd.get(0);
		if (System.getProperty("os.name").startsWith("Windows")) {
			StringTokenizer st = new StringTokenizer(System.getenv("PATH"), ";");
			while (st.hasMoreTokens()) {
				File pe = new File(st.nextToken());
				if (new File(pe, commandName + ".exe").exists()) {
					return cmd;
				}
				// PATHEXT controls precedence of .exe, .bat and .cmd files, usually .exe wins
				if (new File(pe, commandName + ".bat").exists() || new File(pe, commandName + ".cmd").exists()) {
					ArrayList<String> command = new ArrayList<String>();
					command.add("cmd.exe");
					command.add("/C");
					command.addAll(cmd);
					return command;
				}
			}
		}
		return super.prepareCommand(cmd);
	}
	
	public void run(String... cmd) throws IOException, InterruptedException {
		CharSequence res = super.exec(cmd);
		parser.parse(res);
	}

	public int getExitValue() {
		return super.exitValue();
	}
}
