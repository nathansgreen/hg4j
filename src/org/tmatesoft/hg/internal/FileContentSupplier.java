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
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.DataSerializer.DataSource;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * {@link DataSource} that reads from regular files
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileContentSupplier implements DataSource {
	private final File file;
	private final SessionContext ctx;
	
	public FileContentSupplier(HgRepository repo, Path file) {
		this(repo, new File(repo.getWorkingDir(), file.toString()));
	}

	public FileContentSupplier(SessionContext.Source ctxSource, File f) {
		ctx = ctxSource.getSessionContext();
		file = f;
	}
	
	public void serialize(DataSerializer out) throws HgIOException {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			FileChannel fc = fis.getChannel();
			ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(100*1024, fc.size()));
			while (fc.read(buffer) != -1) {
				buffer.flip();
				// #allocate() above ensures backing array
				out.write(buffer.array(), 0, buffer.limit());
				buffer.clear();
			}
		} catch (IOException ex) {
			throw new HgIOException("Failed to get content of the file", ex, file);
		} finally {
			new FileUtils(ctx.getLog(), this).closeQuietly(fis);
		}
	}
	
	public int serializeLength() {
		return Internals.ltoi(file.length());
	}
}