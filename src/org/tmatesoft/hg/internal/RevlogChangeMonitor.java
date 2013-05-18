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

import java.io.File;
import java.util.WeakHashMap;

/**
 * Detect changes to revlog files. Not a general file change monitoring as we utilize the fact revlogs are append-only (and even in case
 * of stripped-off tail revisions, with e.g. mq, detection approach is still valid).
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogChangeMonitor {
	
	private final WeakHashMap<File, Long> lastKnownSize;
	private final WeakHashMap<File, Long> lastKnownTime;
	private final File soleFile;
	private long soleFileSize = -1;
	private long soleFileTime = -1;
	
	// use single for multiple files. TODO [1.2] repository/session context shall provide
	// alternative (configurable) implementations, so that Java7 users may supply better one
	public RevlogChangeMonitor() {
		lastKnownSize = new WeakHashMap<File, Long>();
		lastKnownTime= new WeakHashMap<File, Long>();
		soleFile = null;
	}
	
	public RevlogChangeMonitor(File f) {
		assert f != null;
		lastKnownSize = lastKnownTime = null;
		soleFile = f;
	}
	
	public void touch(File f) {
		assert f != null;
		if (lastKnownSize == null) {
			assert f == soleFile;
			soleFileSize = f.length();
			soleFileTime = f.lastModified();
		} else {
			lastKnownSize.put(f, f.length());
			lastKnownTime.put(f, f.lastModified());
		}
	}
	
	public boolean hasChanged(File f) {
		assert f != null;
		if (lastKnownSize == null) {
			assert f == soleFile;
			return soleFileSize != f.length() || soleFileTime != f.lastModified();
		} else {
			Long lastSize = lastKnownSize.get(f);
			Long lastTime = lastKnownTime.get(f);
			if (lastSize == null || lastTime == null) {
				return true;
			}
			return f.length() != lastSize || f.lastModified() != lastTime;
		}
	}
}
