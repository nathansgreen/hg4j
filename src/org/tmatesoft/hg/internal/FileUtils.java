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

import static org.tmatesoft.hg.util.LogFacility.Severity.Debug;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class FileUtils {
	
	private final LogFacility log;
	private final Class<?> troublemaker;
	
	public static void copyFile(File from, File to) throws HgIOException {
		new FileUtils(new StreamLogFacility(Debug, true, System.err), FileUtils.class).copy(from, to);
	}

	public FileUtils(LogFacility logFacility, Object troubleSource) {
		log = logFacility;
		if (troubleSource == null) {
			troublemaker = null;
		} else {
			troublemaker = troubleSource instanceof Class ? (Class<?>) troubleSource : troubleSource.getClass();
		}
	}

	public void copy(File from, File to) throws HgIOException {
		FileInputStream fis = null;
		FileOutputStream fos = null;
		try {
			fis = new FileInputStream(from);
			fos = new FileOutputStream(to);
			FileChannel input = fis.getChannel();
			FileChannel output = fos.getChannel();
			long count = input.size();
			long pos = 0;
			int zeroCopied = 0; // flag to prevent hang-up
			do {
				long c = input.transferTo(pos, count, output);
				pos += c;
				count -= c;
				if (c == 0) {
					if (++zeroCopied == 3) {
						String m = String.format("Can't copy %s to %s, transferTo copies 0 bytes. Position: %d, bytes left:%d", from.getName(), to.getName(), pos, count);
						throw new IOException(m);
					}
				} else {
					// reset
					zeroCopied = 0;
				}
			} while (count > 0);
			fos.close();
			fos = null;
			fis.close();
			fis = null;
		} catch (IOException ex) {
			// not in finally because I don't want to loose exception from fos.close()
			closeQuietly(fis, from);
			closeQuietly(fos, to);
			String m = String.format("Failed to copy %s to %s", from.getName(), to.getName());
			throw new HgIOException(m, ex, from);
		}
		/* Copy of cpython's 00changelog.d, 20Mb+
		 * Linux&Windows: 300-400 ms,
		 * Windows uncached run: 1.6 seconds
		 */
	}
	
	public void write(InputStream is, File file) throws IOException {
		FileOutputStream fos = new FileOutputStream(file);
		int r;
		byte[] buf = new byte[8*1024];
		while ((r = is.read(buf)) != -1) {
			fos.write(buf, 0, r);
		}
		fos.flush();
		fos.close();
	}
	
	public void closeQuietly(Closeable stream) {
		closeQuietly(stream, null);
	}

	public void closeQuietly(Closeable stream, File f) {
		if (stream != null) {
			try {
				stream.close();
			} catch (IOException ex) {
				// ignore
				final String msg;
				if (f == null) {
					msg = "Exception while closing stream quietly";
				} else {
					msg = String.format("Failed to close %s", f);
				}
				log.dump(troublemaker == null ? getClass() : troublemaker, Severity.Warn, ex, msg);
			}
		}
	}

	// nothing special, just a single place with common prefix
	public static File createTempFile() throws IOException {
		return File.createTempFile("hg4j-", null);
	}

	public static void rmdir(File dest) throws IOException {
		if (!dest.isDirectory()) {
			return;
		}
		LinkedList<File> queue = new LinkedList<File>();
		queue.addAll(Arrays.asList(dest.listFiles()));
		while (!queue.isEmpty()) {
			File next = queue.removeFirst();
			if (next.isDirectory()) {
				List<File> files = Arrays.asList(next.listFiles());
				if (!files.isEmpty()) {
					queue.addAll(files);
					queue.add(next);
				}
				// fall through
			} 
			next.delete();
		}
		dest.delete();
	}

}