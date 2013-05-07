/*
 * Copyright (c) 2010-2013 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepository.BAD_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.internal.Internals.REVLOGV1_RECORD_SIZE;

import java.io.File;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.zip.Inflater;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgInvalidRevisionException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Adaptable;


/**
 * ? Single RevlogStream per file per repository with accessor to record access session (e.g. with back/forward operations), 
 * or numerous RevlogStream with separate representation of the underlying data (cached, lazy ChunkStream)?
 * 
 * @see http://mercurial.selenic.com/wiki/Revlog
 * @see http://mercurial.selenic.com/wiki/RevlogNG
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class RevlogStream {

	/*
	 * makes sense for index with inline data only - actual offset of the record in the .i file (record entry + revision * record size))
	 * 
	 * long[] in fact (there are 8-bytes field in the revlog)
	 * However, (a) DataAccess currently doesn't operate with long seek/length
	 * and, of greater significance, (b) files with inlined data are designated for smaller files,  
	 * guess, about 130 Kb, and offset there won't ever break int capacity
	 */
	private int[] indexRecordOffset;  
	private int[] baseRevisions;
	private boolean inline = false;
	private final File indexFile;
	private File dataFile;
	private final DataAccessProvider dataAccess;
	// keeps last complete revision we've read. Note, this cached revision doesn't help
	// for subsequent #iterate() calls with the same revision (Inspector needs more data than 
	// we currently cache here, perhaps, we shall cache everything it wants to cover same 
	// revision case as well). Now this helps when second #iterate() call is for a revision greater
	// than one from the first call, and both revisions got same base rev. It's often the case when
	// parents/children are analyzed.
	private SoftReference<CachedRevision> lastRevisionRead;
	private final ReferenceQueue<CachedRevision> lastRevisionQueue = new ReferenceQueue<CachedRevision>();

	// if we need anything else from HgRepo, might replace DAP parameter with HgRepo and query it for DAP.
	public RevlogStream(DataAccessProvider dap, File indexFile) {
		this.dataAccess = dap;
		this.indexFile = indexFile;
	}

	/**
	 * @param shortRead pass <code>true</code> to indicate intention to read few revisions only (as opposed to reading most of/complete revlog)
	 * @return never <code>null</code>, empty {@link DataAccess} if no stream is available
	 */
	/*package*/ DataAccess getIndexStream(boolean shortRead) {
		// shortRead hint helps  to avoid mmap files when only 
		// few bytes are to be read (i.e. #dataLength())
		return dataAccess.createReader(indexFile, shortRead);
	}

	/*package*/ DataAccess getDataStream() {
		return dataAccess.createReader(getDataFile(), false);
	}
	
	/*package*/ DataSerializer getIndexStreamWriter() {
		return dataAccess.createWriter(indexFile, true);
	}
	
	/*package*/ DataSerializer getDataStreamWriter() {
		return dataAccess.createWriter(getDataFile(), true);
	}
	
	/**
	 * Constructs file object that corresponds to .d revlog counterpart. 
	 * Note, it's caller responsibility to ensure this file makes any sense (i.e. check {@link #inline} attribute)
	 */
	private File getDataFile() {
		if (dataFile == null) {
			final String indexName = indexFile.getName();
			dataFile = new File(indexFile.getParentFile(), indexName.substring(0, indexName.length() - 1) + "d");
		}
		return dataFile;
	}
	
	// initialize exception with the file where revlog structure information comes from
	public HgInvalidControlFileException initWithIndexFile(HgInvalidControlFileException ex) {
		return ex.setFile(indexFile);
	}

	// initialize exception with the file where revlog data comes from
	public HgInvalidControlFileException initWithDataFile(HgInvalidControlFileException ex) {
		// exceptions are usually raised after read attepmt, hence inline shall be initialized
		// although honest approach is to call #initOutline() first
		return ex.setFile(inline ? indexFile : getDataFile());
	}
	
	/*package-private*/String getDataFileName() {
		// XXX a temporary solution to provide more info to fill in exceptions other than 
		// HgInvalidControlFileException (those benefit from initWith* methods above)
		//
		// Besides, since RevlogStream represents both revlogs with user data (those with WC representative and 
		// system data under store/data) and system-only revlogs (like changelog and manifest), there's no
		// easy way to supply human-friendly name of the active file (independent from whether it's index of data)
		return inline ? indexFile.getPath() : getDataFile().getPath();
	}

	public boolean isInlineData() {
		initOutline();
		return inline;
	}
	
	public int revisionCount() {
		initOutline();
		return baseRevisions.length;
	}
	
	/**
	 * @throws HgInvalidControlFileException if attempt to read index file failed
	 * @throws HgInvalidRevisionException if revisionIndex argument doesn't represent a valid record in the revlog
	 */
	public int dataLength(int revisionIndex) throws HgInvalidControlFileException, HgInvalidRevisionException {
		// XXX in fact, use of iterate() instead of this implementation may be quite reasonable.
		//
		revisionIndex = checkRevisionIndex(revisionIndex);
		DataAccess daIndex = getIndexStream(true);
		try {
			int recordOffset = getIndexOffsetInt(revisionIndex);
			daIndex.seek(recordOffset + 12); // 6+2+4
			int actualLen = daIndex.readInt();
			return actualLen; 
		} catch (IOException ex) {
			throw new HgInvalidControlFileException(null, ex, indexFile).setRevisionIndex(revisionIndex);
		} finally {
			daIndex.done();
		}
	}
	
	/**
	 * Read nodeid at given index
	 * 
	 * @throws HgInvalidControlFileException if attempt to read index file failed
	 * @throws HgInvalidRevisionException if revisionIndex argument doesn't represent a valid record in the revlog
	 */
	public byte[] nodeid(int revisionIndex) throws HgInvalidControlFileException, HgInvalidRevisionException {
		revisionIndex = checkRevisionIndex(revisionIndex);
		DataAccess daIndex = getIndexStream(true);
		try {
			int recordOffset = getIndexOffsetInt(revisionIndex);
			daIndex.seek(recordOffset + 32);
			byte[] rv = new byte[20];
			daIndex.readBytes(rv, 0, 20);
			return rv;
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Revision lookup failed", ex, indexFile).setRevisionIndex(revisionIndex);
		} finally {
			daIndex.done();
		}
	}

	/**
	 * Get link field from the index record.
	 * 
	 * @throws HgInvalidControlFileException if attempt to read index file failed
	 * @throws HgInvalidRevisionException if revisionIndex argument doesn't represent a valid record in the revlog
	 */
	public int linkRevision(int revisionIndex) throws HgInvalidControlFileException, HgInvalidRevisionException {
		revisionIndex = checkRevisionIndex(revisionIndex);
		DataAccess daIndex = getIndexStream(true);
		try {
			int recordOffset = getIndexOffsetInt(revisionIndex);
			daIndex.seek(recordOffset + 20);
			int linkRev = daIndex.readInt();
			return linkRev;
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Linked revision lookup failed", ex, indexFile).setRevisionIndex(revisionIndex);
		} finally {
			daIndex.done();
		}
	}
	
	/**
	 * Extract base revision field from the revlog
	 * 
	 * @throws HgInvalidControlFileException if attempt to read index file failed
	 * @throws HgInvalidRevisionException if revisionIndex argument doesn't represent a valid record in the revlog
	 */
	public int baseRevision(int revisionIndex) throws HgInvalidControlFileException, HgInvalidRevisionException {
		initOutline();
		revisionIndex = checkRevisionIndex(revisionIndex);
		return getBaseRevision(revisionIndex);
	}
	
	// Perhaps, RevlogStream should be limited to use of plain int revisions for access,
	// while Nodeids should be kept on the level up, in Revlog. Guess, Revlog better keep
	// map of nodeids, and once this comes true, we may get rid of this method.
	// Unlike its counterpart, {@link Revlog#getLocalRevisionNumber()}, doesn't fail with exception if node not found,
	/**
	 * @return integer in [0..revisionCount()) or {@link HgRepository#BAD_REVISION} if not found
	 * @throws HgInvalidControlFileException if attempt to read index file failed
	 */
	public int findRevisionIndex(Nodeid nodeid) throws HgInvalidControlFileException {
		// XXX this one may be implemented with iterate() once there's mechanism to stop iterations
		final int indexSize = revisionCount();
		DataAccess daIndex = getIndexStream(false);
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
			throw new HgInvalidControlFileException("Revision lookup failed", ex, indexFile).setRevision(nodeid);
		} finally {
			daIndex.done();
		}
		return BAD_REVISION;
	}
	
	/**
	 * @return value suitable for the corresponding field in the new revision's header, not physical offset in the file 
	 * (which is different in case of inline revlogs)
	 */
	public long newEntryOffset() {
		if (revisionCount() == 0) {
			return 0;
		}
		DataAccess daIndex = getIndexStream(true);
		int lastRev = revisionCount() - 1;
		try {
			int recordOffset = getIndexOffsetInt(lastRev);
			daIndex.seek(recordOffset);
			long value = daIndex.readLong();
			value = value >>> 16;
			int compressedLen = daIndex.readInt();
			return lastRev == 0 ? compressedLen : value + compressedLen;
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Linked revision lookup failed", ex, indexFile).setRevisionIndex(lastRev);
		} finally {
			daIndex.done();
		}
	}

	// should be possible to use TIP, ALL, or -1, -2, -n notation of Hg
	// ? boolean needsNodeid
	public void iterate(int start, int end, boolean needData, Inspector inspector) throws HgInvalidRevisionException, HgInvalidControlFileException {
		initOutline();
		final int indexSize = revisionCount();
		if (indexSize == 0) {
			return;
		}
		if (end == TIP) {
			end = indexSize - 1;
		}
		if (start == TIP) {
			start = indexSize - 1;
		}
		HgInternals.checkRevlogRange(start, end, indexSize-1);
		// XXX may cache [start .. end] from index with a single read (pre-read)
		
		ReaderN1 r = new ReaderN1(needData, inspector, dataAccess.shallMergePatches());
		try {
			r.start(end - start + 1, getLastRevisionRead());
			r.range(start, end);
		} catch (IOException ex) {
			throw new HgInvalidControlFileException(String.format("Failed reading [%d..%d]", start, end), ex, indexFile);
		} catch (HgInvalidControlFileException ex) {
			throw ex;
		} finally {
			CachedRevision cr = r.finish();
			setLastRevisionRead(cr);
		}
	}
	
	/**
	 * Effective alternative to {@link #iterate(int, int, boolean, Inspector) batch read}, when only few selected 
	 * revisions are of interest.
	 * @param sortedRevisions revisions to walk, in ascending order.
	 * @param needData whether inspector needs access to header only
	 * @param inspector callback to process entries
	 */
	public void iterate(int[] sortedRevisions, boolean needData, Inspector inspector) throws HgInvalidRevisionException, HgInvalidControlFileException /*REVISIT - too general exception*/ {
		final int indexSize = revisionCount();
		if (indexSize == 0 || sortedRevisions.length == 0) {
			return;
		}
		if (sortedRevisions[0] > indexSize) {
			throw new HgInvalidRevisionException(String.format("Can't iterate [%d, %d] in range [0..%d]", sortedRevisions[0], sortedRevisions[sortedRevisions.length - 1], indexSize), null, sortedRevisions[0]);
		}
		if (sortedRevisions[sortedRevisions.length - 1] > indexSize) {
			throw new HgInvalidRevisionException(String.format("Can't iterate [%d, %d] in range [0..%d]", sortedRevisions[0], sortedRevisions[sortedRevisions.length - 1], indexSize), null, sortedRevisions[sortedRevisions.length - 1]);
		}

		ReaderN1 r = new ReaderN1(needData, inspector, dataAccess.shallMergePatches());
		try {
			r.start(sortedRevisions.length, getLastRevisionRead());
			for (int i = 0; i < sortedRevisions.length; ) {
				int x = i;
				i++;
				while (i < sortedRevisions.length) {
					if (sortedRevisions[i] == sortedRevisions[i-1] + 1) {
						i++;
					} else {
						break;
					}
				}
				// commitRevisions[x..i-1] are sequential
				if (!r.range(sortedRevisions[x], sortedRevisions[i-1])) {
					return;
				}
			}
		} catch (IOException ex) {
			final int c = sortedRevisions.length;
			throw new HgInvalidControlFileException(String.format("Failed reading %d revisions in [%d; %d]",c, sortedRevisions[0], sortedRevisions[c-1]), ex, indexFile);
		} catch (HgInvalidControlFileException ex) {
			// TODO post-1.0 fill HgRuntimeException with appropriate file (either index or data, depending on error source)
			throw ex;
		} finally {
			CachedRevision cr = r.finish();
			setLastRevisionRead(cr);
		}
	}

	void revisionAdded(int revisionIndex, Nodeid revision, int baseRevisionIndex, long revisionOffset) throws HgInvalidControlFileException {
		if (!outlineCached()) {
			return;
		}
		if (baseRevisions.length != revisionIndex) {
			throw new HgInvalidControlFileException(String.format("New entry's index shall be %d, not %d", baseRevisions.length, revisionIndex), null, indexFile);
		}
		if (baseRevisionIndex < 0 || baseRevisionIndex > baseRevisions.length) {
			// baseRevisionIndex MAY be == to baseRevisions.length, it's when new revision is based on itself
			throw new HgInvalidControlFileException(String.format("Base revision index %d doesn't fit [0..%d] range", baseRevisionIndex, baseRevisions.length), null, indexFile);
		}
		assert revision != null;
		assert !revision.isNull();
		int[] baseRevisionsCopy = new int[baseRevisions.length + 1];
		System.arraycopy(baseRevisions, 0, baseRevisionsCopy, 0, baseRevisions.length);
		baseRevisionsCopy[baseRevisions.length] = baseRevisionIndex;
		baseRevisions = baseRevisionsCopy;
		if (inline && indexRecordOffset != null) {
			assert indexRecordOffset.length == revisionIndex;
			int[] indexRecordOffsetCopy = new int[indexRecordOffset.length + 1];
			System.arraycopy(indexRecordOffset, 0, indexRecordOffsetCopy, 0, indexRecordOffset.length);
			indexRecordOffsetCopy[indexRecordOffset.length] = offsetFieldToInlineFileOffset(revisionOffset, revisionIndex);
			indexRecordOffset = indexRecordOffsetCopy;
		}
	}
	
	private int getBaseRevision(int revision) {
		return baseRevisions[revision];
	}

	/**
	 * @param revisionIndex shall be valid index, [0..baseRevisions.length-1]. 
	 * It's advised to use {@link #checkRevisionIndex(int)} to ensure argument is correct. 
	 * @return  offset of the revision's record in the index (.i) stream
	 */
	private int getIndexOffsetInt(int revisionIndex) {
		return inline ? indexRecordOffset[revisionIndex] : revisionIndex * REVLOGV1_RECORD_SIZE;
	}
	
	private int checkRevisionIndex(int revisionIndex) throws HgInvalidRevisionException {
		final int last = revisionCount() - 1;
		if (revisionIndex == TIP) {
			revisionIndex = last;
		}
		if (revisionIndex < 0 || revisionIndex > last) {
			throw new HgInvalidRevisionException(revisionIndex).setRevisionIndex(revisionIndex, 0, last);
		}
		return revisionIndex;
	}
	
	private boolean outlineCached() {
		return baseRevisions != null && baseRevisions.length > 0;
	}
	
	// translate 6-byte offset field value to physical file offset for inline revlogs
	// DOESN'T MAKE SENSE if revlog with data is separate
	private static int offsetFieldToInlineFileOffset(long offset, int recordIndex) throws HgInvalidStateException {
		int o = Internals.ltoi(offset);
		if (o != offset) {
			// just in case, can't happen, ever, unless HG (or some other bad tool) produces index file 
			// with inlined data of size greater than 2 Gb.
			throw new HgInvalidStateException("Data too big, offset didn't fit to sizeof(int)");
		}
		return o + REVLOGV1_RECORD_SIZE * recordIndex;
	}

	private void initOutline() throws HgInvalidControlFileException {
		if (outlineCached()) {
			return;
		}
		DataAccess da = getIndexStream(false);
		try {
			if (da.isEmpty()) {
				// do not fail with exception if stream is empty, it's likely intentional
				baseRevisions = new int[0];
				// empty revlog, likely to be populated, indicate we start with a single file
				inline = true;
				return;
			}
			int versionField = da.readInt();
			da.readInt(); // just to skip next 4 bytes of offset + flags
			final int INLINEDATA = 1 << 16;
			inline = (versionField & INLINEDATA) != 0;
			IntVector resBases, resOffsets = null;
			int entryCountGuess = Internals.ltoi(da.longLength() / REVLOGV1_RECORD_SIZE);
			if (inline) {
				entryCountGuess >>>= 2; // pure guess, assume useful data takes 3/4 of total space
				resOffsets = new IntVector(entryCountGuess, 5000);
			}
			resBases = new IntVector(entryCountGuess, 5000);
			
			long offset = 0; // first offset is always 0, thus Hg uses it for other purposes
			while(true) {
				int compressedLen = da.readInt();
				// 8+4 = 12 bytes total read here
				@SuppressWarnings("unused")
				int actualLen = da.readInt();
				int baseRevision = da.readInt();
				// 12 + 8 = 20 bytes read here
//				int linkRevision = di.readInt();
//				int parent1Revision = di.readInt();
//				int parent2Revision = di.readInt();
//				byte[] nodeid = new byte[32];
				resBases.add(baseRevision);
				if (inline) {
					int o = offsetFieldToInlineFileOffset(offset, resOffsets.size());
					resOffsets.add(o);
					da.skip(3*4 + 32 + compressedLen); // Check: 44 (skip) + 20 (read) = 64 (total RevlogNG record size)
				} else {
					da.skip(3*4 + 32);
				}
				if (da.isEmpty()) {
					// fine, done then
					baseRevisions = resBases.toArray(true);
					if (inline) {
						indexRecordOffset = resOffsets.toArray(true);
					}
					break;
				} else {
					// start reading next record
					long l = da.readLong();
					offset = l >>> 16;
				}
			}
		} catch (IOException ex) {
			throw new HgInvalidControlFileException("Failed to analyze revlog index", ex, indexFile);
		} finally {
			da.done();
		}
	}
	
	private CachedRevision getLastRevisionRead() {
		return lastRevisionRead == null ? null : lastRevisionRead.get();
	}
	
	private void setLastRevisionRead(CachedRevision cr) {
		// done() for lastRevisionRead.userData has been called by ReaderN1 once
		// it noticed unsuitable DataAccess.
		// Now, done() for any CachedRevision cleared by GC:
		for (Reference<? extends CachedRevision> r; (r = lastRevisionQueue.poll()) != null;) {
			CachedRevision toClean = r.get();
			if (toClean != null && toClean.userData != null) {
				toClean.userData.done();
			}
		}
		if (cr != null) {
			lastRevisionRead = new SoftReference<CachedRevision>(cr, lastRevisionQueue);
		} else {
			lastRevisionRead = null;
		}
	}
	
	final static class CachedRevision {
		final int revision;
		final DataAccess userData;
		
		public CachedRevision(int lastRevisionRead, DataAccess lastUserData) {
			revision = lastRevisionRead;
			userData = lastUserData;
		}
	}

	/**
	 * operation with single file open/close and multiple diverse reads.
	 * XXX initOutline might need similar extraction to keep N1 format knowledge  
	 */
	final class ReaderN1 {
		private final Inspector inspector;
		private final boolean needData;
		private final boolean mergePatches;
		private DataAccess daIndex = null, daData = null;
		private Lifecycle.BasicCallback cb = null;
		private Lifecycle lifecycleListener = null;
		private int lastRevisionRead = BAD_REVISION;
		private DataAccess lastUserData;
		//
		// next are transient values, for range() use only
		private final Inflater inflater = new Inflater();
		// can share buffer between instances of InflaterDataAccess as I never read any two of them in parallel
		private final byte[] inflaterBuffer = new byte[10 * 1024]; // TODO consider using DAP.DEFAULT_FILE_BUFFER
		private final byte[] nodeidBuf = new byte[20];
		// revlog record fields
		private long offset;
		@SuppressWarnings("unused")
		private int flags;
		private int compressedLen;
		private int actualLen;
		private int baseRevision;
		private int linkRevision;
		private int parent1Revision;
		private int parent2Revision;
		
		public ReaderN1(boolean dataRequested, Inspector insp, boolean usePatchMerge) {
			assert insp != null;
			needData = dataRequested;
			inspector = insp;
			mergePatches = usePatchMerge;
		}
		
		public void start(int totalWork, CachedRevision cachedRevision) {
			daIndex = getIndexStream(totalWork <= 10);
			if (needData && !inline) {
				daData = getDataStream();
			}
			lifecycleListener = Adaptable.Factory.getAdapter(inspector, Lifecycle.class, null);
			if (lifecycleListener != null) {
				cb = new Lifecycle.BasicCallback();
				lifecycleListener.start(totalWork, cb, cb);
			}
			if (needData && cachedRevision != null) {
				lastUserData = cachedRevision.userData;
				lastRevisionRead = cachedRevision.revision;
				assert lastUserData != null;
			}
		}

		// invoked only once per instance
		public CachedRevision finish() {
			CachedRevision rv = null;
			if (lastUserData != null) {
				if (lastUserData instanceof ByteArrayDataAccess) {
					// it's safe to cache only in-memory revision texts,
					// if lastUserData is merely a filter over file stream,
					// we'd need to keep file open, and this is bad.
					// XXX perhaps, wrap any DataAccess.byteArray into
					// ByteArrayDataAccess?
					rv = new CachedRevision(lastRevisionRead, lastUserData);
				} else {
					lastUserData.done();
				}
				lastUserData = null;
			}
			if (lifecycleListener != null) {
				lifecycleListener.finish(cb);
				lifecycleListener = null;
				cb = null;
				
			}
			daIndex.done();
			if (daData != null) {
				daData.done();
				daData = null;
			}
			return rv;
		}
		
		private void readHeaderRecord(int i) throws IOException {
			if (inline && needData) {
				// inspector reading data (though FilterDataAccess) may have affected index position
				daIndex.seek(getIndexOffsetInt(i));
			}
			long l = daIndex.readLong(); // 0
			offset = i == 0 ? 0 : (l >>> 16);
			flags = (int) (l & 0x0FFFF);
			compressedLen = daIndex.readInt(); // +8
			actualLen = daIndex.readInt(); // +12
			baseRevision = daIndex.readInt(); // +16
			linkRevision = daIndex.readInt(); // +20
			parent1Revision = daIndex.readInt();
			parent2Revision = daIndex.readInt();
			// Hg has 32 bytes here, uses 20 for nodeid, and keeps 12 last bytes empty
			daIndex.readBytes(nodeidBuf, 0, 20); // +32
			daIndex.skip(12);
		}
		
		private boolean isPatch(int i) {
			return baseRevision != i; // the only way I found to tell if it's a patch
		}
		
		private DataAccess getStoredData(int i) throws IOException {
			DataAccess userDataAccess = null;
			DataAccess streamDataAccess;
			long streamOffset;
			if (inline) {
				streamOffset = getIndexOffsetInt(i) + REVLOGV1_RECORD_SIZE;
				streamDataAccess = daIndex;
				 // don't need to do seek as it's actual position in the index stream, but it's safe to seek, just in case
				daIndex.longSeek(streamOffset);
			} else {
				streamOffset = offset;
				streamDataAccess = daData;
				daData.longSeek(streamOffset);
			}
			if (streamDataAccess.isEmpty() || compressedLen == 0) {
				userDataAccess = new DataAccess(); // empty
			} else {
				final byte firstByte = streamDataAccess.readByte();
				if (firstByte == 0x78 /* 'x' */) {
					inflater.reset();
					userDataAccess = new InflaterDataAccess(streamDataAccess, streamOffset, compressedLen, isPatch(i) ? -1 : actualLen, inflater, inflaterBuffer);
				} else if (firstByte == 0x75 /* 'u' */) {
					userDataAccess = new FilterDataAccess(streamDataAccess, streamOffset+1, compressedLen-1);
				} else {
					// XXX Python impl in fact throws exception when there's not 'x', 'u' or '0' but I don't see reason not to return data as is
					//
					// although firstByte is already read from the streamDataAccess, FilterDataAccess#readByte would seek to
					// initial offset before first attempt to read a byte
					userDataAccess = new FilterDataAccess(streamDataAccess, streamOffset, compressedLen);
				}
			}
			return userDataAccess;
		}

		// may be invoked few times per instance life
		public boolean range(int start, int end) throws IOException {
			int i;
			// it (i.e. replace with i >= start)
			if (needData && (i = getBaseRevision(start)) < start) {
				// if lastRevisionRead in [baseRevision(start), start)  can reuse lastUserData
				// doesn't make sense to reuse if lastRevisionRead == start (too much to change in the cycle below). 
				if (lastRevisionRead != BAD_REVISION && i <= lastRevisionRead && lastRevisionRead < start) {
					i = lastRevisionRead + 1; // start with first not-yet-read revision
				} else {
					if (lastUserData != null) {
						lastUserData.done();
						lastUserData = null;
					}
				}
			} else {
				// don't need to clean lastUserData as it's always null when !needData
				i = start;
			}
			
			daIndex.seek(getIndexOffsetInt(i));
			//
			// reuse instance, do not normalize it as patches from the stream are unlikely to need it
			final Patch patch = new Patch(false);
			//
			if (needData && mergePatches && start-i > 2) {
				// i+1 == start just reads lastUserData, i+2 == start applies one patch - not worth dedicated effort 
				Patch ultimatePatch = new Patch(true);
				for ( ; i < start; i++) {
					readHeaderRecord(i);
					DataAccess userDataAccess = getStoredData(i);
					if (lastUserData == null) {
						assert !isPatch(i);
						lastUserData = userDataAccess;
					} else {
						assert isPatch(i); // i < start and i == getBaseRevision()
						patch.read(userDataAccess);
						userDataAccess.done();
						// I assume empty patches are applied ok
						ultimatePatch = ultimatePatch.apply(patch);
						patch.clear();
					}
				}
				lastUserData.reset();
				byte[] userData = ultimatePatch.apply(lastUserData, actualLen);
				ultimatePatch.clear();
				lastUserData.done();
				lastUserData = new ByteArrayDataAccess(userData);
			}
			//
			
			for (; i <= end; i++ ) {
				readHeaderRecord(i);
				DataAccess userDataAccess = null;
				if (needData) {
					userDataAccess = getStoredData(i);
					// userDataAccess is revision content, either complete revision, patch of a previous content, or an empty patch
					if (isPatch(i)) {
						// this is a patch
						if (userDataAccess.isEmpty()) {
							// Issue 22, empty patch to an empty base revision
							// Issue 24, empty patch to non-empty base revision
							// empty patch modifies nothing, use content of a previous revision (shall present - it's a patch here)
							//
							assert lastUserData.length() == actualLen; // with no patch, data size shall be the same
							userDataAccess = lastUserData;
						} else {
							patch.read(userDataAccess);
							userDataAccess.done();
							//
							// it shall be reset at the end of prev iteration, when it got assigned from userDataAccess
							// however, actual userDataAccess and lastUserData may share Inflater object, which needs to be reset
							// Alternatively, userDataAccess.done() above may be responsible to reset Inflater (if it's InflaterDataAccess)
							lastUserData.reset();
//							final long startMeasuring = System.currentTimeMillis(); // TIMING
							byte[] userData = patch.apply(lastUserData, actualLen);
//							applyTime += (System.currentTimeMillis() - startMeasuring); // TIMING
							patch.clear(); // do not keep any reference, allow byte[] data to be gc'd
							userDataAccess = new ByteArrayDataAccess(userData);
						}
					}
				} else {
					if (inline) {
						daIndex.skip(compressedLen);
					}
				}
				if (i >= start) {
//					final long startMeasuring = System.currentTimeMillis(); // TIMING
					inspector.next(i, actualLen, baseRevision, linkRevision, parent1Revision, parent2Revision, nodeidBuf, userDataAccess);
//					inspectorTime += (System.currentTimeMillis() - startMeasuring); // TIMING
				}
				if (cb != null) {
					if (cb.isStopped()) {
						return false;
					}
				}
				if (userDataAccess != null) {
					userDataAccess.reset(); // not sure this is necessary here, as lastUserData would get reset anyway before next use.
				}
				if (lastUserData != null && lastUserData != userDataAccess /* empty patch case, reuse of recent data in actual revision */) {
					// release lastUserData only if we didn't reuse it in actual revision due to empty patch:
					// empty patch means we have previous revision and didn't alter it with a patch, hence use lastUserData for userDataAccess above
					lastUserData.done();
				}
				lastUserData = userDataAccess;
			}
			lastRevisionRead = end;
			return true;
		}
	}

	
	public interface Inspector {
		// XXX boolean retVal to indicate whether to continue?
		// TODO specify nodeid and data length, and reuse policy (i.e. if revlog stream doesn't reuse nodeid[] for each call)
		// implementers shall not invoke DataAccess.done(), it's accomplished by #iterate at appropraite moment
		void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[/*20*/] nodeid, DataAccess data);
	}

}
