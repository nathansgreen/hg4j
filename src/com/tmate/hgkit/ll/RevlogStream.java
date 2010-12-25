/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import static com.tmate.hgkit.ll.HgRepository.TIP;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
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

	/*package*/ DataAccess getIndexStream() {
		return create(indexFile);
	}

	/*package*/ DataAccess getDataStream() {
		final String indexName = indexFile.getName();
		File dataFile = new File(indexFile.getParentFile(), indexName.substring(0, indexName.length() - 1) + "d");
		return create(dataFile);
	}
	
	private DataAccess create(File f) {
		if (!f.exists()) {
			return new DataAccess();
		}
		try {
			FileChannel fc = new FileInputStream(f).getChannel();
			final int MAPIO_MAGIC_BOUNDARY = 100 * 1024;
			if (fc.size() > MAPIO_MAGIC_BOUNDARY) {
				return new MemoryMapFileAccess(fc, fc.size());
			} else {
				return new FileAccess(fc, fc.size());
			}
		} catch (IOException ex) {
			// unlikely to happen, we've made sure file exists.
			ex.printStackTrace(); // FIXME log error
		}
		return new DataAccess(); // non-null, empty.
	}

	public int revisionCount() {
		initOutline();
		return index.size();
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
			byte[] lastData = null;
			int i;
			boolean extraReadsToBaseRev = false;
			if (needData && index.get(start).baseRevision < start) {
				i = index.get(start).baseRevision;
				extraReadsToBaseRev = true;
			} else {
				i = start;
			}
			
			daIndex.seek(inline ? (int) index.get(i).offset : start * REVLOGV1_RECORD_SIZE);
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
				byte[] buf = new byte[32];
				// XXX Hg keeps 12 last bytes empty, we move them into front here
				daIndex.readBytes(buf, 12, 20);
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
					inspector.next(i, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, buf, data);
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
				if (da.nonEmpty()) {
					long l = da.readLong();
					offset = l >>> 16;
				} else {
					// fine, done then
					index = res;
					break;
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

	/*package-local*/ class DataAccess {
		public boolean nonEmpty() {
			return false;
		}
		// absolute positioning
		public void seek(long offset) throws IOException {
			throw new UnsupportedOperationException();
		}
		// relative positioning
		public void skip(int bytes) throws IOException {
			throw new UnsupportedOperationException();
		}
		// shall be called once this object no longer needed
		public void done() {
			// no-op in this empty implementation
		}
		public int readInt() throws IOException {
			byte[] b = new byte[4];
			readBytes(b, 0, 4);
			return b[0] << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
		}
		public long readLong() throws IOException {
			byte[] b = new byte[8];
			readBytes(b, 0, 8);
			int i1 = b[0] << 24 | (b[1] & 0xFF) << 16 | (b[2] & 0xFF) << 8 | (b[3] & 0xFF);
			int i2 = b[4] << 24 | (b[5] & 0xFF) << 16 | (b[6] & 0xFF) << 8 | (b[7] & 0xFF);
			return ((long) i1) << 32 | ((long) i2 & 0xFFFFFFFF);
		}
		public void readBytes(byte[] buf, int offset, int length) throws IOException {
			throw new UnsupportedOperationException();
		}
	}

	// DOESN'T WORK YET 
	private class MemoryMapFileAccess extends DataAccess {
		private FileChannel fileChannel;
		private final long size;
		private long position = 0;

		public MemoryMapFileAccess(FileChannel fc, long channelSize) {
			fileChannel = fc;
			size = channelSize;
		}

		@Override
		public void seek(long offset) {
			position = offset;
		}

		@Override
		public void skip(int bytes) throws IOException {
			position += bytes;
		}

		private boolean fill() throws IOException {
			final int BUFFER_SIZE = 8 * 1024;
			long left = size - position;
			MappedByteBuffer rv = fileChannel.map(FileChannel.MapMode.READ_ONLY, position, left < BUFFER_SIZE ? left : BUFFER_SIZE);
			position += rv.capacity();
			return rv.hasRemaining();
		}

		@Override
		public void done() {
			if (fileChannel != null) {
				try {
					fileChannel.close();
				} catch (IOException ex) {
					ex.printStackTrace(); // log debug
				}
				fileChannel = null;
			}
		}
	}

	private class FileAccess extends DataAccess {
		private FileChannel fileChannel;
		private final long size;
		private ByteBuffer buffer;
		private long bufferStartInFile = 0; // offset of this.buffer in the file.

		public FileAccess(FileChannel fc, long channelSize) {
			fileChannel = fc;
			size = channelSize;
			final int BUFFER_SIZE = 8 * 1024;
			// XXX once implementation is more or less stable,
			// may want to try ByteBuffer.allocateDirect() to see
			// if there's any performance gain.
			buffer = ByteBuffer.allocate(size < BUFFER_SIZE ? (int) size : BUFFER_SIZE);
			buffer.flip(); // or .limit(0) to indicate it's empty
		}
		
		@Override
		public boolean nonEmpty() {
			return bufferStartInFile + buffer.position() < size;
		}
		
		@Override
		public void seek(long offset) throws IOException {
			if (offset < bufferStartInFile + buffer.limit() && offset >= bufferStartInFile) {
				buffer.position((int) (offset - bufferStartInFile));
			} else {
				// out of current buffer, invalidate it (force re-read) 
				// XXX or ever re-read it right away?
				bufferStartInFile = offset;
				buffer.clear();
				buffer.limit(0); // or .flip() to indicate we switch to reading
				fileChannel.position(offset);
			}
		}

		@Override
		public void skip(int bytes) throws IOException {
			final int newPos = buffer.position() + bytes;
			if (newPos >= 0 && newPos < buffer.limit()) {
				// no need to move file pointer, just rewind/seek buffer 
				buffer.position(newPos);
			} else {
				//
				seek(fileChannel.position()+ bytes);
			}
		}

		private boolean fill() throws IOException {
			if (!buffer.hasRemaining()) {
				bufferStartInFile += buffer.limit();
				buffer.clear();
				if (bufferStartInFile < size) { // just in case there'd be any exception on EOF, not -1 
					fileChannel.read(buffer);
					// may return -1 when EOF, but empty will reflect this, hence no explicit support here   
				}
				buffer.flip();
			}
			return buffer.hasRemaining();
		}

		@Override
		public void readBytes(byte[] buf, int offset, int length) throws IOException {
			final int tail = buffer.remaining();
			if (tail >= length) {
				buffer.get(buf, offset, length);
			} else {
				buffer.get(buf, offset, tail);
				if (fill()) {
					buffer.get(buf, offset + tail, length - tail);
				} else {
					throw new IOException(); // shall not happen provided stream contains expected data and no attempts to read past nonEmpty() == false are made. 
				}
			}
		}

		@Override
		public void done() {
			if (buffer != null) {
				buffer = null;
			}
			if (fileChannel != null) {
				try {
					fileChannel.close();
				} catch (IOException ex) {
					ex.printStackTrace(); // log debug
				}
				fileChannel = null;
			}
		}
	}
}
