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
import java.io.IOException;
import java.nio.ByteBuffer;

import org.tmatesoft.hg.core.HgCommitCommand;
import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidFileException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;

/**
 * Access content of the working copy. The difference with {@link FileContentSupplier} is that this one doesn't need {@link File}
 * in the working directory. However, provided this class is used from {@link HgCommitCommand} when "modified" file was detected,
 * it's odd to expect no file in the working dir.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class WorkingCopyContent implements DataSerializer.DataSource {
	private final HgDataFile file;

	public WorkingCopyContent(HgDataFile dataFile) {
		file = dataFile;
		if (!dataFile.exists()) {
			throw new IllegalArgumentException();
		}
	}

	public void serialize(final DataSerializer out) throws HgIOException, HgRuntimeException {
		final HgIOException failure[] = new HgIOException[1];
		try {
			// TODO #workingCopy API is very limiting, CancelledException is inconvenient, 
			// and absence of HgIOException is very uncomfortable
			file.workingCopy(new ByteChannel() {
				
				public int write(ByteBuffer buffer) throws IOException {
					try {
						if (buffer.hasArray()) {
							out.write(buffer.array(), buffer.position(), buffer.remaining());
						}
						int rv = buffer.remaining();
						buffer.position(buffer.limit()); // pretend we've consumed the data
						return rv;
					} catch (HgIOException ex) {
						failure[0] = ex;
						IOException e = new IOException();
						ex.initCause(ex); // XXX Java 1.5
						throw e;
					}
				}
			});
		} catch (HgInvalidFileException ex) {
			if (failure[0] != null) {
				throw failure[0];
			}
			throw new HgIOException("Write failure", ex, new File(file.getRepo().getWorkingDir(), file.getPath().toString()));
		} catch (CancelledException ex) {
			throw new HgInvalidStateException("Our channel doesn't cancel here");
		}
	}

	public int serializeLength() throws HgRuntimeException {
		return file.getLength(HgRepository.WORKING_COPY);
	}
}