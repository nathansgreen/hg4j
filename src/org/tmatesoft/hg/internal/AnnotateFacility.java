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

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.PatchGenerator.LineSequence;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.util.CancelledException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="work in progress")
public class AnnotateFacility {
	
	/**
	 * mimic 'hg diff -r csetRevIndex1 -r csetRevIndex2'
	 */
	public void diff(HgDataFile df, int csetRevIndex1, int csetRevIndex2, BlockInspector insp) {
		int fileRevIndex1 = fileRevIndex(df, csetRevIndex1);
		int fileRevIndex2 = fileRevIndex(df, csetRevIndex2);
		LineSequence c1 = lines(df, fileRevIndex1);
		LineSequence c2 = lines(df, fileRevIndex2);
		PatchGenerator<LineSequence> pg = new PatchGenerator<LineSequence>();
		pg.init(c1, c2);
		pg.findMatchingBlocks(new BlameBlockInspector(insp, csetRevIndex1, csetRevIndex2));
	}

	/**
	 * Annotate file revision, line by line. 
	 */
	public void annotate(HgDataFile df, int changesetRevisionIndex, LineInspector insp) {
		if (!df.exists()) {
			return;
		}
		int fileRevIndex = fileRevIndex(df, changesetRevisionIndex);
		int[] fileRevParents = new int[2];
		FileAnnotation fa = new FileAnnotation(insp);
		do {
			// also covers changesetRevisionIndex == TIP, #implAnnotateChange doesn't tolerate constants
			changesetRevisionIndex = df.getChangesetRevisionIndex(fileRevIndex);
			df.parents(fileRevIndex, fileRevParents, null, null);
			implAnnotateChange(df, changesetRevisionIndex, fileRevIndex, fileRevParents, fa);
			fileRevIndex = fileRevParents[0];
		} while (fileRevIndex != NO_REVISION);
	}

	/**
	 * Annotates changes of the file against its parent(s)
	 */
	public void annotateChange(HgDataFile df, int changesetRevisionIndex, BlockInspector insp) {
		// TODO detect if file is text/binary (e.g. looking for chars < ' ' and not \t\r\n\f
		int fileRevIndex = fileRevIndex(df, changesetRevisionIndex);
		int[] fileRevParents = new int[2];
		df.parents(fileRevIndex, fileRevParents, null, null);
		if (changesetRevisionIndex == TIP) {
			changesetRevisionIndex = df.getChangesetRevisionIndex(fileRevIndex);
		}
		implAnnotateChange(df, changesetRevisionIndex, fileRevIndex, fileRevParents, insp);
	}

	private void implAnnotateChange(HgDataFile df, int csetRevIndex, int fileRevIndex, int[] fileParentRevs, BlockInspector insp) {
		final LineSequence fileRevLines = lines(df, fileRevIndex);
		if (fileParentRevs[0] != NO_REVISION && fileParentRevs[1] != NO_REVISION) {
			LineSequence p1Lines = lines(df, fileParentRevs[0]);
			LineSequence p2Lines = lines(df, fileParentRevs[1]);
			int p1ClogIndex = df.getChangesetRevisionIndex(fileParentRevs[0]);
			int p2ClogIndex = df.getChangesetRevisionIndex(fileParentRevs[1]);
			PatchGenerator<LineSequence> pg = new PatchGenerator<LineSequence>();
			pg.init(p2Lines, fileRevLines);
			EqualBlocksCollector p2MergeCommon = new EqualBlocksCollector();
			pg.findMatchingBlocks(p2MergeCommon);
			//
			pg.init(p1Lines);
			BlameBlockInspector bbi = new BlameBlockInspector(insp, p1ClogIndex, csetRevIndex);
			bbi.setMergeParent2(p2MergeCommon, p2ClogIndex);
			pg.findMatchingBlocks(bbi);
		} else if (fileParentRevs[0] == fileParentRevs[1]) {
			// may be equal iff both are unset
			assert fileParentRevs[0] == NO_REVISION;
			// everything added
			BlameBlockInspector bbi = new BlameBlockInspector(insp, NO_REVISION, csetRevIndex);
			bbi.begin(LineSequence.newlines(new byte[0]), fileRevLines);
			bbi.match(0, fileRevLines.chunkCount()-1, 0);
			bbi.end();
		} else {
			int soleParent = fileParentRevs[0] == NO_REVISION ? fileParentRevs[1] : fileParentRevs[0];
			assert soleParent != NO_REVISION;
			LineSequence parentLines = lines(df, soleParent);
			
			int parentChangesetRevIndex = df.getChangesetRevisionIndex(soleParent);
			PatchGenerator<LineSequence> pg = new PatchGenerator<LineSequence>();
			pg.init(parentLines, fileRevLines);
			pg.findMatchingBlocks(new BlameBlockInspector(insp, parentChangesetRevIndex, csetRevIndex));
		}
	}

	private static int fileRevIndex(HgDataFile df, int csetRevIndex) {
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(csetRevIndex, df.getPath());
		return df.getRevisionIndex(fileRev);
	}

	private static LineSequence lines(HgDataFile df, int fileRevIndex) {
		try {
			ByteArrayChannel c;
			df.content(fileRevIndex, c = new ByteArrayChannel());
			return LineSequence.newlines(c.toArray());
		} catch (CancelledException ex) {
			// TODO likely it was bad idea to throw cancelled exception from content()
			// deprecate and provide alternative?
			HgInvalidStateException ise = new HgInvalidStateException("ByteArrayChannel never throws CancelledException");
			ise.initCause(ex);
			throw ise;
		}
	}

	@Callback
	public interface BlockInspector {
		void same(EqualBlock block);
		void added(AddBlock block);
		void changed(ChangeBlock block);
		void deleted(DeleteBlock block);
	}
	
	@Callback
	public interface BlockInspectorEx extends BlockInspector { // XXX better name
		// XXX perhaps, shall pass object instead of separate values for future extension?
		void start(int originLineCount, int targetLineCount);
		void done();
	}
	
	public interface Block {
		int originChangesetIndex();
		int targetChangesetIndex();
//		boolean isMergeRevision();
//		int fileRevisionIndex();
//		int originFileRevisionIndex();
//		String[] lines();
//		byte[] data();
	}
	
	public interface EqualBlock extends Block {
		int originStart();
		int targetStart();
		int length();
	}
	
	public interface AddBlock extends Block {
		int insertedAt(); // line index in the old file 
		int firstAddedLine();
		int totalAddedLines();
		String[] addedLines();
	}
	public interface DeleteBlock extends Block {
		int removedAt(); // line index in the new file
		int firstRemovedLine();
		int totalRemovedLines();
		String[] removedLines();
	}
	public interface ChangeBlock extends AddBlock, DeleteBlock {
	}
	
	@Callback
	public interface LineInspector {
		/**
		 * Not necessarily invoked sequentially by line numbers
		 */
		void line(int lineNumber, int changesetRevIndex, LineDescriptor ld);
	}
	
	public interface LineDescriptor {
		int totalLines();
	}
	


	static class BlameBlockInspector extends PatchGenerator.DeltaInspector<LineSequence> {
		private final BlockInspector insp;
		private final int csetOrigin;
		private final int csetTarget;
		private EqualBlocksCollector p2MergeCommon;
		private int csetMergeParent;
		private IntVector mergeRanges;

		public BlameBlockInspector(BlockInspector inspector, int originCset, int targetCset) {
			assert inspector != null;
			insp = inspector;
			csetOrigin = originCset;
			csetTarget = targetCset;
		}
		
		public void setMergeParent2(EqualBlocksCollector p2Merge, int parentCset2) {
			p2MergeCommon = p2Merge;
			csetMergeParent = parentCset2;
			mergeRanges = new IntVector(3*10, 3*10);
		}
		
		@Override
		public void begin(LineSequence s1, LineSequence s2) {
			super.begin(s1, s2);
			if (insp instanceof BlockInspectorEx) {
				((BlockInspectorEx) insp).start(s1.chunkCount() - 1, s2.chunkCount() - 1);
			}
		}
		
		@Override
		public void end() {
			super.end();
			if(insp instanceof BlockInspectorEx) {
				((BlockInspectorEx) insp).done();
			}
		}

		@Override
		protected void changed(int s1From, int s1To, int s2From, int s2To) {
			if (p2MergeCommon != null) {
				mergeRanges.clear();
				p2MergeCommon.combineAndMarkRangesWithTarget(s2From, s2To - s2From, csetOrigin, csetMergeParent, mergeRanges);
				
				/*
				 * Usecases:
				 * 3 lines changed to 10 lines. range of 10 lines breaks down to 2 from p2, 3 from p1, and 5 from p2.
				 * We report: 2 lines changed to 2(p2), then 1 line changed with 3(p1) and 5 lines added from p2.
				 * 
				 * 10 lines changed to 3 lines, range of 3 lines breaks down to 2 line from p1 and 1 line from p2.
				 * We report: 2 lines changed to 2(p1) and 8 lines changed to 1(p2) 
				 */
				int s1TotalLines = s1To - s1From, s1ConsumedLines = 0, s1Start = s1From;
				
				for (int i = 0; i < mergeRanges.size(); i += 3) {
					final int rangeOrigin = mergeRanges.get(i);
					final int rangeStart = mergeRanges.get(i+1);
					final int rangeLen = mergeRanges.get(i+2);
					final boolean lastRange = i+3 >= mergeRanges.size();
					final int s1LinesLeft = s1TotalLines - s1ConsumedLines;
					// how many lines we may reported as changed (don't use more than in range unless it's the very last range)
					final int s1LinesToBorrow = lastRange ? s1LinesLeft : Math.min(s1LinesLeft, rangeLen);
					if (s1LinesToBorrow > 0) {
						BlockImpl2 block = new BlockImpl2(seq1, seq2, s1Start, s1LinesToBorrow, rangeStart, rangeLen, s1Start, rangeStart);
						block.setOriginAndTarget(rangeOrigin, csetTarget);
						insp.changed(block);
						s1ConsumedLines += s1LinesToBorrow;
						s1Start += s1LinesToBorrow;
					} else {
						BlockImpl2 block = getAddBlock(rangeStart, rangeLen, s1Start);
						block.setOriginAndTarget(rangeOrigin, csetTarget);
						insp.added(block);
					}
				}
				if (s1ConsumedLines != s1TotalLines) {
					throw new HgInvalidStateException(String.format("Expected to process %d lines, but actually was %d", s1TotalLines, s1ConsumedLines));
				}
			} else {
				BlockImpl2 block = new BlockImpl2(seq1, seq2, s1From, s1To-s1From, s2From, s2To - s2From, s1From, s2From);
				block.setOriginAndTarget(csetOrigin, csetTarget);
				insp.changed(block);
			}
		}
		
		@Override
		protected void added(int s1InsertPoint, int s2From, int s2To) {
			if (p2MergeCommon != null) {
				mergeRanges.clear();
				p2MergeCommon.combineAndMarkRangesWithTarget(s2From, s2To - s2From, csetOrigin, csetMergeParent, mergeRanges);
				int insPoint = s1InsertPoint; // track changes to insertion point
				for (int i = 0; i < mergeRanges.size(); i += 3) {
					int rangeOrigin = mergeRanges.get(i);
					int rangeStart = mergeRanges.get(i+1);
					int rangeLen = mergeRanges.get(i+2);
					BlockImpl2 block = getAddBlock(rangeStart, rangeLen, insPoint);
					block.setOriginAndTarget(rangeOrigin, csetTarget);
					insp.added(block);
					// indicate insPoint moved down number of lines we just reported
					insPoint += rangeLen;
				}
			} else {
				BlockImpl2 block = getAddBlock(s2From, s2To - s2From, s1InsertPoint);
				block.setOriginAndTarget(csetOrigin, csetTarget);
				insp.added(block);
			}
		}
		
		@Override
		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			BlockImpl2 block = new BlockImpl2(seq1, null, s1From, s1To - s1From, -1, -1, -1, s2DeletePoint);
			block.setOriginAndTarget(csetOrigin, csetTarget);
			insp.deleted(block);
		}

		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			BlockImpl1 block = new BlockImpl1(s1From, s2From, length);
			block.setOriginAndTarget(csetOrigin, csetTarget);
			insp.same(block);
		}
		
		private BlockImpl2 getAddBlock(int start, int len, int insPoint) {
			return new BlockImpl2(null, seq2, -1, -1, start, len, insPoint, -1);
		}
	}
	
	static class BlockImpl implements Block {
		
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

	static class BlockImpl1 extends BlockImpl implements EqualBlock {
		private final int start1, start2;
		private final int length;
		
		BlockImpl1(int blockStartSeq1, int blockStartSeq2, int blockLength) {
			start1 = blockStartSeq1;
			start2 = blockStartSeq2;
			length = blockLength;
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
		
		@Override
		public String toString() {
			return String.format("@@ [%d..%d) == [%d..%d) @@", start1, start1+length, start2, start2+length);
		}
	}
	
	static class BlockImpl2 extends BlockImpl implements ChangeBlock {
		
		private final LineSequence oldSeq;
		private final LineSequence newSeq;
		private final int s1Start;
		private final int s1Len;
		private final int s2Start;
		private final int s2Len;
		private final int s1InsertPoint;
		private final int s2DeletePoint;

		public BlockImpl2(LineSequence s1, LineSequence s2, int s1Start, int s1Len, int s2Start, int s2Len, int s1InsertPoint, int s2DeletePoint) {
			oldSeq = s1;
			newSeq = s2;
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

		public String[] addedLines() {
			return generateLines(totalAddedLines(), firstAddedLine());
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

		public String[] removedLines() {
			return generateLines(totalRemovedLines(), firstRemovedLine());
		}
		
		private String[] generateLines(int count, int startFrom) {
			String[] rv = new String[count];
			for (int i = 0; i < count; i++) {
				rv[i] = String.format("LINE %d", startFrom + i+1);
			}
			return rv;
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

	static class EqualBlocksCollector implements PatchGenerator.MatchInspector<LineSequence> {
		private final IntVector matches = new IntVector(10*3, 2*3);

		public void begin(LineSequence s1, LineSequence s2) {
		}

		public void match(int startSeq1, int startSeq2, int matchLength) {
			matches.add(startSeq1);
			matches.add(startSeq2);
			matches.add(matchLength);
		}

		public void end() {
		}
		
		// true when specified line in origin is equal to a line in target
		public boolean includesOriginLine(int ln) {
			return includes(ln, 0);
		}
		
		// true when specified line in target is equal to a line in origin
		public boolean includesTargetLine(int ln) {
			return includes(ln, 1);
		}
		
		public void intersectWithTarget(int start, int length, IntVector result) {
			int s = start;
			for (int l = start, x = start + length; l < x; l++) {
				if (!includesTargetLine(l)) {
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
		public void combineAndMarkRangesWithTarget(int start, int length, int markerSource, int markerTarget, IntVector result) {
			int sourceStart = start, targetStart = start, sourceEnd = start + length;
			for (int l = sourceStart; l < sourceEnd; l++) {
				if (includesTargetLine(l)) {
					// l is from target
					if (sourceStart < l) {
						// few lines from source range were not in the target, report them
						result.add(markerSource);
						result.add(sourceStart);
						result.add(l - sourceStart);
					}
					// indicate the earliest line from source range to use
					sourceStart = l + 1;
				} else {
					// l is not in target
					if (targetStart < l) {
						// report lines from target range
						result.add(markerTarget);
						result.add(targetStart);
						result.add(l - targetStart);
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
				result.add(markerSource);
				result.add(sourceStart);
				result.add(sourceEnd - sourceStart);
			} else if (targetStart < sourceEnd) {
				assert sourceStart == sourceEnd;
				result.add(markerTarget);
				result.add(targetStart);
				result.add(sourceEnd - targetStart);
			}
		}
		
		private boolean includes(int ln, int o) {
			for (int i = 2; i < matches.size(); o += 3, i+=3) {
				int rangeStart = matches.get(o);
				if (rangeStart > ln) {
					return false;
				}
				int rangeLen = matches.get(i);
				if (rangeStart + rangeLen > ln) {
					return true;
				}
			}
			return false;
		}
	}

	public static void main(String[] args) {
		EqualBlocksCollector bc = new EqualBlocksCollector();
		bc.match(-1, 5, 3);
		bc.match(-1, 10, 2);
		bc.match(-1, 15, 3);
		bc.match(-1, 20, 3);
		assert !bc.includesTargetLine(4);
		assert bc.includesTargetLine(7);
		assert !bc.includesTargetLine(8);
		assert bc.includesTargetLine(10);
		assert !bc.includesTargetLine(12);
		IntVector r = new IntVector();
		bc.intersectWithTarget(7, 10, r);
		for (int i = 0; i < r.size(); i+=2) {
			System.out.printf("[%d..%d) ", r.get(i), r.get(i) + r.get(i+1));
		}
		System.out.println();
		r.clear();
		bc.combineAndMarkRangesWithTarget(0, 16, 508, 514, r);
		for (int i = 0; i < r.size(); i+=3) {
			System.out.printf("%d:[%d..%d)  ", r.get(i), r.get(i+1), r.get(i+1) + r.get(i+2));
		}
	}
}
