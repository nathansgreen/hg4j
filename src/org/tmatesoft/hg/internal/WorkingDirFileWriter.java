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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.LogFacility.Severity;
import org.tmatesoft.hg.util.Path;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class WorkingDirFileWriter implements ByteChannel {

	
	private final Internals hgRepo;
	private File dest;
	private FileChannel destChannel;
	private int totalBytesWritten;

	public WorkingDirFileWriter(Internals internalRepo) {
		hgRepo = internalRepo;
	}
	
	public void processFile(HgDataFile df, int fileRevIndex) throws IOException {
		try {
			prepare(df.getPath());
			df.contentWithFilters(fileRevIndex, this);
		} catch (CancelledException ex) {
			hgRepo.getSessionContext().getLog().dump(getClass(), Severity.Error, ex, "Our impl doesn't throw cancellation");
		}
		finish();
	}

	public void prepare(Path fname) throws IOException {
		String fpath = fname.toString();
		dest = new File(hgRepo.getRepo().getWorkingDir(), fpath);
		if (fpath.indexOf('/') != -1) {
			dest.getParentFile().mkdirs();
		}
		destChannel = new FileOutputStream(dest).getChannel();
		totalBytesWritten = 0;
	}

	public int write(ByteBuffer buffer) throws IOException, CancelledException {
		int written = destChannel.write(buffer);
		totalBytesWritten += written;
		return written;
	}

	public void finish() throws IOException {
		destChannel.close();
		dest = null;
	}
	
	public int bytesWritten() {
		return totalBytesWritten;
	}
}
