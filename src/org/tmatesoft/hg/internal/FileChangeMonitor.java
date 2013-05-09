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

/**
 * This shall become interface/abstract class accessible from SessionContext,
 * with plugable implementations, e.g. Java7 (file monitoring facilities) based,
 * or any other convenient means. It shall allow both "check at the moment asked" 
 * and "collect changes and dispatch on demand" implementation approaches, so that
 * implementors may use best available technology   
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileChangeMonitor {
	private final File file;
	private long lastModified;
	private long length;
	
	/**
	 * First round: support for 1-monitor-1-file only
	 * Next round: 1-monitor-N files
	 */
	public FileChangeMonitor(File f) {
		file = f;
	}
	
	// shall work for files that do not exist
	public void touch(Object source) {
		lastModified = file.lastModified();
		length = file.length();
	}
	
	public void check(Object source, Action onChange) {
		if (changed(source)) {
			onChange.changed();
		}
	}

	public boolean changed(Object source) {
		if (file.lastModified() != lastModified) {
			return true;
		}
		return file.length() != length; 
	}
	
	public interface Action {
		public void changed();
	}
}
