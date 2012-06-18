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
package org.tmatesoft.hg.util;


/**
 * Facility to dump various messages.
 * 
 * Intention of this class is to abstract away almost any log facility out there clients might be using with the <b>Hg4J</b> library, 
 * not to be a full-fledged logging facility of its own.
 * 
 * Implementations may wrap platform- or application-specific loggers, e.g. {@link java.util.logging.Logger} or 
 * <code>org.eclipse.core.runtime.ILog</code>
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface LogFacility {
	
	public enum Severity {
		Debug, Info, Warn, Error // order is important
	}

	/**
	 * Effective way to avoid attempts to construct debug dumps when they are of no interest. Basically, <code>getLevel() < Info</code>
	 * 
	 * @return <code>true</code> if interested in debug dumps
	 */
	boolean isDebug();

	/**
	 * 
	 * @return lowest (from {@link Severity#Debug} to {@link Severity#Error} active severity level 
	 */
	Severity getLevel();
	
	/**
	 * Dump a message
	 * @param src identifies source of the message, never <code>null</code>
	 * @param severity one of predefined levels
	 * @param format message format suitable for {@link String#format(String, Object...)}, never <code>null</code>
	 * @param args optional arguments for the preceding format argument, may be <code>null</code>
	 */
	void dump(Class<?> src, Severity severity, String format, Object... args);
	
	/**
	 * Alternative to dump an exception
	 *  
	 * @param src identifies source of the message, never <code>null</code>
	 * @param severity one of predefined levels
	 * @param th original exception, never <code>null</code>
	 * @param message additional description of the error/conditions, may be <code>null</code>
	 */
	void dump(Class<?> src, Severity severity, Throwable th, String message);
}
