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

import java.io.IOException;
import java.io.InputStream;

/**
 * Wrap our internal API into a public one
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class DataAccessInputStream extends InputStream {
	
	private final DataAccess da;
	private int bytesLeft = -1;

	public DataAccessInputStream(DataAccess dataAccess) {
		da = dataAccess;
	}
	
	@Override
	public int available() throws IOException {
		initAvailable();
		return bytesLeft;
	}
	
	@Override
	public int read() throws IOException {
		initAvailable();
		if (bytesLeft == 0) {
			return -1;
		}
		int rv = da.readByte();
		bytesLeft--;
		return rv;
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		initAvailable();
		if (bytesLeft == 0) {
			return -1;
		}
		if (len == 0) {
			return 0;
		}
		int x = Math.min(len, bytesLeft);
		da.readBytes(b, off, x);
		bytesLeft -= x;
		return x;
	}


	private void initAvailable() throws IOException {
		da.reset();
		if (bytesLeft == -1) {
			bytesLeft = da.length();
		}
	}
}
