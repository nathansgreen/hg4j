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
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;

import org.tmatesoft.hg.util.Path;

/**
 * <blockquote>
 * The fncache file contains the paths of all filelog files in the store as encoded by mercurial.filelog.encodedir. The paths are separated by '\n' (LF).
 * </blockquote>
 * @see http://mercurial.selenic.com/wiki/fncacheRepoFormat
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FNCacheFile {
	
	private final Internals repo;
	private final ArrayList<Path> files;

	public FNCacheFile(Internals internalRepo) {
		repo = internalRepo;
		files = new ArrayList<Path>();
	}

	public void read(Path.Source pathFactory) throws IOException {
		File f = fncacheFile();
		files.clear();
		if (!f.exists()) {
			return;
		}
		ArrayList<String> entries = new ArrayList<String>();
		// names in fncache are in local encoding, shall translate to unicode
		new LineReader(f, repo.getSessionContext().getLog(), repo.getFilenameEncoding()).read(new LineReader.SimpleLineCollector(), entries);
		for (String e : entries) {
			files.add(pathFactory.path(e));
		}
	}
	
	public void write() throws IOException {
		if (files.isEmpty()) {
			return;
		}
		File f = fncacheFile();
		f.getParentFile().mkdirs();
		final Charset filenameEncoding = repo.getFilenameEncoding();
		FileOutputStream fncacheFile = new FileOutputStream(f);
		for (Path p : files) {
			String s = "data/" + p.toString() + ".i"; // TODO post-1.0 this is plain wrong. (a) likely need .d files, too; (b) what about dh/ location? 
			fncacheFile.write(s.getBytes(filenameEncoding));
			fncacheFile.write(0x0A); // http://mercurial.selenic.com/wiki/fncacheRepoFormat
		}
		fncacheFile.close();
	}

	public void add(Path p) {
		files.add(p);
	}

	private File fncacheFile() {
		return repo.getFileFromStoreDir("fncache");
	}
}
