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
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.DataSerializer.ByteArraySerializer;
import org.tmatesoft.hg.internal.DataSerializer.ByteArrayDataSource;
import org.tmatesoft.hg.internal.DataSerializer.DataSource;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * 
 * TODO [post-1.1] separate operation to check if index is too big and split into index+data
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogStreamWriter {

	private final DigestHelper dh = new DigestHelper();
	private final RevlogCompressor revlogDataZip;
	private final Transaction transaction;
	private int lastEntryBase, lastEntryIndex;
	private byte[] lastEntryContent;
	private Nodeid lastEntryRevision;
	private IntMap<Nodeid> revisionCache = new IntMap<Nodeid>(32);
	private RevlogStream revlogStream;
	
	public RevlogStreamWriter(SessionContext.Source ctxSource, RevlogStream stream, Transaction tr) {
		assert ctxSource != null;
		assert stream != null;
		assert tr != null;
				
		revlogDataZip = new RevlogCompressor(ctxSource.getSessionContext());
		revlogStream = stream;
		transaction = tr;
	}
	
	/**
	 * @return nodeid of added revision
	 * @throws HgRuntimeException 
	 */
	public Nodeid addRevision(DataSource content, int linkRevision, int p1, int p2) throws HgIOException, HgRuntimeException {
		lastEntryRevision = Nodeid.NULL;
		int revCount = revlogStream.revisionCount();
		lastEntryIndex = revCount == 0 ? NO_REVISION : revCount - 1;
		populateLastEntry();
		//
		byte[] contentByteArray = toByteArray(content);
		Patch patch = GeneratePatchInspector.delta(lastEntryContent, contentByteArray);
		int patchSerializedLength = patch.serializedLength();
		
		final boolean writeComplete = preferCompleteOverPatch(patchSerializedLength, contentByteArray.length);
		DataSerializer.DataSource dataSource = writeComplete ? new ByteArrayDataSource(contentByteArray) : patch.new PatchDataSource();
		revlogDataZip.reset(dataSource);
		final int compressedLen;
		final boolean useCompressedData = preferCompressedOverComplete(revlogDataZip.getCompressedLength(), dataSource.serializeLength());
		if (useCompressedData) {
			compressedLen= revlogDataZip.getCompressedLength();
		} else {
			// compression wasn't too effective,
			compressedLen = dataSource.serializeLength() + 1 /*1 byte for 'u' - uncompressed prefix byte*/;
		}
		//
		Nodeid p1Rev = revision(p1);
		Nodeid p2Rev = revision(p2);
		byte[] revisionNodeidBytes = dh.sha1(p1Rev, p2Rev, contentByteArray).asBinary();
		//

		DataSerializer indexFile, dataFile;
		indexFile = dataFile = null;
		try {
			//
			indexFile = revlogStream.getIndexStreamWriter(transaction);
			final boolean isInlineData = revlogStream.isInlineData();
			HeaderWriter revlogHeader = new HeaderWriter(isInlineData);
			revlogHeader.length(contentByteArray.length, compressedLen);
			revlogHeader.nodeid(revisionNodeidBytes);
			revlogHeader.linkRevision(linkRevision);
			revlogHeader.parents(p1, p2);
			revlogHeader.baseRevision(writeComplete ? lastEntryIndex+1 : lastEntryBase);
			long lastEntryOffset = revlogStream.newEntryOffset();
			revlogHeader.offset(lastEntryOffset);
			//
			revlogHeader.serialize(indexFile);
			
			if (isInlineData) {
				dataFile = indexFile;
			} else {
				dataFile = revlogStream.getDataStreamWriter(transaction);
			}
			if (useCompressedData) {
				int actualCompressedLenWritten = revlogDataZip.writeCompressedData(dataFile);
				if (actualCompressedLenWritten != compressedLen) {
					throw new HgInvalidStateException(String.format("Expected %d bytes of compressed data, but actually wrote %d in %s", compressedLen, actualCompressedLenWritten, revlogStream.getDataFileName()));
				}
			} else {
				dataFile.writeByte((byte) 'u');
				dataSource.serialize(dataFile);
			}
			
			
			lastEntryContent = contentByteArray;
			lastEntryBase = revlogHeader.baseRevision();
			lastEntryIndex++;
			lastEntryRevision = Nodeid.fromBinary(revisionNodeidBytes, 0);
			revisionCache.put(lastEntryIndex, lastEntryRevision);

			revlogStream.revisionAdded(lastEntryIndex, lastEntryRevision, lastEntryBase, lastEntryOffset);
		} finally {
			indexFile.done();
			if (dataFile != null && dataFile != indexFile) {
				dataFile.done();
			}
		}
		return lastEntryRevision;
	}
	
	private byte[] toByteArray(DataSource content) throws HgIOException, HgRuntimeException {
		ByteArraySerializer ba = new ByteArraySerializer();
		content.serialize(ba);
		return ba.toByteArray();
	}

	private Nodeid revision(int revisionIndex) throws HgInvalidControlFileException, HgInvalidRevisionException {
		if (revisionIndex == NO_REVISION) {
			return Nodeid.NULL;
		}
		Nodeid n = revisionCache.get(revisionIndex);
		if (n == null) {
			n = Nodeid.fromBinary(revlogStream.nodeid(revisionIndex), 0);
			revisionCache.put(revisionIndex, n);
		}
		return n;
	}
	
	private void populateLastEntry() throws HgRuntimeException {
		if (lastEntryContent != null) {
			return;
		}
		if (lastEntryIndex != NO_REVISION) {
			assert lastEntryIndex >= 0;
			final IOException[] failure = new IOException[1];
			revlogStream.iterate(lastEntryIndex, lastEntryIndex, true, new RevlogStream.Inspector() {
				
				public void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
					try {
						lastEntryBase = baseRevision;
						lastEntryRevision = Nodeid.fromBinary(nodeid, 0);
						lastEntryContent = data.byteArray();
					} catch (IOException ex) {
						failure[0] = ex;
					}
				}
			});
			if (failure[0] != null) {
				String m = String.format("Failed to get content of most recent revision %d", lastEntryIndex);
				throw revlogStream.initWithDataFile(new HgInvalidControlFileException(m, failure[0], null));
			}
		} else {
			lastEntryContent = new byte[0];
		}
	}
	
	public static boolean preferCompleteOverPatch(int patchLength, int fullContentLength) {
		return !decideWorthEffort(patchLength, fullContentLength);
	}
	
	public static boolean preferCompressedOverComplete(int compressedLen, int fullContentLength) {
		if (compressedLen <= 0) { // just in case, meaningless otherwise
			return false;
		}
		return decideWorthEffort(compressedLen, fullContentLength);
	}

	// true if length obtained with effort is worth it 
	private static boolean decideWorthEffort(int lengthWithExtraEffort, int lengthWithoutEffort) {
		return lengthWithExtraEffort < (/* 3/4 of original */lengthWithoutEffort - (lengthWithoutEffort >>> 2));
	}

	/*XXX public because HgCloneCommand uses it*/
	public static class HeaderWriter implements DataSerializer.DataSource {
		private final ByteBuffer header;
		private final boolean isInline;
		private long offset;
		private int length, compressedLength;
		private int baseRev, linkRev, p1, p2;
		private byte[] nodeid;
		
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
			linkRev = linkRevision;
			return this;
		}
		
		public HeaderWriter nodeid(Nodeid n) {
			nodeid = n.toByteArray();
			return this;
		}
		
		public HeaderWriter nodeid(byte[] nodeidBytes) {
			nodeid = nodeidBytes;
			return this;
		}
		
		public void serialize(DataSerializer out) throws HgIOException {
			header.clear();
			if (offset == 0) {
				int version = 1 /* RevlogNG */;
				if (isInline) {
					version |= RevlogStream.INLINEDATA;
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
			header.put(nodeid);
			// assume 12 bytes left are zeros
			out.write(header.array(), 0, header.capacity());

			// regardless whether it's inline or separate data,
			// offset field always represent cumulative compressedLength 
			// (while physical offset in the index file with inline==true differs by n*sizeof(header), where n is entry's position in the file) 
			offset += compressedLength;
		}
		
		public int serializeLength() {
			return header.capacity();
		}
	}
}
