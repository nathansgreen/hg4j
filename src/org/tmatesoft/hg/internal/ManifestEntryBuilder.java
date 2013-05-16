/*
 * Copyright (c) 2012 TMate Software Ltd
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

import java.io.ByteArrayOutputStream;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataSerializer.DataSource;

/**
 * Create binary manifest entry ready to write down into 00manifest.i
 * <p>Usage:
 * <pre>
 *   ManifestEntryBuilder mb = new ManifestEntryBuilder();
 *   mb.reset().add("file1", file1.getRevision(r1));
 *   mb.add("file2", file2.getRevision(r2));
 *   byte[] manifestRecordData = mb.build();
 *   byte[] manifestRevlogHeader = buildRevlogHeader(..., sha1(parents, manifestRecordData), manifestRecordData.length);
 *   manifestIndexOutputStream.write(manifestRevlogHeader);
 *   manifestIndexOutputStream.write(manifestRecordData);
 * </pre>
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ManifestEntryBuilder implements DataSource {
	private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
	private final EncodingHelper encHelper;

	public ManifestEntryBuilder(EncodingHelper encodingHelper) {
		encHelper = encodingHelper;
	}
	
	public ManifestEntryBuilder reset() {
		buffer.reset();
		return this;
	}
	public ManifestEntryBuilder add(String fname, Nodeid revision) {
		byte[] b = encHelper.toManifest(fname);
		buffer.write(b, 0, b.length);
		buffer.write('\0');
		b = revision.toString().getBytes();
		buffer.write(b, 0, b.length);
		buffer.write('\n');
		return this;
	}

	public byte[] build() {
		return buffer.toByteArray();
	}

	public void serialize(DataSerializer out) throws HgIOException {
		byte[] r = build();
		out.write(r, 0 , r.length);
	}

	public int serializeLength() {
		return buffer.size();
	}

}
