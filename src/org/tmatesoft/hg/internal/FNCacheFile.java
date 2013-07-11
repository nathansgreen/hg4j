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

import static org.tmatesoft.hg.repo.HgRepositoryFiles.FNCache;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.util.Path;

/**
 * Append-only fncache support
 * 
 * <blockquote>
 * The fncache file contains the paths of all filelog files in the store as encoded by mercurial.filelog.encodedir. The paths are separated by '\n' (LF).
 * </blockquote>
 * @see http://mercurial.selenic.com/wiki/fncacheRepoFormat
 * 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FNCacheFile {
	
	private final Internals repo;
//	private final List<Path> files;
	private final List<Path> addedDotI;
	private final List<Path> addedDotD;
	private final FNCachePathHelper pathHelper;

	public FNCacheFile(Internals internalRepo) {
		repo = internalRepo;
//		files = new ArrayList<Path>();
		pathHelper = new FNCachePathHelper();
		addedDotI = new ArrayList<Path>(5);
		addedDotD = new ArrayList<Path>(5);
	}

	/*
	 * For append-only option, we don't care reading the original content
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
			// XXX plain wrong, need either to decode paths and strip off .i/.d or (if keep names as is) change write()
			files.add(pathFactory.path(e));
		}
	}
	*/
	
	public void write(Transaction tr) throws HgIOException {
		if (addedDotI.isEmpty() && addedDotD.isEmpty()) {
			return;
		}
		File f = repo.getRepositoryFile(FNCache);
		f.getParentFile().mkdirs();
		final EncodingHelper fnEncoder = repo.buildFileNameEncodingHelper();
		ArrayList<CharBuffer> added = new ArrayList<CharBuffer>();
		for (Path p : addedDotI) {
			added.add(CharBuffer.wrap(pathHelper.rewrite(p)));
		}
		for (Path p : addedDotD) {
			// XXX FNCachePathHelper always return name of an index file, need to change it into a name of data file,
			// although the approach (to replace last char) is depressingly awful
			CharSequence cs = pathHelper.rewrite(p);
			CharBuffer cb = CharBuffer.allocate(cs.length());
			cb.append(cs);
			cb.put(cs.length()-1, 'd');
			cb.flip();
			added.add(cb);
		}
		FileOutputStream fos = null;
		f = tr.prepare(f);
		try {
			fos = new FileOutputStream(f, true);
			FileChannel fncacheFile = fos.getChannel();
			ByteBuffer lf = ByteBuffer.wrap(new byte[] { 0x0A });
			for (CharBuffer b : added) {
				fncacheFile.write(fnEncoder.toFNCache(b));
				fncacheFile.write(lf);
				lf.rewind();
			}
			fncacheFile.force(true);
			tr.done(f);
		} catch (IOException ex) {
			tr.failure(f, ex);
			throw new HgIOException("Failed to write fncache", ex, f);
		} finally {
			new FileUtils(repo.getLog(), this).closeQuietly(fos, f);
		}
		
	}

	public void addIndex(Path p) {
		addedDotI.add(p);
	}

	public void addData(Path p) {
		addedDotD.add(p);
	}

	/**
	 * Register new files with fncache if one is enabled for the repo, do nothing otherwise
	 */
	public static class Mediator {
		private final Internals repo;
		private FNCacheFile fncache;
		private final Transaction tr;

		public Mediator(Internals internalRepo, Transaction transaction) {
			repo = internalRepo;
			tr = transaction;
		}
		
		public void registerNew(Path f, RevlogStream rs) {
			if (fncache != null || repo.fncacheInUse()) {
				if (fncache == null) {
					fncache = new FNCacheFile(repo);
				}
				fncache.addIndex(f);
				if (!rs.isInlineData()) {
					fncache.addData(f);
				}
			}
		}
		
		public void complete() throws HgIOException {
			if (fncache != null) {
				fncache.write(tr);
			}
		}
	}
}
