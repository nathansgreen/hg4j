/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.tmate.hgkit.fs.DataAccess;
import com.tmate.hgkit.fs.DataAccessProvider;

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
	private final File indexFile;
	private final DataAccessProvider dataAccess;

	// if we need anything else from HgRepo, might replace DAP parameter with HgRepo and query it for DAP.
	RevlogStream(DataAccessProvider dap, File indexFile) {
		this.dataAccess = dap;
		this.indexFile = indexFile;
	}

	/*package*/ DataAccess getIndexStream() {
		return dataAccess.create(indexFile);
	}

	/*package*/ DataAccess getDataStream() {
		final String indexName = indexFile.getName();
		File dataFile = new File(indexFile.getParentFile(), indexName.substring(0, indexName.length() - 1) + "d");
		return dataAccess.create(dataFile);
	}
	
	public int revisionCount() {
		initOutline();
		return index.size();
	}
	
	public int dataLength(int revision) {
		// XXX in fact, use of iterate() instead of this implementation may be quite reasonable.
		//
		final int indexSize = revisionCount();
		DataAccess daIndex = getIndexStream(); // XXX may supply a hint that I'll need really few bytes of data (although at some offset)
		if (revision == TIP) {
			revision = indexSize - 1;
		}
		try {
			int recordOffset = inline ? (int) index.get(revision).offset : revision * REVLOGV1_RECORD_SIZE;
			daIndex.seek(recordOffset + 12); // 6+2+4
			int actualLen = daIndex.readInt();
			return actualLen; 
		} catch (IOException ex) {
			ex.printStackTrace(); // log error. FIXME better handling
			throw new IllegalStateException(ex);
		} finally {
			daIndex.done();
		}
	}
	
	public int findLocalRevisionNumber(Nodeid nodeid) {
		// XXX this one may be implemented with iterate() once there's mechanism to stop iterations
		final int indexSize = revisionCount();
		DataAccess daIndex = getIndexStream();
		try {
			byte[] nodeidBuf = new byte[20];
			for (int i = 0; i < indexSize; i++) {
				daIndex.skip(8);
				int compressedLen = daIndex.readInt();
				daIndex.skip(20);
				daIndex.readBytes(nodeidBuf, 0, 20);
				if (nodeid.equalsTo(nodeidBuf)) {
					return i;
				}
				daIndex.skip(inline ? 12 + compressedLen : 12);
			}
		} catch (IOException ex) {
			ex.printStackTrace(); // log error. FIXME better handling
			throw new IllegalStateException(ex);
		} finally {
			daIndex.done();
		}
		throw new IllegalArgumentException(String.format("%s doesn't represent a revision of %s", nodeid.toString(), indexFile.getName() /*XXX HgDataFile.getPath might be more suitable here*/));
	}


	private final int REVLOGV1_RECORD_SIZE = 64;

	// should be possible to use TIP, ALL, or -1, -2, -n notation of Hg
	// ? boolean needsNodeid
	public void iterate(int start, int end, boolean needData, Revlog.Inspector inspector) {
		initOutline();
		final int indexSize = index.size();
		if (indexSize == 0) {
			return;
		}
		if (end == TIP) {
			end = indexSize - 1;
		}
		if (start == TIP) {
			start = indexSize - 1;
		}
		if (start < 0 || start >= indexSize) {
			throw new IllegalArgumentException("Bad left range boundary " + start);
		}
		if (end < start || end >= indexSize) {
			throw new IllegalArgumentException("Bad right range boundary " + end);
		}
		// XXX may cache [start .. end] from index with a single read (pre-read)
		
		DataAccess daIndex = null, daData = null;
		daIndex = getIndexStream();
		if (needData && !inline) {
			daData = getDataStream();
		}
		try {
			byte[] nodeidBuf = new byte[20];
			byte[] lastData = null;
			int i;
			boolean extraReadsToBaseRev = false;
			if (needData && index.get(start).baseRevision < start) {
				i = index.get(start).baseRevision;
				extraReadsToBaseRev = true;
			} else {
				i = start;
			}
			
			daIndex.seek(inline ? (int) index.get(i).offset : i * REVLOGV1_RECORD_SIZE);
			for (; i <= end; i++ ) {
				long l = daIndex.readLong();
				long offset = l >>> 16;
				int flags = (int) (l & 0X0FFFF);
				int compressedLen = daIndex.readInt();
				int actualLen = daIndex.readInt();
				int baseRevision = daIndex.readInt();
				int linkRevision = daIndex.readInt();
				int parent1Revision = daIndex.readInt();
				int parent2Revision = daIndex.readInt();
				// Hg has 32 bytes here, uses 20 for nodeid, and keeps 12 last bytes empty
				daIndex.readBytes(nodeidBuf, 0, 20);
				daIndex.skip(12);
				byte[] data = null;
				if (needData) {
					byte[] dataBuf = new byte[compressedLen];
					if (inline) {
						daIndex.readBytes(dataBuf, 0, compressedLen);
					} else {
						daData.seek(index.get(i).offset);
						daData.readBytes(dataBuf, 0, compressedLen);
					}
					if (dataBuf[0] == 0x78 /* 'x' */) {
						try {
							Inflater zlib = new Inflater(); // XXX Consider reuse of Inflater, and/or stream alternative
							zlib.setInput(dataBuf, 0, compressedLen);
							byte[] result = new byte[actualLen*2]; // FIXME need to use zlib.finished() instead 
							int resultLen = zlib.inflate(result);
							zlib.end();
							data = new byte[resultLen];
							System.arraycopy(result, 0, data, 0, resultLen);
						} catch (DataFormatException ex) {
							ex.printStackTrace();
							data = new byte[0]; // FIXME need better failure strategy
						}
					} else if (dataBuf[0] == 0x75 /* 'u' */) {
						data = new byte[dataBuf.length - 1];
						System.arraycopy(dataBuf, 1, data, 0, data.length);
					} else {
						// XXX Python impl in fact throws exception when there's not 'x', 'u' or '0'
						// but I don't see reason not to return data as is 
						data = dataBuf;
					}
					// XXX 
					if (baseRevision != i) { // XXX not sure if this is the right way to detect a patch
						// this is a patch
						LinkedList<PatchRecord> patches = new LinkedList<PatchRecord>();
						int patchElementIndex = 0;
						do {
							final int x = patchElementIndex; // shorthand
							int p1 =  ((data[x] & 0xFF)<< 24)    | ((data[x+1] & 0xFF) << 16) | ((data[x+2] & 0xFF) << 8)  | (data[x+3] & 0xFF);
							int p2 =  ((data[x+4] & 0xFF) << 24) | ((data[x+5] & 0xFF) << 16) | ((data[x+6] & 0xFF) << 8)  | (data[x+7] & 0xFF);
							int len = ((data[x+8] & 0xFF) << 24) | ((data[x+9] & 0xFF) << 16) | ((data[x+10] & 0xFF) << 8) | (data[x+11] & 0xFF);
							patchElementIndex += 12 + len;
							patches.add(new PatchRecord(p1, p2, len, data, x+12));
						} while (patchElementIndex < data.length);
						//
						byte[] baseRevContent = lastData;
						data = apply(baseRevContent, actualLen, patches);
					}
				} else {
					if (inline) {
						daIndex.skip(compressedLen);
					}
				}
				if (!extraReadsToBaseRev || i >= start) {
					inspector.next(i, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, nodeidBuf, data);
				}
				lastData = data;
			}
		} catch (IOException ex) {
			throw new IllegalStateException(ex); // FIXME need better handling
		} finally {
			daIndex.done();
			if (daData != null) {
				daData.done();
			}
		}
	}
	
	private void initOutline() {
		if (index != null && !index.isEmpty()) {
			return;
		}
		ArrayList<IndexEntry> res = new ArrayList<IndexEntry>();
		DataAccess da = getIndexStream();
		try {
			int versionField = da.readInt();
			da.readInt(); // just to skip next 2 bytes of offset + flags
			final int INLINEDATA = 1 << 16;
			inline = (versionField & INLINEDATA) != 0;
			long offset = 0; // first offset is always 0, thus Hg uses it for other purposes
			while(true) {
				int compressedLen = da.readInt();
				// 8+4 = 12 bytes total read here
				int actualLen = da.readInt();
				int baseRevision = da.readInt();
				// 12 + 8 = 20 bytes read here
//				int linkRevision = di.readInt();
//				int parent1Revision = di.readInt();
//				int parent2Revision = di.readInt();
//				byte[] nodeid = new byte[32];
				if (inline) {
					res.add(new IndexEntry(offset + REVLOGV1_RECORD_SIZE * res.size(), baseRevision));
					da.skip(3*4 + 32 + compressedLen); // Check: 44 (skip) + 20 (read) = 64 (total RevlogNG record size)
				} else {
					res.add(new IndexEntry(offset, baseRevision));
					da.skip(3*4 + 32);
				}
				if (da.isEmpty()) {
					// fine, done then
					res.trimToSize();
					index = res;
					break;
				} else {
					// start reading next record
					long l = da.readLong();
					offset = l >>> 16;
				}
			}
		} catch (IOException ex) {
			ex.printStackTrace(); // log error
			// too bad, no outline then.
			index = Collections.emptyList();
		} finally {
			da.done();
		}
		
	}
	

	// perhaps, package-local or protected, if anyone else from low-level needs them
	// XXX think over if we should keep offset in case of separate data file - we read the field anyway. Perhaps, distinct entry classes for Inline and non-inline indexes?
	private static class IndexEntry {
		public final long offset; // for separate .i and .d - copy of index record entry, for inline index - actual offset of the record in the .i file (record entry + revision * record size))
		//public final int length; // data past fixed record (need to decide whether including header size or not), and whether length is of compressed data or not
		public final int baseRevision;

		public IndexEntry(long o, int baseRev) {
			offset = o;
			baseRevision = baseRev;
		}
	}

	// mpatch.c : apply()
	// FIXME need to implement patch merge (fold, combine, gather and discard from aforementioned mpatch.[c|py]), also see Revlog and Mercurial PDF
	private static byte[] apply(byte[] baseRevisionContent, int outcomeLen, List<PatchRecord> patch) {
		byte[] tempBuf = new byte[outcomeLen]; // XXX
		int last = 0, destIndex = 0;
		for (PatchRecord pr : patch) {
			System.arraycopy(baseRevisionContent, last, tempBuf, destIndex, pr.start-last);
			destIndex += pr.start - last;
			System.arraycopy(pr.data, 0, tempBuf, destIndex, pr.data.length);
			destIndex += pr.data.length;
			last = pr.end;
		}
		System.arraycopy(baseRevisionContent, last, tempBuf, destIndex, baseRevisionContent.length - last);
		destIndex += baseRevisionContent.length - last; // total length
		byte[] rv = new byte[destIndex];
		System.arraycopy(tempBuf, 0, rv, 0, destIndex);
		return rv;
	}

	static class PatchRecord { // copy of struct frag from mpatch.c
		int start, end, len;
		byte[] data;

		public PatchRecord(int p1, int p2, int len, byte[] src, int srcOffset) {
		start = p1;
				end = p2;
				this.len = len;
				data = new byte[len];
				System.arraycopy(src, srcOffset, data, 0, len);
		}
	}
}
