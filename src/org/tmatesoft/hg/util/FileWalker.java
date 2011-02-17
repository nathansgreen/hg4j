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
package org.tmatesoft.hg.util;

import java.io.File;
import java.util.LinkedList;
import java.util.NoSuchElementException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileWalker implements FileIterator {

	private final File startDir;
	private final Path.Source pathHelper;
	private final LinkedList<File> dirQueue;
	private final LinkedList<File> fileQueue;
	private File nextFile;
	private Path nextPath;

	public FileWalker(File dir, Path.Source pathFactory) {
		startDir = dir;
		pathHelper = pathFactory;
		dirQueue = new LinkedList<File>();
		fileQueue = new LinkedList<File>();
		reset();
	}

	public void reset() {
		fileQueue.clear();
		dirQueue.clear();
		dirQueue.add(startDir);
		nextFile = null;
		nextPath = null;
	}
	
	public boolean hasNext() {
		return fill();
	}

	public void next() {
		if (!fill()) {
			throw new NoSuchElementException();
		}
		nextFile = fileQueue.removeFirst();
		nextPath = pathHelper.path(nextFile.getPath());
	}

	public Path name() {
		return nextPath;
	}
	
	public File file() {
		return nextFile;
	}
	
	private File[] listFiles(File f) {
		// in case we need to solve os-related file issues (mac with some encodings?)
		return f.listFiles();
	}

	// return true when fill added any elements to fileQueue. 
	private boolean fill() {
		while (fileQueue.isEmpty()) {
			if (dirQueue.isEmpty()) {
				return false;
			}
			while (!dirQueue.isEmpty()) {
				File dir = dirQueue.removeFirst();
				for (File f : listFiles(dir)) {
					if (f.isDirectory()) {
						if (!".hg".equals(f.getName())) {
							dirQueue.addLast(f);
						}
					} else {
						fileQueue.addLast(f);
					}
				}
				break;
			}
		}
		return !fileQueue.isEmpty();
	}
}
