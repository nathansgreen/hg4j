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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.repo.CommitFacility;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * FIXME files are opened at the moment of instantiation, though the moment the data is requested might be distant
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileContentSupplier implements CommitFacility.ByteDataSupplier {
	private final FileChannel channel;
	private IOException error;
	
	public FileContentSupplier(HgRepository repo, Path file) throws HgIOException {
		this(new File(repo.getWorkingDir(), file.toString()));
	}

	public FileContentSupplier(File f) throws HgIOException {
		if (!f.canRead()) {
			throw new HgIOException(String.format("Can't read file %s", f), f);
		}
		try {
			channel = new FileInputStream(f).getChannel();
		} catch (FileNotFoundException ex) {
			throw new HgIOException("Can't open file", ex, f);
		}
	}

	public int read(ByteBuffer buf) {
		if (error != null) {
			return -1;
		}
		try {
			return channel.read(buf);
		} catch (IOException ex) {
			error = ex;
		}
		return -1;
	}
	
	public void done() throws IOException {
		channel.close();
		if (error != null) {
			throw error;
		}
	}
}