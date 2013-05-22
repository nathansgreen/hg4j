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

import java.util.zip.Deflater;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.LogFacility.Severity;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogCompressor {
	private final SessionContext ctx;
	private final Deflater zip;
	private DataSerializer.DataSource sourceData;
	private int compressedLen;
	
	public RevlogCompressor(SessionContext sessionCtx) {
		ctx = sessionCtx;
		zip = new Deflater();
	}

	public void reset(DataSerializer.DataSource source) {
		sourceData = source;
		compressedLen = -1;
	}
	
	// out stream is not closed!
	public int writeCompressedData(DataSerializer out) throws HgIOException, HgRuntimeException {
		zip.reset();
		DeflaterDataSerializer dds = new DeflaterDataSerializer(out, zip, sourceData.serializeLength());
		sourceData.serialize(dds);
		dds.finish();
		return zip.getTotalOut();
	}

	public int getCompressedLength() throws HgRuntimeException {
		if (compressedLen != -1) {
			return compressedLen;
		}
		Counter counter = new Counter();
		try {
			compressedLen = writeCompressedData(counter);
			assert counter.totalWritten == compressedLen;
	        return compressedLen;
		} catch (HgIOException ex) {
			// can't happen provided we write to our stream that does nothing but byte counting
			ctx.getLog().dump(getClass(), Severity.Error, ex, "Failed estimating compressed length of revlog data");
			return counter.totalWritten; // use best known value so far
		}
	}

	private static class Counter extends DataSerializer {
		public int totalWritten = 0;

		public void writeByte(byte... values) throws HgIOException {
			totalWritten += values.length;
		}

		public void writeInt(int... values) throws HgIOException {
			totalWritten += 4 * values.length;
		}

		public void write(byte[] data, int offset, int length) throws HgIOException {
			totalWritten += length;
		}
	}
}
