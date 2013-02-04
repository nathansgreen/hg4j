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
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * {@link DeflaterOutputStream} counterpart for {@link DataSerializer} API
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
class DeflaterDataSerializer extends DataSerializer {
	
	private static final int AUX_BUFFER_CAPACITY = 5; // room for 5 ints

	private final DataSerializer delegate;
	private final Deflater deflater;
	private final byte[] deflateOutBuffer;
	private final byte[] auxBuffer;

	public DeflaterDataSerializer(DataSerializer delegateSerializer, Deflater def, int bufferSizeHint) {
		delegate = delegateSerializer;
		deflater = def;
		deflateOutBuffer = new byte[bufferSizeHint <= 0 ? 2048 : bufferSizeHint];
		auxBuffer = new byte[4 * AUX_BUFFER_CAPACITY]; // sizeof(int) * capacity
	}

	@Override
	public void writeInt(int... values) throws IOException {
		for (int i = 0; i < values.length; i+= AUX_BUFFER_CAPACITY) {
			int idx = 0;
			for (int j = i, x = Math.min(values.length, i + AUX_BUFFER_CAPACITY); j < x; j++) {
				int v = values[j];
				auxBuffer[idx++] = (byte) ((v >>> 24) & 0x0ff);
				auxBuffer[idx++] = (byte) ((v >>> 16) & 0x0ff);
				auxBuffer[idx++] = (byte) ((v >>> 8) & 0x0ff);
				auxBuffer[idx++] = (byte) (v & 0x0ff);
			}
			internalWrite(auxBuffer, 0, idx);
		}
	}

	@Override
	public void write(byte[] data, int offset, int length) throws IOException {
		// @see DeflaterOutputStream#write(byte[], int, int)
		int stride = deflateOutBuffer.length;
		for (int i = 0; i < length; i += stride) {
			internalWrite(data, offset + i, Math.min(stride, length - i));
		}
	}
	
	private void internalWrite(byte[] data, int offset, int length) throws IOException {
		deflater.setInput(data, offset, length);
		while (!deflater.needsInput()) {
			deflate();
		}
	}

	@Override
	public void done() {
		delegate.done();
	}

	public void finish() throws IOException {
		if (!deflater.finished()) {
			deflater.finish();
			while (!deflater.finished()) {
				deflate();
			}
		}
	}

	protected void deflate() throws IOException {
		int len = deflater.deflate(deflateOutBuffer, 0, deflateOutBuffer.length);
		if (len > 0) {
			delegate.write(deflateOutBuffer, 0, len);
		}
	}
}
