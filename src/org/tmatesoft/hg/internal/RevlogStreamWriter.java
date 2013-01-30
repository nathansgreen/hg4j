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

import static org.tmatesoft.hg.internal.Internals.REVLOGV1_RECORD_SIZE;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.tmatesoft.hg.core.Nodeid;

/**
 * 
 * TODO check if index is too big and split into index+data
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogStreamWriter {

	
	public static class HeaderWriter {
		private final ByteBuffer header;
		private final boolean isInline;
		private long offset;
		private int length, compressedLength;
		private int baseRev, linkRev, p1, p2;
		private Nodeid nodeid;
		
		public HeaderWriter(boolean inline) {
			isInline = inline;
			header = ByteBuffer.allocate(REVLOGV1_RECORD_SIZE);
		}
		
		public HeaderWriter offset(long offset) {
			this.offset = offset;
			return this;
		}
		
		public int baseRevision() {
			return baseRev;
		}
		
		public HeaderWriter baseRevision(int baseRevision) {
			this.baseRev = baseRevision;
			return this;
		}
		
		public HeaderWriter length(int len, int compressedLen) {
			this.length = len;
			this.compressedLength = compressedLen;
			return this;
		}
		
		public HeaderWriter parents(int parent1, int parent2) {
			p1 = parent1;
			p2 = parent2;
			return this;
		}
		
		public HeaderWriter linkRevision(int linkRevision) {
			this.linkRev = linkRevision;
			return this;
		}
		
		public HeaderWriter nodeid(Nodeid n) {
			this.nodeid = n;
			return this;
		}

		public void write(OutputStream out) throws IOException {
			header.clear();
			if (offset == 0) {
				int version = 1 /* RevlogNG */;
				if (isInline) {
					final int INLINEDATA = 1 << 16; // FIXME extract constant
					version |= INLINEDATA;
				}
				header.putInt(version);
				header.putInt(0);
			} else {
				header.putLong(offset << 16);
			}
			header.putInt(compressedLength);
			header.putInt(length);
			header.putInt(baseRev);
			header.putInt(linkRev);
			header.putInt(p1);
			header.putInt(p2);
			header.put(nodeid.toByteArray());
			// assume 12 bytes left are zeros
			out.write(header.array());

			// regardless whether it's inline or separate data,
			// offset field always represent cumulative compressedLength 
			// (while offset in the index file with inline==true differs by n*sizeof(header), where n is entry's position in the file) 
			offset += compressedLength;
		}
	}
	
	
	private final DigestHelper dh = new DigestHelper();
	
	public void addRevision(byte[] content, int linkRevision, int p1, int p2) {
		Nodeid p1Rev = parent(p1);
		Nodeid p2Rev = parent(p2);
		byte[] revisionBytes = dh.sha1(p1Rev, p2Rev, content).asBinary();
		//final Nodeid revision = Nodeid.fromBinary(revisionBytes, 0);
		// cache last revision (its delta and baseRev)
		PatchGenerator pg = new PatchGenerator();
		byte[] prev = null;
		Patch patch = pg.delta(prev, content);
		byte[] patchContent;
		// rest as in HgCloneCommand
	}
	
	private Nodeid parent(int parentIndex) {
		return null;
	}
}
