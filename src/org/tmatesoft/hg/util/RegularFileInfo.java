/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import static org.tmatesoft.hg.util.LogFacility.Severity.Info;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadableByteChannel;

import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.StreamLogFacility;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RegularFileInfo implements FileInfo {
	private final boolean supportsExec, supportsLink;
	private final RegularFileStats fileFlagsHelper; // null if both supportsLink and supportExec are false
	private File file;
	
	public RegularFileInfo(SessionContext ctx) {
		this(ctx, false, false);
	}
	public RegularFileInfo(SessionContext ctx, boolean supportExecFlag, boolean supportSymlink) {
		supportsLink = supportSymlink;
		supportsExec = supportExecFlag;
		if (supportSymlink || supportExecFlag) {
			fileFlagsHelper = new RegularFileStats(ctx);
		} else  {
			fileFlagsHelper = null;
		}
	}
	
	public void init(File f) {
		file = f;
		if (fileFlagsHelper != null) {
			fileFlagsHelper.init(file);
		}
	}
	
	public boolean exists() {
		// java.io.File for symlinks without proper target says it doesn't exist.
		// since we found this symlink in directory listing, it's safe to say it exists just based on the fact it's link
		return isSymlink() || (file.canRead() && file.isFile());
	}

	public int lastModified() {
		// TODO [post-1.1] for symlinks, this returns incorrect mtime of the target file, not that of link itself
		// Besides, timestame if link points to non-existing file is 0.
		// However, it result only in slowdown in WCStatusCollector, as it need to perform additional content check
		return (int) (file.lastModified() / 1000);
	}

	public long length() {
		if (isSymlink()) {
			return getLinkTargetBytes().length;
		}
		return file.length();
	}

	public ReadableByteChannel newInputChannel() {
		try {
			if (isSymlink()) {
				return new ByteArrayReadableChannel(getLinkTargetBytes());
			} else {
				// TODO [2.0 API break]  might be good idea replace channel with smth 
				// else, to ensure #close() disposes FileDescriptor. Now
				// FD has usage count of two (new FileInputStream + getChannel),
				// and FileChannel#close decrements only 1, second has to wait FIS#finalize() 
				return new FileInputStream(file).getChannel();
			}
		} catch (FileNotFoundException ex) {
			StreamLogFacility.newDefault().dump(getClass(), Info, ex, null);
			// shall not happen, provided this class is used correctly
			return new ByteArrayReadableChannel(null);
		}
	}

	public boolean isExecutable() {
		return supportsExec && fileFlagsHelper.isExecutable();
	}
	
	public boolean isSymlink() {
		return supportsLink && fileFlagsHelper.isSymlink();
	}
	
	@Override
	public String toString() {
		char t = exists() ? (isExecutable() ? '*' : (isSymlink() ? '@' : '-')) : '!';
		return String.format("RegularFileInfo[%s %c]", file.getPath(), t);
	}
	
	private byte[] getLinkTargetBytes() {
		assert isSymlink();
		// no idea what encoding Mercurial uses for link targets, assume platform native is ok
		return fileFlagsHelper.getSymlinkTarget().getBytes();
	}


	private static class ByteArrayReadableChannel implements ReadableByteChannel {
		private final byte[] data;
		private boolean closed = false; // initially open
		private int firstAvailIndex = 0;
		
		ByteArrayReadableChannel(byte[] dataToStream) {
			data = dataToStream;
		}

		public boolean isOpen() {
			return !closed;
		}

		public void close() throws IOException {
			closed = true;
		}

		public int read(ByteBuffer dst) throws IOException {
			if (closed) {
				throw new ClosedChannelException();
			}
			int remainingBytes = data.length - firstAvailIndex;
			if (data == null || remainingBytes == 0) {
				// EOF right away
				return -1;
			}
			int x = Math.min(dst.remaining(), remainingBytes);
			for (int i = firstAvailIndex, lim = firstAvailIndex + x; i < lim; i++) {
				dst.put(data[i]);
			}
			firstAvailIndex += x;
			return x;
		}
	}
}
