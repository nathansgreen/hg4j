/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.BufferedInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.zip.DataFormatException;
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
	private final File indexFile;

	RevlogStream(File indexFile) {
		this.indexFile = indexFile;
	}

	private void detectVersion() {
		
	}

	/*package*/ DataInput getIndexStream() {
		DataInputStream dis = null;
		try {
			dis = new DataInputStream(new BufferedInputStream(new FileInputStream(indexFile)));
		} catch (FileNotFoundException ex) {
			ex.printStackTrace();
			// should not happen, we checked for existence
		}
		return dis;
	}

	/*package*/ DataInput getDataStream() {
		final String indexName = indexFile.getName();
		File dataFile = new File(indexFile.getParentFile(), indexName.substring(0, indexName.length() - 1) + "d");
		try {
			return new DataInputStream(new BufferedInputStream(new FileInputStream(dataFile)));
		} catch (FileNotFoundException ex) {
			ex.printStackTrace();
			return null;
		}
	}

	public int revisionCount() {
		initOutline();
		return index.size();
	}

	// should be possible to use TIP, ALL, or -1, -2, -n notation of Hg
	// ? boolean needsNodeid
	public void iterate(int start, int end, boolean needData, Revlog.Inspector inspector) {
		initOutline();
		final int indexSize = index.size();
		if (indexSize == 0) {
			return;
		}
		if (end == -1 /*FIXME TIP*/) {
			end = indexSize - 1;
		}
		if (start < 0 || start >= indexSize) {
			throw new IllegalArgumentException("Bad left range boundary " + start);
		}
		if (end < start || end >= indexSize) {
			throw new IllegalArgumentException("Bad right range boundary " + end);
		}
		// XXX may cache [start .. end] from index with a single read (pre-read)
		
		DataInput diIndex = null, diData = null;
		diIndex = getIndexStream();
		if (needData && !inline) {
			diData = getDataStream();
		}
		try {
			int skipped = diIndex.skipBytes(inline ? (int) index.get(start).offset : start * 64);
			byte[] lastData = null;
			for (int i = start; i <= end; i++ ) {
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
						diData.skipBytes((int) ie.offset); // FIXME not skip but seek!!! (skip would work only for the first time)
						diData.readFully(dataBuf);
					}
					if (dataBuf[0] == 0x78 /* 'x' */) {
						try {
							Inflater zlib = new Inflater();
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
							int p1 = (data[x] << 24) | (data[x+1] << 16) | (data[x+2] << 8) | data[x+3];
							int p2 = (data[x+4] << 24) | (data[x+5] << 16) | (data[x+6] << 8) | data[x+7];
							int len = (data[x+8] << 24) | (data[x+9] << 16) | (data[x+10] << 8) | data[x+11];
							patchElementIndex += 12 + len;
							patches.add(new PatchRecord(p1, p2, len, data, x+12));
						} while (patchElementIndex < data.length);
						//
						byte[] baseRevContent;
						if (baseRevision == i - 1) {
							baseRevContent = lastData;
						} else {
							// FIXME implement delta collection from few revisions
							// read baseRevision plus all deltas between this revision and base. Need to do this effectively.
							throw HgRepository.notImplemented();
						}
						
						// FIXME need to collect all patches between baseRevision and current version 
						data = apply(baseRevContent, patches);
					}
				} else {
					if (inline) {
						diIndex.skipBytes(compressedLen);
					}
				}
				inspector.next(i, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, buf, data);
				lastData = data;
			}
		} catch (EOFException ex) {
			// should not happen as long as we read inside known boundaries
			throw new IllegalStateException(ex);
		} catch (IOException ex) {
			throw new IllegalStateException(ex); // FIXME need better handling
		} finally {
			hackCloseFileStreams(diIndex, diData); // FIXME HACK!!!
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
			di.readInt(); // just to skip next 2 bytes of offset + flags
			final int INLINEDATA = 1 << 16;
			inline = (versionField & INLINEDATA) != 0;
			long offset = 0; // first offset is always 0, thus Hg uses it for other purposes
			while(true) { // EOFExcepiton should get us outta here. FIXME Our inputstream should has explicit no-more-data indicator
				int compressedLen = di.readInt();
				// 8+4 = 12 bytes total read
//				int actualLen = di.readInt();
//				int baseRevision = di.readInt();
//				int linkRevision = di.readInt();
//				int parent1Revision = di.readInt();
//				int parent2Revision = di.readInt();
//				byte[] nodeid = new byte[32];
				if (inline) {
					res.add(new IndexEntry(offset + 64*res.size(), compressedLen));
					di.skipBytes(5*4 + 32 + compressedLen); // Check: 52 (skip) + 12 (read) = 64 (total RevlogNG record size)
				} else {
					res.add(new IndexEntry(offset, compressedLen));
					di.skipBytes(5*4 + 32);
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
		hackCloseFileStreams(di, null); // FIXME HACK!!!
	}
	
	// FIXME HACK to deal with File/FileStream nature of out data source. Won't need this once implement
	// own DataInput based on bytearray chunks or RandomAccessFile
	private void hackCloseFileStreams(DataInput index, DataInput data) {
		try {
			if (index != null) {
				((DataInputStream) index).close();
			}
			if (data != null) {
				((DataInputStream) data).close();
			}
		} catch (IOException ex) {
			ex.printStackTrace();
		}
	}


	// perhaps, package-local or protected, if anyone else from low-level needs them
	private static class IndexEntry {
		public final long offset; // for separate .i and .d - copy of index record entry, for inline index - actual offset of the record in the .i file (record entry + revision * record size))
		public final int length; // data past fixed record (need to decide whether including header size or not), and whether length is of compressed data or not

		public IndexEntry(long o, int l) {
			offset = o;
			length = l;
		}
	}

	// mpatch.c : apply()
	// FIXME need to implement patch merge (fold, combine, gather and discard from aforementioned mpatch.[c|py]), also see Revlog and Mercurial PDF
	private static byte[] apply(byte[] baseRevisionContent, List<PatchRecord> patch) {
		byte[] tempBuf = new byte[512]; // XXX
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
