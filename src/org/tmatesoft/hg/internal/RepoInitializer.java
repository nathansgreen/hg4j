/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.internal.RequiresFile.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Responsible of `requires` processing both on repo read and repo write
 * XXX needs better name, perhaps
 * 
 * @see http://mercurial.selenic.com/wiki/RequiresFile
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RepoInitializer {
	private int requiresFlags;
	
	public RepoInitializer() {
	}
	
	public RepoInitializer initRequiresFromFile(File repoDir) throws HgInvalidControlFileException {
		File requiresFile = new File(repoDir, "requires"); // not #getFileFromRepoDir() just in case it gets modified later 
		try {
			int flags = new RequiresFile().parse(requiresFile);
			return setRequires(flags);
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Parse failed", ex, requiresFile);
		}
	}
	
	public RepoInitializer setRequires(int flags) {
		requiresFlags = flags;
		return this;
	}
	
	public int getRequires() {
		return requiresFlags;
	}

	public void initEmptyRepository(File repoDir) throws HgIOException {
		repoDir.mkdirs();
		final File requiresFile = new File(repoDir, "requires");
		try {
			FileOutputStream requiresStream = new FileOutputStream(requiresFile);
			StringBuilder sb = new StringBuilder(40);
			if ((requiresFlags & REVLOGV1) != 0) {
				sb.append("revlogv1\n");
			}
			if ((requiresFlags & STORE) != 0) {
				sb.append("store\n");
			}
			if ((requiresFlags & FNCACHE) != 0) {
				sb.append("fncache\n");
			}
			if ((requiresFlags & DOTENCODE) != 0) {
				sb.append("dotencode\n");
			}
			requiresStream.write(sb.toString().getBytes());
			requiresStream.close();
		} catch (IOException ex) {
			throw new HgIOException("Failed to initialize empty repo", ex, requiresFile);
		}
		if ((requiresFlags & STORE) != 0) {
			new File(repoDir, "store").mkdir(); // with that, hg verify says ok.
		}
	}

	public PathRewrite buildDataFilesHelper(SessionContext ctx) {
		Charset cs = Internals.getFileEncoding(ctx);
		// StoragePathHelper needs fine-grained control over char encoding, hence doesn't use EncodingHelper
		return new StoragePathHelper((requiresFlags & STORE) != 0, (requiresFlags & FNCACHE) != 0, (requiresFlags & DOTENCODE) != 0, cs);
	}

	public PathRewrite buildStoreFilesHelper() {
		if ((requiresFlags & STORE) != 0) {
			return new PathRewrite() {
				public CharSequence rewrite(CharSequence path) {
					return "store/" + path;
				}
			};
		} else {
			return new PathRewrite.Empty();
		}
	}
}
