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

import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;

import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.internal.DiffHelper.LineSequence;
import org.tmatesoft.hg.internal.DiffHelper.LineSequence.ByteChain;
import org.tmatesoft.hg.core.HgBlameInspector;
import org.tmatesoft.hg.core.HgBlameInspector.*;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;

/**
 * Blame implementation
 * @see HgBlameInspector
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BlameHelper {
	
	private final HgBlameInspector insp;
	private FileLinesCache linesCache;

	public BlameHelper(HgBlameInspector inspector) {
		insp = inspector;
	}

	/**
	 * Build history of the file for the specified range (follow renames if necessary). This history
	 * is used to access various file revision data during subsequent {@link #diff(int, int, int, int)} and
	 * {@link #annotateChange(int, int, int[], int[])} calls. Callers can use returned history for own approaches 
	 * to iteration over file history.

	 * <p>NOTE, clogRevIndexEnd has to list name of the supplied file in the corresponding manifest,
	 * as it's not possible to trace rename history otherwise.
	 */
	public FileHistory prepare(HgDataFile df, int clogRevIndexStart, int clogRevIndexEnd) throws HgRuntimeException {
		assert clogRevIndexStart <= clogRevIndexEnd;
		FileHistory fileHistory = new FileHistory(df, clogRevIndexStart, clogRevIndexEnd);
		fileHistory.build();
		int cacheHint = 5; // cache comes useful when we follow merge branches and don't want to
		// parse base revision twice. There's no easy way to determine max(distance(all(base,merge))),
		// hence the heuristics to use the longest history chunk:
		for (FileRevisionHistoryChunk c : fileHistory.iterate(OldToNew)) {
			// iteration order is not important here
			if (c.revisionCount() > cacheHint) {
				cacheHint = c.revisionCount();
			}
		}
		linesCache = new FileLinesCache(cacheHint);
		for (FileRevisionHistoryChunk fhc : fileHistory.iterate(OldToNew)) {
			// iteration order is not important here
			linesCache.useFileUpTo(fhc.getFile(), fhc.getEndChangeset());
		}
		return fileHistory;
	}
	
	// NO_REVISION is not allowed as any argument
	public void diff(int fileRevIndex1, int clogRevIndex1, int fileRevIndex2, int clogRevIndex2) throws HgCallbackTargetException, HgRuntimeException {
		HgDataFile targetFile = linesCache.getFile(clogRevIndex2);
		LineSequence c1 = linesCache.lines(clogRevIndex1, fileRevIndex1);
		LineSequence c2 = linesCache.lines(clogRevIndex2, fileRevIndex2);
		DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
		pg.init(c1, c2);
		BlameBlockInspector bbi = new BlameBlockInspector(targetFile, fileRevIndex2, insp, clogRevIndex1, clogRevIndex2);
		pg.findMatchingBlocks(bbi);
		bbi.checkErrors();
	}

	public void annotateChange(int fileRevIndex, int csetRevIndex, int[] fileParentRevs, int[] fileParentClogRevs) throws HgCallbackTargetException, HgRuntimeException {
		HgDataFile targetFile = linesCache.getFile(csetRevIndex);
		final LineSequence fileRevLines = linesCache.lines(csetRevIndex, fileRevIndex);
		if (fileParentClogRevs[0] != NO_REVISION && fileParentClogRevs[1] != NO_REVISION) {
			int p1ClogIndex = fileParentClogRevs[0];
			int p2ClogIndex = fileParentClogRevs[1];
			LineSequence p1Lines = linesCache.lines(p1ClogIndex, fileParentRevs[0]);
			LineSequence p2Lines = linesCache.lines(p2ClogIndex, fileParentRevs[1]);
			DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
			pg.init(p2Lines, fileRevLines);
			EqualBlocksCollector p2MergeCommon = new EqualBlocksCollector();
			pg.findMatchingBlocks(p2MergeCommon);
			//
			pg.init(p1Lines);
			BlameBlockInspector bbi = new BlameBlockInspector(targetFile, fileRevIndex, insp, p1ClogIndex, csetRevIndex);
			bbi.setMergeParent2(p2MergeCommon, p2ClogIndex);
			pg.findMatchingBlocks(bbi);
			bbi.checkErrors();
		} else if (fileParentClogRevs[0] == fileParentClogRevs[1]) {
			// may be equal iff both are unset
			assert fileParentClogRevs[0] == NO_REVISION;
			// everything added
			BlameBlockInspector bbi = new BlameBlockInspector(targetFile, fileRevIndex, insp, NO_REVISION, csetRevIndex);
			bbi.begin(LineSequence.newlines(new byte[0]), fileRevLines);
			bbi.match(0, fileRevLines.chunkCount()-1, 0);
			bbi.end();
			bbi.checkErrors();
		} else {
			int soleParentIndex = fileParentClogRevs[0] == NO_REVISION ? 1 : 0;
			assert fileParentClogRevs[soleParentIndex] != NO_REVISION;
			LineSequence parentLines = linesCache.lines(fileParentClogRevs[soleParentIndex], fileParentRevs[soleParentIndex]);
			
			DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
			pg.init(parentLines, fileRevLines);
			BlameBlockInspector bbi = new BlameBlockInspector(targetFile, fileRevIndex, insp, fileParentClogRevs[soleParentIndex], csetRevIndex);
			pg.findMatchingBlocks(bbi);
			bbi.checkErrors();
		}
	}
	
	private static class FileLinesCache {
		private final LinkedList<Pair<Integer, LineSequence>> lruCache;
		private final int limit;
		private final LinkedList<Pair<Integer, HgDataFile>> files; // TODO in fact, need sparse array 

		/**
		 * @param lruLimit how many parsed file revisions to keep
		 */
		public FileLinesCache(int lruLimit) {
			limit = lruLimit;
			lruCache = new LinkedList<Pair<Integer, LineSequence>>();
			files = new LinkedList<Pair<Integer,HgDataFile>>();
		}
		
		public void useFileUpTo(HgDataFile df, int clogRevIndex) {
			Pair<Integer, HgDataFile> newEntry = new Pair<Integer, HgDataFile>(clogRevIndex, df);
			for (ListIterator<Pair<Integer, HgDataFile>> it = files.listIterator(); it.hasNext();) {
				Pair<Integer, HgDataFile> e = it.next();
				if (e.first() == clogRevIndex) {
					assert e.second().getPath().equals(df.getPath());
					return;
				}
				if (e.first() > clogRevIndex) {
					// insert new entry before current
					it.previous();
					it.add(newEntry);
					return;
				}
			}
			files.add(newEntry);
		}
		
		public HgDataFile getFile(int clogRevIndex) {
			for (Pair<Integer, HgDataFile> e : files) {
				if (e.first() >= clogRevIndex) {
					return e.second();
				}
			}
			throw new HgInvalidStateException(String.format("Got %d file-changelog mappings, but no luck for revision %d.", files.size(), clogRevIndex));
		}

		public LineSequence lines(int clogRevIndex, int fileRevIndex) throws HgRuntimeException {
			Pair<Integer, LineSequence> cached = checkCache(clogRevIndex);
			if (cached != null) {
				return cached.second();
			}
			HgDataFile df = getFile(clogRevIndex);
			try {
				ByteArrayChannel c;
				df.content(fileRevIndex, c = new ByteArrayChannel());
				LineSequence rv = LineSequence.newlines(c.toArray());
				lruCache.addFirst(new Pair<Integer, LineSequence>(clogRevIndex, rv));
				if (lruCache.size() > limit) {
					lruCache.removeLast();
				}
				return rv;
			} catch (CancelledException ex) {
				// TODO likely it was bad idea to throw cancelled exception from content()
				// deprecate and provide alternative?
				HgInvalidStateException ise = new HgInvalidStateException("ByteArrayChannel never throws CancelledException");
				ise.initCause(ex);
				throw ise;
			}
		}
		
		private Pair<Integer,LineSequence> checkCache(int fileRevIndex) {
			Pair<Integer, LineSequence> rv = null;
			for (ListIterator<Pair<Integer, LineSequence>> it = lruCache.listIterator(); it.hasNext(); ) {
				Pair<Integer, LineSequence> p = it.next();
				if (p.first() == fileRevIndex) {
					rv = p;
					it.remove();
					break;
				}
			}
			if (rv != null) {
				lruCache.addFirst(rv);
			}
			return rv;
		}
	}

	private static class BlameBlockInspector extends DiffHelper.DeltaInspector<LineSequence> {
		private final HgBlameInspector insp;
		private final int csetOrigin;
		private final int csetTarget;
		private EqualBlocksCollector p2MergeCommon;
		private int csetMergeParent;
		private IntSliceSeq mergeRanges;
		private final AnnotateRev annotatedRevision;
		private HgCallbackTargetException error;

		public BlameBlockInspector(HgDataFile df, int fileRevIndex, HgBlameInspector inspector, int originCset, int targetCset) {
			assert inspector != null;
			insp = inspector;
			annotatedRevision = new AnnotateRev();
			annotatedRevision.set(df, fileRevIndex);
			csetOrigin = originCset;
			csetTarget = targetCset;
		}
		
		public void setMergeParent2(EqualBlocksCollector p2Merge, int parentCset2) {
			p2MergeCommon = p2Merge;
			csetMergeParent = parentCset2;
			mergeRanges = new IntSliceSeq(3, 10, 10);
		}
		
		@Override
		public void begin(LineSequence s1, LineSequence s2) {
			super.begin(s1, s2);
			if (shallStop()) {
				return;
			}
			ContentBlock originContent = new ContentBlock(s1);
			ContentBlock targetContent = new ContentBlock(s2);
			annotatedRevision.set(originContent, targetContent);
			annotatedRevision.set(csetOrigin, csetTarget, p2MergeCommon != null ? csetMergeParent : NO_REVISION);
			RevisionDescriptor.Recipient curious = Adaptable.Factory.getAdapter(insp, RevisionDescriptor.Recipient.class, null);
			if (curious != null) {
				try {
					curious.start(annotatedRevision);
				} catch (HgCallbackTargetException ex) {
					error = ex;
				}
			}
		}
		
		@Override
		public void end() {
			super.end();
			if (shallStop()) {
				return;
			}
			RevisionDescriptor.Recipient curious = Adaptable.Factory.getAdapter(insp, RevisionDescriptor.Recipient.class, null);
			if (curious != null) {
				try {
					curious.done(annotatedRevision);
				} catch (HgCallbackTargetException ex) {
					error = ex;
				}
			}
			p2MergeCommon = null;
		}

		@Override
		protected void changed(int s1From, int s1To, int s2From, int s2To) {
			if (shallStop()) {
				return;
			}
			try {
				if (p2MergeCommon != null) {
					mergeRanges.clear();
					p2MergeCommon.combineAndMarkRangesWithTarget(s2From, s2To - s2From, csetOrigin, csetMergeParent, mergeRanges);
					
					/*
					 * Usecases, how it USED TO BE initially:
					 * 3 lines changed to 10 lines. range of 10 lines breaks down to 2 from p2, 3 from p1, and 5 from p2.
					 * We report: 2 lines changed to 2(p2), then 1 line changed with 3(p1) and 5 lines added from p2.
					 * 
					 * 10 lines changed to 3 lines, range of 3 lines breaks down to 2 line from p1 and 1 line from p2.
					 * We report: 2 lines changed to 2(p1) and 8 lines changed to 1(p2)
					 * 
					 * NOW, lines from p2 are always reported as pure add (since we need their insertion point to be in p2, not in p1)
					 * and we try to consume p1 changes as soon as we see first p1's range 
					 */
					int s1TotalLines = s1To - s1From, s1ConsumedLines = 0, s1Start = s1From;
					
					for (Iterator<IntTuple> it = mergeRanges.iterator(); it.hasNext();) {
						IntTuple mergeRange = it.next();
						final int rangeOrigin = mergeRange.at(0);
						final int rangeStart = mergeRange.at(1);
						final int rangeLen = mergeRange.at(2);
						final boolean lastRange = it.hasNext();
						final int s1LinesLeft = s1TotalLines - s1ConsumedLines;
						// how many lines we may report as changed (don't use more than in range unless it's the very last range)
						final int s1LinesToBorrow = lastRange ? s1LinesLeft : Math.min(s1LinesLeft, rangeLen);
						if (rangeOrigin != csetMergeParent && s1LinesToBorrow > 0) {
							ChangeBlockImpl block = getChangeBlock(s1Start, s1LinesToBorrow, rangeStart, rangeLen);
							block.setOriginAndTarget(rangeOrigin, csetTarget);
							insp.changed(block);
							s1ConsumedLines += s1LinesToBorrow;
							s1Start += s1LinesToBorrow;
						} else {
							int blockInsPoint = rangeOrigin != csetMergeParent ? s1Start : p2MergeCommon.reverseMapLine(rangeStart);
							ChangeBlockImpl block = getAddBlock(rangeStart, rangeLen, blockInsPoint);
							block.setOriginAndTarget(rangeOrigin, csetTarget);
							insp.added(block);
						}
					}
					if (s1ConsumedLines != s1TotalLines) {
						assert s1ConsumedLines < s1TotalLines : String.format("Expected to process %d lines, but actually was %d", s1TotalLines, s1ConsumedLines);
						// either there were no ranges from p1, whole s2From..s2To range came from p2, shall report as deleted
						// or the ranges found were not enough to consume whole s2From..s2To
						// The "deletion point" is shifted to the end of last csetOrigin->csetTarget change
						int s2DeletePoint = s2From + s1ConsumedLines;
						ChangeBlockImpl block =  new ChangeBlockImpl(annotatedRevision.origin, null, s1Start, s1To - s1Start, -1, -1, -1, s2DeletePoint);
						block.setOriginAndTarget(csetOrigin, csetTarget);
						insp.deleted(block);
					}
				} else {
					ChangeBlockImpl block = getChangeBlock(s1From, s1To - s1From, s2From, s2To - s2From);
					block.setOriginAndTarget(csetOrigin, csetTarget);
					insp.changed(block);
				}
			} catch (HgCallbackTargetException ex) {
				error = ex;
			}
		}
		
		@Override
		protected void added(int s1InsertPoint, int s2From, int s2To) {
			if (shallStop()) {
				return;
			}
			try {
				if (p2MergeCommon != null) {
					mergeRanges.clear();
					p2MergeCommon.combineAndMarkRangesWithTarget(s2From, s2To - s2From, csetOrigin, csetMergeParent, mergeRanges);
					int insPoint = s1InsertPoint; // track changes to insertion point
					for (IntTuple mergeRange : mergeRanges) {
						int rangeOrigin = mergeRange.at(0);
						int rangeStart = mergeRange.at(1);
						int rangeLen = mergeRange.at(2);
						ChangeBlockImpl block = getAddBlock(rangeStart, rangeLen, insPoint);
						block.setOriginAndTarget(rangeOrigin, csetTarget);
						insp.added(block);
						// indicate insPoint moved down number of lines we just reported
						insPoint += rangeLen;
					}
				} else {
					ChangeBlockImpl block = getAddBlock(s2From, s2To - s2From, s1InsertPoint);
					block.setOriginAndTarget(csetOrigin, csetTarget);
					insp.added(block);
				}
			} catch (HgCallbackTargetException ex) {
				error = ex;
			}
		}
		
		@Override
		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			if (shallStop()) {
				return;
			}
			try {
				ChangeBlockImpl block = new ChangeBlockImpl(annotatedRevision.origin, null, s1From, s1To - s1From, -1, -1, -1, s2DeletePoint);
				block.setOriginAndTarget(csetOrigin, csetTarget);
				insp.deleted(block);
			} catch (HgCallbackTargetException ex) {
				error = ex;
			}
		}

		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			if (shallStop()) {
				return;
			}
			try {
				EqualBlockImpl block = new EqualBlockImpl(s1From, s2From, length, annotatedRevision.target);
				block.setOriginAndTarget(csetOrigin, csetTarget);
				insp.same(block);
			} catch (HgCallbackTargetException ex) {
				error = ex;
			}
		}
		
		void checkErrors() throws HgCallbackTargetException {
			if (error != null) {
				throw error;
			}
		}
		
		private boolean shallStop() {
			return error != null;
		}
		
		private ChangeBlockImpl getAddBlock(int start, int len, int insPoint) {
			return new ChangeBlockImpl(null, annotatedRevision.target, -1, -1, start, len, insPoint, -1);
		}
		
		private ChangeBlockImpl getChangeBlock(int start1, int len1, int start2, int len2) {
			return new ChangeBlockImpl(annotatedRevision.origin, annotatedRevision.target, start1, len1, start2, len2, start1, start2);
		}
	}
	
	private static class BlockImpl implements Block {
		private int originCset;
		private int targetCset;

		void setOriginAndTarget(int originChangesetIndex, int targetChangesetIndex) {
			// XXX perhaps, shall be part of Inspector API, rather than Block's
			// as they don't change between blocks (although the moment about merged revisions)
			// is not yet clear to me
			originCset = originChangesetIndex;
			targetCset = targetChangesetIndex;
		}

		public int originChangesetIndex() {
			return originCset;
		}

		public int targetChangesetIndex() {
			return targetCset;
		}
	}

	private static class EqualBlockImpl extends BlockImpl implements EqualBlock {
		private final int start1, start2;
		private final int length;
		private final ContentBlock fullContent;
		private FilterBlock myContent;
		
		EqualBlockImpl(int blockStartSeq1, int blockStartSeq2, int blockLength, ContentBlock targetContent) {
			start1 = blockStartSeq1;
			start2 = blockStartSeq2;
			length = blockLength;
			fullContent = targetContent;
		}

		public int originStart() {
			return start1;
		}

		public int targetStart() {
			return start2;
		}

		public int length() {
			return length;
		}
		
		public BlockData content() {
			if (myContent == null) {
				myContent = new FilterBlock(fullContent, start2, length);
			}
			return myContent;
		}
		
		@Override
		public String toString() {
			return String.format("@@ [%d..%d) == [%d..%d) @@", start1, start1+length, start2, start2+length);
		}
	}
	
	private static class ChangeBlockImpl extends BlockImpl implements ChangeBlock {
		private final ContentBlock oldContent;
		private final ContentBlock newContent;
		private final int s1Start;
		private final int s1Len;
		private final int s2Start;
		private final int s2Len;
		private final int s1InsertPoint;
		private final int s2DeletePoint;
		private FilterBlock addedBlock, removedBlock;

		public ChangeBlockImpl(ContentBlock c1, ContentBlock c2, int s1Start, int s1Len, int s2Start, int s2Len, int s1InsertPoint, int s2DeletePoint) {
			oldContent = c1;
			newContent = c2;
			this.s1Start = s1Start;
			this.s1Len = s1Len;
			this.s2Start = s2Start;
			this.s2Len = s2Len;
			this.s1InsertPoint = s1InsertPoint;
			this.s2DeletePoint = s2DeletePoint;
		}
		
		public int insertedAt() {
			return s1InsertPoint;
		}

		public int firstAddedLine() {
			return s2Start;
		}

		public int totalAddedLines() {
			return s2Len;
		}

		public BlockData addedLines() {
			if (addedBlock == null) {
				addedBlock = new FilterBlock(newContent, firstAddedLine(), totalAddedLines());
			}
			return addedBlock;
		}
		
		public int removedAt() {
			return s2DeletePoint;
		}

		public int firstRemovedLine() {
			return s1Start;
		}

		public int totalRemovedLines() {
			return s1Len;
		}

		public BlockData removedLines() {
			if (removedBlock == null) {
				removedBlock = new FilterBlock(oldContent, firstRemovedLine(), totalRemovedLines());
			}
			return removedBlock;
		}
		
		@Override
		public String toString() {
			if (s2DeletePoint == -1) {
				return String.format("@@ -%d,0 +%d,%d @@", insertedAt(), firstAddedLine(), totalAddedLines());
			} else if (s1InsertPoint == -1) {
				// delete only
				return String.format("@@ -%d,%d +%d,0 @@", firstRemovedLine(), totalRemovedLines(), removedAt());
			}
			return String.format("@@ -%d,%d +%d,%d @@", firstRemovedLine(), totalRemovedLines(), firstAddedLine(), totalAddedLines());
		}
	}
	
	private static class SingleLine implements BlockData {
		private final ByteChain line;

		public SingleLine(ByteChain lineContent) {
			line = lineContent;
		}

		public BlockData elementAt(int index) {
			assert false;
			return null;
		}

		public int elementCount() {
			return 0;
		}

		public byte[] asArray() {
			return line.data();
		}
	}
	
	private static class ContentBlock implements BlockData {
		private final LineSequence seq;

		public ContentBlock(LineSequence sequence) {
			seq = sequence;
		}

		public BlockData elementAt(int index) {
			return new SingleLine(seq.chunk(index));
		}

		public int elementCount() {
			return seq.chunkCount() - 1;
		}

		public byte[] asArray() {
			return seq.data(0, seq.chunkCount() - 1);
		}
	}
	
	private static class FilterBlock implements BlockData {
		private final ContentBlock contentBlock;
		private final int from;
		private final int length;

		public FilterBlock(ContentBlock bd, int startFrom, int len) {
			assert bd != null;
			assert startFrom + len < bd.seq.chunkCount(); // there's one extra chunk in the end, so strict less is ok
			contentBlock = bd;
			from = startFrom;
			length = len;
		}

		public BlockData elementAt(int index) {
			if (index < 0 || index >= length) {
				throw new IllegalArgumentException(String.format("Expected value from [0..%d), got %d", length, index));
			}
			return contentBlock.elementAt(from + index);
		}

		public int elementCount() {
			return length;
		}

		public byte[] asArray() {
			return contentBlock.seq.data(from, from + length);
		}
	}
	

	private static class EqualBlocksCollector implements DiffHelper.MatchInspector<LineSequence> {
		private final RangePairSeq matches = new RangePairSeq();

		public void begin(LineSequence s1, LineSequence s2) {
		}

		public void match(int startSeq1, int startSeq2, int matchLength) {
			matches.add(startSeq1, startSeq2, matchLength);
		}

		public void end() {
		}

		public int reverseMapLine(int ln) {
			return matches.reverseMapLine(ln);
		}

		public void intersectWithTarget(int start, int length, IntVector result) {
			int s = start;
			for (int l = start, x = start + length; l < x; l++) {
				if (!matches.includesTargetLine(l)) {
					if (l - s > 0) {
						result.add(s);
						result.add(l - s);
					}
					s = l+1;
				}
			}
			if (s < start+length) {
				result.add(s);
				result.add((start + length) - s);
			}
		}
		
		/*
		 * intersects [start..start+length) with ranges of target lines, and based on the intersection 
		 * breaks initial range into smaller ranges and records them into result, with marker to indicate
		 * whether the range is from initial range (markerSource) or is a result of the intersection with target
		 * (markerTarget)
		 */
		public void combineAndMarkRangesWithTarget(int start, int length, int markerSource, int markerTarget, IntSliceSeq result) {
			assert result.sliceSize() == 3;
			int sourceStart = start, targetStart = start, sourceEnd = start + length;
			for (int l = sourceStart; l < sourceEnd; l++) {
				if (matches.includesTargetLine(l)) {
					// l is from target
					if (sourceStart < l) {
						// few lines from source range were not in the target, report them
						result.add(markerSource, sourceStart, l - sourceStart);
					}
					// indicate the earliest line from source range to use
					sourceStart = l + 1;
				} else {
					// l is not in target
					if (targetStart < l) {
						// report lines from target range
						result.add(markerTarget, targetStart, l - targetStart);
					}
					// next line *may* be from target
					targetStart = l + 1;
				}
			}
			// if source range end with line from target, sourceStart would be == sourceEnd, and we need to add range with markerTarget
			// if source range doesn't end with target line, targetStart == sourceEnd, while sourceStart < sourceEnd
			if (sourceStart < sourceEnd) {
				assert targetStart == sourceEnd;
				// something left from the source range
				result.add(markerSource, sourceStart, sourceEnd - sourceStart);
			} else if (targetStart < sourceEnd) {
				assert sourceStart == sourceEnd;
				result.add(markerTarget, targetStart, sourceEnd - targetStart);
			}
		}
	}

	private static class AnnotateRev implements RevisionDescriptor {
		public ContentBlock origin, target;
		public int originCset, targetCset, mergeCset, fileRevIndex;
		public HgDataFile df;
		
		public void set(HgDataFile file, int fileRev) {
			df = file;
			fileRevIndex = fileRev;
		}
		public void set(ContentBlock o, ContentBlock t) {
			origin = o;
			target = t;
		}
		public void set(int o, int t, int m) {
			originCset = o;
			targetCset = t;
			mergeCset = m;
		}
		
		public BlockData origin() {
			return origin;
		}

		public BlockData target() {
			return target;
		}

		public int originChangesetIndex() {
			return originCset;
		}

		public int targetChangesetIndex() {
			return targetCset;
		}

		public boolean isMerge() {
			return mergeCset != NO_REVISION;
		}

		public int mergeChangesetIndex() {
			return mergeCset;
		}

		public int fileRevisionIndex() {
			return fileRevIndex;
		}
		public HgDataFile file() {
			return df;
		}
		@Override
		public String toString() {
			if (isMerge()) {
				return String.format("[%d,%d->%d]", originCset, mergeCset, targetCset);
			}
			return String.format("[%d->%d]", originCset, targetCset);
		}
	}

	public static void main(String[] args) {
		EqualBlocksCollector bc = new EqualBlocksCollector();
		bc.match(-1, 5, 3);
		bc.match(-1, 10, 2);
		bc.match(-1, 15, 3);
		bc.match(-1, 20, 3);
		IntVector r = new IntVector();
		bc.intersectWithTarget(7, 10, r);
		for (int i = 0; i < r.size(); i+=2) {
			System.out.printf("[%d..%d) ", r.get(i), r.get(i) + r.get(i+1));
		}
		System.out.println();
		IntSliceSeq mr = new IntSliceSeq(3);
		bc.combineAndMarkRangesWithTarget(0, 16, 508, 514, mr);
		for (IntTuple t : mr) {
			System.out.printf("%d:[%d..%d)  ", t.at(0), t.at(1), t.at(1) + t.at(2));
		}
	}
}
