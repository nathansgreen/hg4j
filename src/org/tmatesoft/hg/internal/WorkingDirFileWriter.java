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

import static org.tmatesoft.hg.util.LogFacility.Severity.Warn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgManifest;
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
	private final boolean execCap, symlinkCap;
	private final FileSystemHelper fileFlagsHelper;
	private File dest;
	private FileChannel destChannel;
	private int totalBytesWritten;
	private ByteArrayChannel linkChannel;
	private int fmode;

	public WorkingDirFileWriter(Internals internalRepo) {
		hgRepo = internalRepo;
		execCap = Internals.checkSupportsExecutables(internalRepo.getRepo().getWorkingDir());
		symlinkCap = Internals.checkSupportsSymlinks(internalRepo.getRepo().getWorkingDir());
		if (symlinkCap || execCap) {
			fileFlagsHelper = new FileSystemHelper(internalRepo.getSessionContext());
		} else  {
			fileFlagsHelper = null;
		}
	}
	
	/**
	 * Writes content of specified file revision into local filesystem, or create a symlink according to flags. 
	 * Executable bit is set if specified and filesystem supports it. 
	 */
	public void processFile(HgDataFile df, int fileRevIndex, HgManifest.Flags flags) throws IOException {
		try {
			prepare(df.getPath());
			if (flags != HgManifest.Flags.Link) {
				destChannel = new FileOutputStream(dest).getChannel();
			} else {
				linkChannel = new ByteArrayChannel();
			}
			df.contentWithFilters(fileRevIndex, this);
		} catch (CancelledException ex) {
			hgRepo.getSessionContext().getLog().dump(getClass(), Severity.Error, ex, "Our impl doesn't throw cancellation");
		} finally {
			if (flags != HgManifest.Flags.Link) {
				destChannel.close();
				destChannel = null;
				// leave dest in case anyone enquires with #getDestinationFile
			}
		}
		if (linkChannel != null && symlinkCap) {
			assert flags == HgManifest.Flags.Link;
			fileFlagsHelper.createSymlink(dest.getParentFile(), dest.getName(), linkChannel.toArray());
		} else if (flags == HgManifest.Flags.Exec && execCap) {
			fileFlagsHelper.setExecutableBit(dest.getParentFile(), dest.getName());
		}
		// Although HgWCStatusCollector treats 644 (`hg manifest -v`) and 664 (my fs) the same, it's better
		// to detect actual flags here
		fmode = flags.fsMode(); // default to one from manifest
		if (fileFlagsHelper != null) {
			// if neither execBit nor link is supported by fs, it's unlikely file mode is supported, too.
			try {
				fmode = fileFlagsHelper.getFileMode(dest, fmode);
			} catch (IOException ex) {
				// Warn, we've got default value and can live with it
				hgRepo.getSessionContext().getLog().dump(getClass(), Warn, ex, "Failed get file access rights");
			}
		}
	}

	public void prepare(Path fname) throws IOException {
		String fpath = fname.toString();
		dest = new File(hgRepo.getRepo().getWorkingDir(), fpath);
		if (fpath.indexOf('/') != -1) {
			dest.getParentFile().mkdirs();
		}
		destChannel = null;
		linkChannel = null;
		totalBytesWritten = 0;
		fmode = 0;
	}

	public int write(ByteBuffer buffer) throws IOException, CancelledException {
		final int written;
		if (linkChannel != null) {
			written = linkChannel.write(buffer);
		} else {
			written = destChannel.write(buffer);
		}
		totalBytesWritten += written;
		return written;
	}

	/**
	 * Information purposes only, to find out trouble location if {@link #processFile(HgDataFile, int)} fails
	 */
	public File getDestinationFile() {
		return dest;
	}
	
	public int bytesWritten() {
		return totalBytesWritten;
	}

	public int fmode() {
		return fmode;
	}

	public int mtime() {
		return (int) (dest.lastModified() / 1000);
	}
}
