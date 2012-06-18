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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.util.LogFacility.Severity.Info;

import java.io.PrintStream;

import org.tmatesoft.hg.util.LogFacility;

/**
 * Primitive implementation of {@link LogFacility} that directs all output to specified {@link PrintStream}.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class StreamLogFacility implements LogFacility {
	
	private final boolean isDebug;
	private final Severity severity;
	protected final boolean timestamp;
	protected final PrintStream outStream;

	public StreamLogFacility(Severity level, boolean needTimestamp, PrintStream out) {
		assert level != null;
		severity = level;
		isDebug = level == Severity.Debug;
		timestamp = needTimestamp;
		outStream = out;
	}

	public boolean isDebug() {
		return isDebug;
	}
	
	public Severity getLevel() {
		return severity;
	}

	public void dump(Class<?> src, Severity severity, String format, Object... args) {
		if (severity.ordinal() >= getLevel().ordinal()) {
			printf(severity, src, format, args);
		}
	}

	public void dump(Class<?> src, Severity severity, Throwable th, String message) {
		if (severity.ordinal() >= getLevel().ordinal()) {
			printf(severity, src, th, message);
		}
	}

	protected void printf(Severity level, Class<?> src, String format, Object... args) {
		String msg = String.format(format, args);
		if (timestamp) {
			outStream.printf(isDebug ? "%tT.%1$tL " : "%tT ", System.currentTimeMillis());
		}
		if (isDebug) {
			String cn = src.getName();
			if (cn.startsWith("org.tmatesoft.hg.")) {
				cn = "oth." + cn.substring("org.tmatesoft.hg.".length());
			}
			outStream.printf("(%s) ", cn);
		}
		outStream.printf("%s: %s", level.toString().toUpperCase(), msg);
		if (format.length() == 0 || format.charAt(format.length() - 1) != '\n') {
			outStream.println();
		}
	}
	protected void printf(Severity level, Class<?> src, Throwable th, String msg) {
		if (msg != null || timestamp || isDebug || th == null) {
			printf(level, src, msg == null ? "" : msg, (Object[]) null);
		}
		if (th != null) {
			if (getLevel().ordinal() <= Info.ordinal()) {
				// full stack trace
				th.printStackTrace(outStream);
			} else {
				// just title of the exception
				outStream.printf("%s: %s\n", th.getClass().getName(), th.getMessage());
			}
		}
	}

	// alternative to hardcore System.out where SessionContext is not available now (or ever)
	public static LogFacility newDefault() {
		return new StreamLogFacility(Severity.Debug, true, System.out); 
	}
}
