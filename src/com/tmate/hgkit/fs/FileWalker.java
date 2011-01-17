/*
 * Copyright (c) 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.fs;

import java.io.File;
import java.util.LinkedList;
import java.util.NoSuchElementException;

/**
 *
 * @author artem
 */
public class FileWalker {

	private final File startDir;
	private final LinkedList<File> dirQueue;
	private final LinkedList<File> fileQueue;
	private File nextFile;
	private String nextPath;

	// FilenameFilter is used in a non-standard way - first argument, dir, is always startDir, 
	// while second arg, name, is startDir-relative path to the file in question
	public FileWalker(File startDir) {
		this.startDir = startDir;
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
		nextPath = path(nextFile);
	}

	public String name() {
		return nextPath;
	}
	
	public File file() {
		return nextFile;
	}
	
	private String path(File f) {
		// XXX LocalHgRepo#normalize
		String p = f.getPath().substring(startDir.getPath().length() + 1);
		return p.replace('\\', '/').replace("//", "/");
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
