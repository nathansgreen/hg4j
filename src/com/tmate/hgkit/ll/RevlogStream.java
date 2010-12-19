/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.DataInput;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.Inflater;

/**
 * ? Single RevlogStream per file per repository with accessor to record access session (e.g. with back/forward operations), 
 * or numerous RevlogStream with separate representation of the underlaying data (cached, lazy ChunkStream)?
 * @author artem
 * @see http://mercurial.selenic.com/wiki/Revlog
 * @see http://mercurial.selenic.com/wiki/RevlogNG
 */
public class RevlogStream {

	private List<IndexEntry> index; // indexed access highly needed
	private boolean inline = false;

	private void detectVersion() {
		
	}

	/*package*/ DataInput getIndexStream() {
		// TODO Auto-generated method stub
		return null;
	}

	/*package*/ DataInput getDataStream() {
		// TODO Auto-generated method stub
		return null;
	}
	public int revisionCount() {
		initOutline();
		return index.size();
	}

	// should be possible to use TIP, ALL
	public void iterate(int start, int end, boolean needData, Revlog.Inspector inspector) {
		initOutline();
		final int indexSize = index.size();
		if (start < 0 || start >= indexSize) {
			throw new IllegalArgumentException("Bad left range boundary " + start);
		}
		if (end < start || end >= indexSize) {
			throw new IllegalArgumentException("Bad right range boundary " + end);
		}
		// XXX may cache [start .. end] from index with a single read (pre-read)
		
		DataInput diIndex = null, diData = null;
		diIndex = getIndexStream();
		if (needData) {
			diData = getDataStream();
		}
		try {
			diIndex.skipBytes(inline ? (int) index.get(start).offset : start * 64);
			for (int i = start; i <= end && i < indexSize; i++ ) {
				IndexEntry ie = index.get(i);
				long l = diIndex.readLong();
				long offset = l >>> 16;
				int flags = (int) (l & 0X0FFFF);
				int compressedLen = diIndex.readInt();
				int actualLen = diIndex.readInt();
				int baseRevision = diIndex.readInt();
				int linkRevision = diIndex.readInt();
				int parent1Revision = diIndex.readInt();
				int parent2Revision = diIndex.readInt();
				byte[] buf = new byte[32];
				// XXX Hg keeps 12 last bytes empty, we move them into front here
				diIndex.readFully(buf, 12, 20);
				diIndex.skipBytes(12);
				byte[] data = null;
				if (needData) {
					byte[] dataBuf = new byte[compressedLen];
					if (inline) {
						diIndex.readFully(dataBuf);
					} else {
						diData.skipBytes((int) ie.offset); // FIXME not skip but seek!!!
						diData.readFully(dataBuf);
					}
					if (dataBuf[0] == 0x78 /* 'x' */) {
						Inflater zlib = new Inflater();
						zlib.setInput(dataBuf, 0, compressedLen);
						byte[] result = new byte[actualLen*2]; // FIXME need to use zlib.finished() instead 
						int resultLen = zlib.inflate(result);
						zlib.end();
						data = new byte[resultLen];
						System.arraycopy(result, 0, data, 0, resultLen);
					} else if (dataBuf[0] == 0x75 /* 'u' */) {
						data = new byte[dataBuf.length - 1];
						System.arraycopy(dataBuf, 1, data, 0, data.length);
					} else {
						// XXX Python impl in fact throws exception when there's not 'x', 'u' or '0'
						// but I don't see reason not to just return data as is 
						data = dataBuf;
					}
					// FIXME if patch data (based on baseRevision), then apply patches to get true content
				}
				inspector.next(compressedLen, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, buf, data);
			}
		} catch (EOFException ex) {
			// should not happen as long as we read inside known boundaries
			throw new IllegalStateException(ex);
		} catch (IOException ex) {
			FIXME 
		}
	}
	
	private void initOutline() {
		if (index != null && !index.isEmpty()) {
			return;
		}
		ArrayList<IndexEntry> res = new ArrayList<IndexEntry>();
		DataInput di = getIndexStream();
		try {
			int versionField = di.readInt();
			// di.undreadInt();
			final int INLINEDATA = 1 << 16;
			inline = (versionField & INLINEDATA) != 0;
			long offset = 0; // first offset is always 0, thus Hg uses it for other purposes
			while(true) { // EOFExcepiton should get us outta here. FIXME Out inputstream should has explicit no-more-data indicator
				int compressedLen = di.readInt();
				// 8+4 = 12 bytes total read
//				int actualLen = di.readInt();
//				int baseRevision = di.readInt();
//				int linkRevision = di.readInt();
//				int parent1Revision = di.readInt();
//				int parent2Revision = di.readInt();
//				byte[] nodeid = new byte[32];
				res.add(new IndexEntry(offset, compressedLen));
				if (inline) {
					di.skipBytes(6*4 + 32 + compressedLen); // Check: 56 (skip) + 12 (read) = 64 (total RevlogNG record size)
				} else {
					di.skipBytes(6*4 + 32);
				}
				long l = di.readLong();
				offset = l >>> 16;
			}
		} catch (EOFException ex) {
			// fine, done then
			index = res;
		} catch (IOException ex) {
			ex.printStackTrace();
			// too bad, no outline then
			index = Collections.emptyList();
		}
	}


	// perhaps, package-local or protected, if anyone else from low-level needs them
	private static class IndexEntry {
		public final long offset;
		public final int length; // data past fixed record (need to decide whether including header size or not), and whether length is of compressed data or not

		public IndexEntry(long o, int l) {
			offset = o;
			length = l;
		}
	}
}
