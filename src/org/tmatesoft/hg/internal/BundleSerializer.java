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

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.LogFacility;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BundleSerializer implements DataSerializer.DataSource {
	private final LogFacility log;
	private final File bundleFile;
	
	public static BundleSerializer newInstance(SessionContext ctx, HgBundle bundle) {
		return new BundleSerializer(ctx.getLog(), HgInternals.getBundleFile(bundle));
	}

	public BundleSerializer(LogFacility logFacility, File bundleFile) {
		log = logFacility;
		this.bundleFile = bundleFile;
	}

	public void serialize(DataSerializer out) throws HgIOException, HgRuntimeException {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(bundleFile);
			byte[] buffer = new byte[8*1024];
			int r;
			while ((r = fis.read(buffer, 0, buffer.length)) > 0) {
				out.write(buffer, 0, r);
			}
			
		} catch (IOException ex) {
			throw new HgIOException("Failed to serialize bundle", bundleFile);
		} finally {
			new FileUtils(log, this).closeQuietly(fis, bundleFile);
		}
	}

	public int serializeLength() throws HgRuntimeException {
		return Internals.ltoi(bundleFile.length());
	}
}