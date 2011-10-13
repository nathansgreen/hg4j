/*
 * Copyright (c) 2011 TMate Software Ltd
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
import java.util.ArrayList;

/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat, in Changelog group description
 * 
 * range [start..end] in original source gets replaced with data of length (do not keep, use data.length instead)
 * range [end(i)..start(i+1)] is copied from the source
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Patch {
	private final IntVector starts, ends;
	private final ArrayList<byte[]> data;
	
	public Patch() {
		starts = new IntVector();
		ends = new IntVector();
		data = new ArrayList<byte[]>();
	}
	
	public int count() {
		return data.size();
	}

	// number of bytes this patch will add (or remove, if negative) from the base revision
	private int patchSizeDelta() {
		int rv = 0;
		int prevEnd = 0;
		for (int i = 0, x = data.size(); i < x; i++) {
			final int start = starts.get(i);
			final int len = data.get(i).length;
			rv += start - prevEnd; // would copy from original
			rv += len; // and add new
			prevEnd = ends.get(i);
		}
		rv -= prevEnd;
		return rv;
	}
	
	public byte[] apply(DataAccess baseRevisionContent, int outcomeLen) throws IOException {
		if (outcomeLen == -1) {
			outcomeLen = baseRevisionContent.length() + patchSizeDelta();
		}
		int prevEnd = 0, destIndex = 0;
		byte[] rv = new byte[outcomeLen];
		for (int i = 0, x = data.size(); i < x; i++) {
			final int start = starts.get(i);
			baseRevisionContent.seek(prevEnd);
			// copy source bytes that were not modified (up to start of the record)
			baseRevisionContent.readBytes(rv, destIndex, start - prevEnd);
			destIndex += start - prevEnd;
			// insert new data from the patch, if any
			byte[] d = data.get(i);
			System.arraycopy(d, 0, rv, destIndex, d.length);
			destIndex += d.length;
			prevEnd = ends.get(i);
		}
		baseRevisionContent.seek(prevEnd);
		// copy everything in the source past last record's end
		baseRevisionContent.readBytes(rv, destIndex, (int) (baseRevisionContent.length() - prevEnd));
		return rv;
	}
	
	public void clear() {
		starts.clear();
		ends.clear();
		data.clear();
	}
	
	/**
	 * Initialize instance from stream. Any previous patch information (i.e. if instance if reused) is cleared first.
	 * Read up to the end of DataAccess and interpret data as patch records.
	 */
	public void read(DataAccess da) throws IOException {
		clear();
		while (!da.isEmpty()) {
			readOne(da);
		}
	}

	/**
	 * Caller is responsible to ensure stream got some data to read
	 */
	public void readOne(DataAccess da) throws IOException {
		int s = da.readInt();
		int e = da.readInt();
		int len = da.readInt();
		byte[] src = new byte[len];
		da.readBytes(src, 0, len);
		starts.add(s);
		ends.add(e);
		data.add(src);
	}

/*
	private void add(Patch another, int index) {
		starts.add(another.starts.get(index));
		ends.add(another.ends.get(index));
		data.add(another.data.get(index));
	}

	/**
	 * Modify this patch with subsequent patch 
	 * /
	public void apply(Patch another) {
		Patch r = new Patch();
		int p1AppliedPos = 0;
		int p1PrevEnd = 0;
		for (int i = 0, j = 0, iMax = another.count(), jMax = this.count(); i < iMax; i++) {
			int newerPatchEntryStart = another.starts.get(i);
			int olderPatchEntryEnd;
			
			while (j < jMax) {
				if (starts.get(j) < newerPatchEntryStart) {
					if (starts.get(j)+data.get(j).length <= newerPatchEntryStart) {
						r.add(this, j);
					} else {
						int newLen = newerPatchEntryStart - starts.get(j);
						int newEnd = ends.get(j) <= newerPatchEntryStart ? ends.get(j) : newerPatchEntryStart; 
						r.add(starts.get(j), newEnd, data.get(j), newLen);
						break;
					}
				}
				p1AppliedPos += starts.get(j) - p1PrevEnd;
				p1AppliedPos += data.get(j).length;
				p1PrevEnd = ends.get(j);
				j++;
			}
			r.add(newerPatchEntryStart, another.ends.get(i), another.data.get(i));
			p1AppliedPos += newerPatchEntryStart + p1PrevEnd - another.data.get(i).length;
			// either j == jMax and another(i, i+1, ..., iMax) need to be just copied
			// or new patch entry starts before end of one of original patch entries
			if (olderPatchEntryEnd > (destPosition + newerPatchEntryStart)) {
				destPosition += starts.get(j) - prevEnd; // count those in the original stream up to old patch start
				int newLen = newerPatchEntryStart - destPosition;
			}
		}
	}
*/
}