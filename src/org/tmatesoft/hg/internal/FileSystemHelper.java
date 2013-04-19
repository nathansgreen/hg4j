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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.tmatesoft.hg.core.SessionContext;

/**
 * TODO Merge with RegularFileStats
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileSystemHelper {
	
	private final SessionContext ctx;
	private final List<String> linkCmd, chmodCmd, statCmd;
	private final ProcessExecHelper execHelper;

	public FileSystemHelper(SessionContext sessionContext) {
		ctx = sessionContext;
		if (Internals.runningOnWindows()) {
			linkCmd = Arrays.asList("mklink", "%1", "%2");
			chmodCmd = Collections.emptyList();
			statCmd = Collections.emptyList();
		} else {
			linkCmd = Arrays.asList("/bin/ln", "-s", "%2", "%1");
			chmodCmd = Arrays.asList("/bin/chmod", "+x", "%1");
			statCmd = Arrays.asList("stat", "--format=%a", "%1");
		}
		execHelper = new ProcessExecHelper();
	}

	public void createSymlink(File parentDir, String linkName, byte[] target) throws IOException {
		ArrayList<String> command = new ArrayList<String>(linkCmd);
		command.set(command.indexOf("%1"), linkName);
		String targetFilename = Internals.getFileEncoding(ctx).decode(ByteBuffer.wrap(target)).toString();
		command.set(command.indexOf("%2"), targetFilename);
		execHelper.cwd(parentDir);
		try {
			execHelper.exec(command);
		} catch (InterruptedException ex) {
			throw new IOException(ex);
		}
	}
	
	public void setExecutableBit(File parentDir, String fname) throws IOException {
		if (chmodCmd.isEmpty()) {
			return;
		}
		ArrayList<String> command = new ArrayList<String>(chmodCmd);
		command.set(command.indexOf("%1"), fname);
		execHelper.cwd(parentDir);
		try {
			execHelper.exec(command);
		} catch (InterruptedException ex) {
			throw new IOException(ex);
		}
	}

	public int getFileMode(File file, int defaultValue) throws IOException {
		if (statCmd.isEmpty()) {
			return defaultValue;
		}
		ArrayList<String> command = new ArrayList<String>(statCmd);
		command.set(command.indexOf("%1"), file.getAbsolutePath());
		String result = null;
		try {
			result = execHelper.exec(command).toString().trim();
			if (result.isEmpty()) {
				return defaultValue;
			}
			return Integer.parseInt(result, 8);
		} catch (InterruptedException ex) {
			throw new IOException(ex);
		} catch (NumberFormatException ex) {
			ctx.getLog().dump(getClass(), Warn, ex, String.format("Bad value for access rights:%s", result));
			return defaultValue;
		}
	}
}
