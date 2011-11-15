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

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.PathPool;
import org.tmatesoft.hg.util.PathRewrite;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BasicSessionContext implements SessionContext {

	private PathPool pathPool;
	private final LogFacility logFacility;
	
	public BasicSessionContext(PathPool pathFactory, LogFacility log) {
		pathPool = pathFactory;
		logFacility = log != null ? log : new StreamLogFacility(true, true, true, System.out);
	}

	public PathPool getPathPool() {
		if (pathPool == null) {
			pathPool = new PathPool(new PathRewrite.Empty());
		}
		return pathPool;
	}

	public LogFacility getLog() {
		// e.g. for exceptions that we can't handle but log (e.g. FileNotFoundException when we've checked beforehand file.canRead()
		return logFacility;
	}

	public Object getProperty(String name, Object defaultValue) {
		String value = System.getProperty(name);
		return value == null ? defaultValue : value;
	}
}
