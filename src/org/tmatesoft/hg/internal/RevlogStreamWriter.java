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
import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.core.SessionContext;
import org.tmatesoft.hg.internal.DataSerializer.ByteArrayDataSource;
import org.tmatesoft.hg.internal.DataSerializer.ByteArraySerializer;
import org.tmatesoft.hg.internal.DataSerializer.DataSource;
import org.tmatesoft.hg.repo.HgBundle.GroupElement;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidDataFormatException;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Pair;

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
	// init with illegal values
	private int lastEntryBase = BAD_REVISION, lastEntryIndex = BAD_REVISION, lastEntryActualLen = -1;
	// record revision and its full content
	// the name might be misleading, it does not necessarily match lastEntryIndex
	private Pair<Integer, byte[]> lastFullContent;
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
	
	public RevlogStream getRevlogStream() {
		return revlogStream;
	}
	
	public Pair<Integer,Nodeid> addPatchRevision(GroupElement ge, RevisionToIndexMap clogRevs, RevisionToIndexMap revlogRevs) throws HgIOException, HgRuntimeException {
		populateLastEntryIndex();
		//
		final Nodeid nodeRev = ge.node();
		final Nodeid csetRev = ge.cset();
		int linkRev;
		if (nodeRev.equals(csetRev)) {
			linkRev = lastEntryIndex+1;
		} else {
			linkRev = clogRevs.revisionIndex(csetRev);
		}
		assert linkRev >= 0;
		final Nodeid p1Rev = ge.firstParent();
		int p1 = p1Rev.isNull() ? NO_REVISION : revlogRevs.revisionIndex(p1Rev);
		final Nodeid p2Rev = ge.secondParent();
		int p2 = p2Rev.isNull() ? NO_REVISION : revlogRevs.revisionIndex(p2Rev);
		Patch p = null;
		try {
			p = HgInternals.patchFromData(ge);
		} catch (IOException ex) {
			throw new HgIOException("Failed to read patch information", ex, null);
		}
		//
		final Nodeid patchBase = ge.patchBase();
		int patchBaseRev = patchBase.isNull() ? NO_REVISION : revlogRevs.revisionIndex(patchBase);
		int baseRev = lastEntryIndex == NO_REVISION ? 0 : revlogStream.baseRevision(patchBaseRev);
		int revLen;
		DataSource ds;
		byte[] complete = null;
		if (patchBaseRev == lastEntryIndex && lastEntryIndex != NO_REVISION) {
			// we may write patch from GroupElement as is
			int patchBaseLen = dataLength(patchBaseRev);
			revLen = patchBaseLen + p.patchSizeDelta();
			ds = p.new PatchDataSource();
		} else {
			// read baseRev, unless it's the pull to empty repository
			try {
				if (lastEntryIndex == NO_REVISION) {
					complete = p.apply(new ByteArrayDataAccess(new byte[0]), -1);
					baseRev = 0; // it's done above, but doesn't hurt
				} else {
					assert patchBaseRev != NO_REVISION;
					ReadContentInspector insp = new ReadContentInspector().read(revlogStream, patchBaseRev);
					complete = p.apply(new ByteArrayDataAccess(insp.content), -1);
					baseRev = lastEntryIndex + 1;
				}
				ds = new ByteArrayDataSource(complete);
				revLen = complete.length;
			} catch (IOException ex) {
				// unlikely to happen, as ByteArrayDataSource throws IOException only in case of programming errors
				// hence, throwing rt exception here makes more sense here than HgIOException (even that latter is in throws)
				throw new HgInvalidDataFormatException("Failed to reconstruct revision", ex);
			}
		}
		doAdd(nodeRev, p1, p2, linkRev, baseRev, revLen, ds);
		if (complete != null) {
			lastFullContent = new Pair<Integer, byte[]>(lastEntryIndex, complete);
		}
		return new Pair<Integer, Nodeid>(lastEntryIndex, lastEntryRevision);
	}
	
	/**
	 * @return nodeid of added revision
	 * @throws HgRuntimeException 
	 */
	public Pair<Integer,Nodeid> addRevision(DataSource content, int linkRevision, int p1, int p2) throws HgIOException, HgRuntimeException {
		populateLastEntryIndex();
		populateLastEntryContent();
		//
		byte[] contentByteArray = toByteArray(content);
		Nodeid p1Rev = revision(p1);
		Nodeid p2Rev = revision(p2);
		Nodeid newRev = Nodeid.fromBinary(dh.sha1(p1Rev, p2Rev, contentByteArray).asBinary(), 0);
		if (newRev.equals(p1Rev)) { // shall never happen, same content but different parents give new SHA. Doesn't hurt to check, though 
			assert p2Rev.isNull();
			return new Pair<Integer, Nodeid>(p1, p1Rev);
		}
		//
		Patch patch = GeneratePatchInspector.delta(lastFullContent.second(), contentByteArray);
		int patchSerializedLength = patch.serializedLength();
		final boolean writeComplete = preferCompleteOverPatch(patchSerializedLength, contentByteArray.length);
		DataSerializer.DataSource dataSource = writeComplete ? new ByteArrayDataSource(contentByteArray) : patch.new PatchDataSource();
		//
		doAdd(newRev, p1, p2, linkRevision, writeComplete ? lastEntryIndex+1 : lastEntryBase, contentByteArray.length, dataSource);
		lastFullContent = new Pair<Integer, byte[]>(lastEntryIndex, contentByteArray);
		return new Pair<Integer, Nodeid>(lastEntryIndex, lastEntryRevision);
	}

	private Nodeid doAdd(Nodeid rev, int p1, int p2, int linkRevision, int baseRevision, int revLen, DataSerializer.DataSource dataSource) throws HgIOException, HgRuntimeException  {
		assert linkRevision >= 0;
		assert baseRevision >= 0;
		assert p1 == NO_REVISION || p1 >= 0;
		assert p2 == NO_REVISION || p2 >= 0;
		assert !rev.isNull();
		assert revLen >= 0;
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
		DataSerializer indexFile, dataFile;
		indexFile = dataFile = null;
		try {
			// FIXME perhaps, not a good idea to open stream for each revision added (e.g, when we pull a lot of them)
			indexFile = revlogStream.getIndexStreamWriter(transaction);
			final boolean isInlineData = revlogStream.isInlineData();
			HeaderWriter revlogHeader = new HeaderWriter(isInlineData);
			revlogHeader.length(revLen, compressedLen);
			revlogHeader.nodeid(rev.toByteArray());
			revlogHeader.linkRevision(linkRevision);
			revlogHeader.parents(p1, p2);
			revlogHeader.baseRevision(baseRevision);
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
			
			lastEntryBase = revlogHeader.baseRevision();
			lastEntryIndex++;
			lastEntryActualLen = revLen;
			lastEntryRevision = rev;
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
	
	private int dataLength(int revisionIndex) throws HgInvalidControlFileException, HgInvalidRevisionException {
		assert revisionIndex >= 0;
		if (revisionIndex == lastEntryIndex && lastEntryActualLen >= 0) {
			// if the last entry is the one we've just written, we know its actual len.
			// it's possible, however, that revisionIndex == lastEntryIndex just
			// because revision being added comes right after last locally known one
			// and lastEntryActualLen is not set
			return lastEntryActualLen;
		}
		if (lastFullContent != null && lastFullContent.first() == revisionIndex) {
			return lastFullContent.second().length;
		}
		return revlogStream.dataLength(revisionIndex);
	}
	
	private void populateLastEntryIndex() throws HgRuntimeException {
		int revCount = revlogStream.revisionCount();
		lastEntryIndex = revCount == 0 ? NO_REVISION : revCount - 1;
	}
	
	private void populateLastEntryContent() throws HgIOException, HgRuntimeException {
		if (lastFullContent != null && lastFullContent.first() == lastEntryIndex) {
			// we have last entry cached
			return;
		}
		lastEntryRevision = Nodeid.NULL;
		if (lastEntryIndex != NO_REVISION) {
			ReadContentInspector insp = new ReadContentInspector().read(revlogStream, lastEntryIndex);
			lastEntryBase = insp.baseRev;
			lastEntryRevision = insp.rev;
			lastFullContent = new Pair<Integer, byte[]>(lastEntryIndex, insp.content);
		} else {
			lastFullContent = new Pair<Integer, byte[]>(lastEntryIndex, new byte[0]);
		}
		assert lastFullContent.first() == lastEntryIndex;
		assert lastFullContent.second() != null;
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
	
	// XXX part of HgRevisionMap contract, need public counterparts (along with IndexToRevisionMap)
	public interface RevisionToIndexMap {
		
		/**
		 * @return {@link HgRepository#NO_REVISION} if unknown revision
		 */
		int revisionIndex(Nodeid revision);
	}

	private static class ReadContentInspector implements RevlogStream.Inspector {
		public int baseRev;
		public Nodeid rev;
		public byte[] content;
		private IOException failure;
		
		public ReadContentInspector read(RevlogStream rs, int revIndex) throws HgIOException, HgRuntimeException {
			assert revIndex >= 0;
			rs.iterate(revIndex, revIndex, true, this);
			if (failure != null) {
				String m = String.format("Failed to get content of revision %d", revIndex);
				throw rs.initWithIndexFile(new HgIOException(m, failure, null));
			}
			return this;
		}
		
		public void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) {
			try {
				baseRev = baseRevision;
				rev = Nodeid.fromBinary(nodeid, 0);
				content = data.byteArray();
			} catch (IOException ex) {
				failure = ex;
			}
		}
	}
}