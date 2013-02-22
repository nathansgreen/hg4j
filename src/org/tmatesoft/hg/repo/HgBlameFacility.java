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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.util.BitSet;
import java.util.LinkedList;
import java.util.ListIterator;

import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.Callback;
import org.tmatesoft.hg.internal.DiffHelper;
import org.tmatesoft.hg.internal.Experimental;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.internal.DiffHelper.LineSequence;
import org.tmatesoft.hg.internal.DiffHelper.LineSequence.ByteChain;
import org.tmatesoft.hg.repo.HgBlameFacility.RevisionDescriptor.Recipient;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;

/**
 * Facility with diff/annotate functionality.
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="Unstable API")
public final class HgBlameFacility {
	
	/**
	 * mimic 'hg diff -r clogRevIndex1 -r clogRevIndex2'
	 */
	public void diff(HgDataFile df, int clogRevIndex1, int clogRevIndex2, BlockInspector insp) {
		int fileRevIndex1 = fileRevIndex(df, clogRevIndex1);
		int fileRevIndex2 = fileRevIndex(df, clogRevIndex2);
		FileLinesCache fileInfoCache = new FileLinesCache(df, 5);
		LineSequence c1 = fileInfoCache.lines(fileRevIndex1);
		LineSequence c2 = fileInfoCache.lines(fileRevIndex2);
		DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
		pg.init(c1, c2);
		pg.findMatchingBlocks(new BlameBlockInspector(fileRevIndex2, insp, clogRevIndex1, clogRevIndex2));
	}
	
	/**
	 * Walk file history up to revision at given changeset and report changes for each revision
	 */
	public void annotate(HgDataFile df, int changelogRevisionIndex, BlockInspector insp, HgIterateDirection iterateOrder) {
		if (!df.exists()) {
			return;
		}
		// Note, changelogRevisionIndex may be TIP, while #implAnnotateChange doesn't tolerate constants
		//
		// XXX df.indexWalk(0, fileRevIndex, ) might be more effective
		int fileRevIndex = fileRevIndex(df, changelogRevisionIndex);
		int[] fileRevParents = new int[2];
		IntVector fileParentRevs = new IntVector((fileRevIndex+1) * 2, 0);
		fileParentRevs.add(NO_REVISION, NO_REVISION);
		for (int i = 1; i <= fileRevIndex; i++) {
			df.parents(i, fileRevParents, null, null);
			fileParentRevs.add(fileRevParents[0], fileRevParents[1]);
		}
		// collect file revisions to visit, from newest to oldest
		IntVector fileRevsToVisit = new IntVector(fileRevIndex + 1, 0);
		LinkedList<Integer> queue = new LinkedList<Integer>();
		BitSet seen = new BitSet(fileRevIndex + 1);
		queue.add(fileRevIndex);
		do {
			int x = queue.removeFirst();
			if (seen.get(x)) {
				continue;
			}
			seen.set(x);
			fileRevsToVisit.add(x);
			int p1 = fileParentRevs.get(2*x);
			int p2 = fileParentRevs.get(2*x + 1);
			if (p1 != NO_REVISION) {
				queue.addLast(p1);
			}
			if (p2 != NO_REVISION) {
				queue.addLast(p2);
			}
		} while (!queue.isEmpty());
		FileLinesCache fileInfoCache = new FileLinesCache(df, 10);
		// fileRevsToVisit now { r10, r7, r6, r5, r0 }
		// and we'll iterate it from behind, e.g. old to new unless reversed 
		if (iterateOrder == HgIterateDirection.NewToOld) {
			fileRevsToVisit.reverse();
		}
		for (int i = fileRevsToVisit.size() - 1; i >= 0; i--) {
			int fri = fileRevsToVisit.get(i);
			int clogRevIndex = df.getChangesetRevisionIndex(fri);
			fileRevParents[0] = fileParentRevs.get(fri * 2);
			fileRevParents[1] = fileParentRevs.get(fri * 2 + 1);
			implAnnotateChange(fileInfoCache, clogRevIndex, fri, fileRevParents, insp);
		}
	}

	/**
	 * Annotates changes of the file against its parent(s). 
	 * Unlike {@link #annotate(HgDataFile, int, BlockInspector, HgIterateDirection)}, doesn't
	 * walk file history, looks at the specified revision only. Handles both parents (if merge revision).
	 */
	public void annotateSingleRevision(HgDataFile df, int changelogRevisionIndex, BlockInspector insp) {
		// TODO detect if file is text/binary (e.g. looking for chars < ' ' and not \t\r\n\f
		int fileRevIndex = fileRevIndex(df, changelogRevisionIndex);
		int[] fileRevParents = new int[2];
		df.parents(fileRevIndex, fileRevParents, null, null);
		if (changelogRevisionIndex == TIP) {
			changelogRevisionIndex = df.getChangesetRevisionIndex(fileRevIndex);
		}
		implAnnotateChange(new FileLinesCache(df, 5), changelogRevisionIndex, fileRevIndex, fileRevParents, insp);
	}

	private void implAnnotateChange(FileLinesCache fl, int csetRevIndex, int fileRevIndex, int[] fileParentRevs, BlockInspector insp) {
		final LineSequence fileRevLines = fl.lines(fileRevIndex);
		if (fileParentRevs[0] != NO_REVISION && fileParentRevs[1] != NO_REVISION) {
			LineSequence p1Lines = fl.lines(fileParentRevs[0]);
			LineSequence p2Lines = fl.lines(fileParentRevs[1]);
			int p1ClogIndex = fl.getChangesetRevisionIndex(fileParentRevs[0]);
			int p2ClogIndex = fl.getChangesetRevisionIndex(fileParentRevs[1]);
			DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
			pg.init(p2Lines, fileRevLines);
			EqualBlocksCollector p2MergeCommon = new EqualBlocksCollector();
			pg.findMatchingBlocks(p2MergeCommon);
			//
			pg.init(p1Lines);
			BlameBlockInspector bbi = new BlameBlockInspector(fileRevIndex, insp, p1ClogIndex, csetRevIndex);
			bbi.setMergeParent2(p2MergeCommon, p2ClogIndex);
			pg.findMatchingBlocks(bbi);
		} else if (fileParentRevs[0] == fileParentRevs[1]) {
			// may be equal iff both are unset
			assert fileParentRevs[0] == NO_REVISION;
			// everything added
			BlameBlockInspector bbi = new BlameBlockInspector(fileRevIndex, insp, NO_REVISION, csetRevIndex);
			bbi.begin(LineSequence.newlines(new byte[0]), fileRevLines);
			bbi.match(0, fileRevLines.chunkCount()-1, 0);
			bbi.end();
		} else {
			int soleParent = fileParentRevs[0] == NO_REVISION ? fileParentRevs[1] : fileParentRevs[0];
			assert soleParent != NO_REVISION;
			LineSequence parentLines = fl.lines(soleParent);
			
			int parentChangesetRevIndex = fl.getChangesetRevisionIndex(soleParent);
			DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
			pg.init(parentLines, fileRevLines);
			pg.findMatchingBlocks(new BlameBlockInspector(fileRevIndex, insp, parentChangesetRevIndex, csetRevIndex));
		}
	}

	private static int fileRevIndex(HgDataFile df, int csetRevIndex) {
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(csetRevIndex, df.getPath());
		return df.getRevisionIndex(fileRev);
	}

	private static class FileLinesCache {
		private final HgDataFile df;
		private final LinkedList<Pair<Integer, LineSequence>> lruCache;
		private final int limit;
		private IntMap<Integer> fileToClogIndexMap = new IntMap<Integer>(20);

		public FileLinesCache(HgDataFile file, int lruLimit) {
			df = file;
			limit = lruLimit;
			lruCache = new LinkedList<Pair<Integer, LineSequence>>();
		}
		
		public int getChangesetRevisionIndex(int fileRevIndex) {
			Integer cached = fileToClogIndexMap.get(fileRevIndex);
			if (cached == null) {
				cached = df.getChangesetRevisionIndex(fileRevIndex);
				fileToClogIndexMap.put(fileRevIndex, cached);
			}
			return cached.intValue();
		}

		public LineSequence lines(int fileRevIndex) {
			Pair<Integer, LineSequence> cached = checkCache(fileRevIndex);
			if (cached != null) {
				return cached.second();
			}
			try {
				ByteArrayChannel c;
				df.content(fileRevIndex, c = new ByteArrayChannel());
				LineSequence rv = LineSequence.newlines(c.toArray());
				lruCache.addFirst(new Pair<Integer, LineSequence>(fileRevIndex, rv));
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

	/**
	 * Client's sink for revision differences.
	 * 
	 * When implemented, clients shall not expect new {@link Block blocks} instances in each call.
	 * 
	 * In case more information about annotated revision is needed, inspector instances may supply 
	 * {@link RevisionDescriptor.Recipient} through {@link Adaptable}.  
	 */
	@Callback
	public interface BlockInspector {
		void same(EqualBlock block);
		void added(AddBlock block);
		void changed(ChangeBlock block);
		void deleted(DeleteBlock block);
	}
	
	/**
	 * Represents content of a block, either as a sequence of bytes or a 
	 * sequence of smaller blocks (lines), if appropriate (according to usage context).
	 * 
	 * This approach allows line-by-line access to content data along with complete byte sequence for the whole block, i.e.
	 * <pre>
	 *    BlockData bd = addBlock.addedLines()
	 *    // bd describes data from the addition completely.
	 *    // elements of the BlockData are lines
	 *    bd.elementCount() == addBlock.totalAddedLines();
	 *    // one cat obtain complete addition with
	 *    byte[] everythingAdded = bd.asArray();
	 *    // or iterate line by line
	 *    for (int i = 0; i < bd.elementCount(); i++) {
	 *    	 byte[] lineContent = bd.elementAt(i);
	 *       String line = new String(lineContent, fileEncodingCharset);
	 *    }
	 *    where bd.elementAt(0) is the line at index addBlock.firstAddedLine() 
	 * </pre> 
	 * 
	 * LineData or ChunkData? 
	 */
	public interface BlockData {
		BlockData elementAt(int index);
		int elementCount();
		byte[] asArray();
	}
	
	/**
	 * {@link BlockInspector} may optionally request extra information about revisions
	 * being inspected, denoting itself as a {@link RevisionDescriptor.Recipient}. This class 
	 * provides complete information about file revision under annotation now. 
	 */
	public interface RevisionDescriptor {
		/**
		 * @return complete source of the diff origin, never <code>null</code>
		 */
		BlockData origin();
		/**
		 * @return complete source of the diff target, never <code>null</code>
		 */
		BlockData target();
		/**
		 * @return changeset revision index of original file, or {@link HgRepository#NO_REVISION} if it's the very first revision
		 */
		int originChangesetIndex();
		/**
		 * @return changeset revision index of the target file
		 */
		int targetChangesetIndex();
		/**
		 * @return <code>true</code> if this revision is merge
		 */
		boolean isMerge();
		/**
		 * @return changeset revision index of the second, merged parent
		 */
		int mergeChangesetIndex();
		/**
		 * @return revision index of the change in target file's revlog
		 */
		int fileRevisionIndex();

		/**
		 * Implement to indicate interest in {@link RevisionDescriptor}.
		 * 
		 * Note, instance of {@link RevisionDescriptor} is the same for 
		 * {@link #start(RevisionDescriptor)} and {@link #done(RevisionDescriptor)} 
		 * methods, and not necessarily a new one (i.e. <code>==</code>) for the next
		 * revision announced.
		 */
		@Callback
		public interface Recipient {
			/**
			 * Comes prior to any change {@link Block blocks}
			 */
			void start(RevisionDescriptor revisionDescription);
			/**
			 * Comes after all change {@link Block blocks} were dispatched
			 */
			void done(RevisionDescriptor revisionDescription);
		}
	}
	
	/**
	 * Each change block comes from a single origin, blocks that are result of a merge
	 * have {@link #originChangesetIndex()} equal to {@link RevisionDescriptor#mergeChangesetIndex()}.
	 */
	public interface Block {
		int originChangesetIndex();
		int targetChangesetIndex();
	}
	
	public interface EqualBlock extends Block {
		int originStart();
		int targetStart();
		int length();
		BlockData content();
	}
	
	public interface AddBlock extends Block {
		/**
		 * @return line index in the origin where this block is inserted
		 */
		int insertedAt();  
		/**
		 * @return line index of the first added line in the target revision
		 */
		int firstAddedLine();
		/**
		 * @return number of added lines in this block
		 */
		int totalAddedLines();
		/**
		 * @return content of added lines
		 */
		BlockData addedLines();
	}
	public interface DeleteBlock extends Block {
		/**
		 * @return line index in the target revision were this deleted block would be
		 */
		int removedAt();
		/**
		 * @return line index of the first removed line in the original revision
		 */
		int firstRemovedLine();
		/**
		 * @return number of deleted lines in this block
		 */
		int totalRemovedLines();
		/**
		 * @return content of deleted lines
		 */
		BlockData removedLines();
	}
	public interface ChangeBlock extends AddBlock, DeleteBlock {
	}
	
	private static class BlameBlockInspector extends DiffHelper.DeltaInspector<LineSequence> {
		private final BlockInspector insp;
		private final int csetOrigin;
		private final int csetTarget;
		private EqualBlocksCollector p2MergeCommon;
		private int csetMergeParent;
		private IntVector mergeRanges;
		private final AnnotateRev annotatedRevision;

		public BlameBlockInspector(int fileRevIndex, BlockInspector inspector, int originCset, int targetCset) {
			assert inspector != null;
			insp = inspector;
			annotatedRevision = new AnnotateRev();
			annotatedRevision.set(fileRevIndex);
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
			ContentBlock originContent = new ContentBlock(s1);
			ContentBlock targetContent = new ContentBlock(s2);
			annotatedRevision.set(originContent, targetContent);
			annotatedRevision.set(csetOrigin, csetTarget, p2MergeCommon != null ? csetMergeParent : NO_REVISION);
			Recipient curious = Adaptable.Factory.getAdapter(insp, Recipient.class, null);
			if (curious != null) {
				curious.start(annotatedRevision);
			}
		}
		
		@Override
		public void end() {
			super.end();
			Recipient curious = Adaptable.Factory.getAdapter(insp, Recipient.class, null);
			if (curious != null) {
				curious.done(annotatedRevision);
			}
			p2MergeCommon = null;
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
						ChangeBlockImpl block = getChangeBlock(s1Start, s1LinesToBorrow, rangeStart, rangeLen);
						block.setOriginAndTarget(rangeOrigin, csetTarget);
						insp.changed(block);
						s1ConsumedLines += s1LinesToBorrow;
						s1Start += s1LinesToBorrow;
					} else {
						ChangeBlockImpl block = getAddBlock(rangeStart, rangeLen, s1Start);
						block.setOriginAndTarget(rangeOrigin, csetTarget);
						insp.added(block);
					}
				}
				if (s1ConsumedLines != s1TotalLines) {
					throw new HgInvalidStateException(String.format("Expected to process %d lines, but actually was %d", s1TotalLines, s1ConsumedLines));
				}
			} else {
				ChangeBlockImpl block = getChangeBlock(s1From, s1To - s1From, s2From, s2To - s2From);
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
		}
		
		@Override
		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			ChangeBlockImpl block = new ChangeBlockImpl(annotatedRevision.origin, null, s1From, s1To - s1From, -1, -1, -1, s2DeletePoint);
			block.setOriginAndTarget(csetOrigin, csetTarget);
			insp.deleted(block);
		}

		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			EqualBlockImpl block = new EqualBlockImpl(s1From, s2From, length, annotatedRevision.target);
			block.setOriginAndTarget(csetOrigin, csetTarget);
			insp.same(block);
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
	

	static class EqualBlocksCollector implements DiffHelper.MatchInspector<LineSequence> {
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

	private static class AnnotateRev implements RevisionDescriptor {
		public ContentBlock origin, target;
		public int originCset, targetCset, mergeCset, fileRevIndex;
		
		public void set(int fileRev) {
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
