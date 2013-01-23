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
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogCompressor {
	private final Deflater zip;
	private byte[] sourceData;
	private int compressedLenEstimate;
	
	public RevlogCompressor() {
		zip = new Deflater();
	}

	public void reset(byte[] source) {
		sourceData = source;
		compressedLenEstimate = -1;
	}
	
	public int writeCompressedData(OutputStream out) throws IOException {
		zip.reset();
		DeflaterOutputStream dos = new DeflaterOutputStream(out, zip, Math.min(2048, sourceData.length));
		dos.write(sourceData);
		dos.finish();
		return zip.getTotalOut();
	}

	public int getCompressedLengthEstimate() {
		if (compressedLenEstimate != -1) {
			return compressedLenEstimate;
		}
		zip.reset();
		int rv = 0;
		// from DeflaterOutputStream:
		byte[] buffer = new byte[Math.min(2048, sourceData.length)];
        for (int i = 0, stride = buffer.length; i < sourceData.length; i+= stride) {
            zip.setInput(sourceData, i, Math.min(stride, sourceData.length - i));
            while (!zip.needsInput()) {
            	rv += zip.deflate(buffer, 0, buffer.length);
            }
        }
        zip.finish();
        while (!zip.finished()) {
        	rv += zip.deflate(buffer, 0, buffer.length);
        }
        return compressedLenEstimate = rv;
	}
}
