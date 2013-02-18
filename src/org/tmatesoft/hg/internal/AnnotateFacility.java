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
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelledException;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@Experimental(reason="work in progress")
public class AnnotateFacility {

	/**
	 * Annotate file revision, line by line. 
	 */
	public void annotate(HgDataFile df, int changesetRevisionIndex, LineInspector insp) {
		if (!df.exists()) {
			return;
		}
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(changesetRevisionIndex, df.getPath());
		int fileRevIndex = df.getRevisionIndex(fileRev);
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
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(changesetRevisionIndex, df.getPath());
		int fileRevIndex = df.getRevisionIndex(fileRev);
		int[] fileRevParents = new int[2];
		df.parents(fileRevIndex, fileRevParents, null, null);
		if (changesetRevisionIndex == TIP) {
			changesetRevisionIndex = df.getChangesetRevisionIndex(fileRevIndex);
		}
		implAnnotateChange(df, changesetRevisionIndex, fileRevIndex, fileRevParents, insp);
	}

	private void implAnnotateChange(HgDataFile df, int csetRevIndex, int fileRevIndex, int[] fileParentRevs, BlockInspector insp) {
		try {
			if (fileParentRevs[0] != NO_REVISION && fileParentRevs[1] != NO_REVISION) {
				// merge
			} else if (fileParentRevs[0] == fileParentRevs[1]) {
				// may be equal iff both are unset
				assert fileParentRevs[0] == NO_REVISION;
				// everything added
				ByteArrayChannel c;
				df.content(fileRevIndex, c = new ByteArrayChannel());
				BlameBlockInspector bbi = new BlameBlockInspector(insp, NO_REVISION, csetRevIndex);
				LineSequence cls = LineSequence.newlines(c.toArray());
				bbi.begin(LineSequence.newlines(new byte[0]), cls);
				bbi.match(0, cls.chunkCount()-1, 0);
				bbi.end();
			} else {
				int soleParent = fileParentRevs[0] == NO_REVISION ? fileParentRevs[1] : fileParentRevs[0];
				assert soleParent != NO_REVISION;
				ByteArrayChannel c1, c2;
				df.content(soleParent, c1 = new ByteArrayChannel());
				df.content(fileRevIndex, c2 = new ByteArrayChannel());
				int parentChangesetRevIndex = df.getChangesetRevisionIndex(soleParent);
				PatchGenerator<LineSequence> pg = new PatchGenerator<LineSequence>();
				pg.init(LineSequence.newlines(c1.toArray()), LineSequence.newlines(c2.toArray()));
				pg.findMatchingBlocks(new BlameBlockInspector(insp, parentChangesetRevIndex, csetRevIndex));
			}
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
		private final int csetP1;
		private final int csetTarget;

		public BlameBlockInspector(BlockInspector inspector, int parentCset1, int targetCset) {
			assert inspector != null;
			insp = inspector;
			csetP1 = parentCset1;
			csetTarget = targetCset;
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
			BlockImpl2 block = new BlockImpl2(seq1, seq2, s1From, s1To-s1From, s2From, s2To - s2From, s1From, s2From);
			block.setOriginAndTarget(csetP1, csetTarget);
			insp.changed(block);
		}
		
		@Override
		protected void added(int s1InsertPoint, int s2From, int s2To) {
			BlockImpl2 block = new BlockImpl2(null, seq2, -1, -1, s2From, s2To - s2From, s1InsertPoint, -1);
			block.setOriginAndTarget(csetP1, csetTarget);
			insp.added(block);
		}
		
		@Override
		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			BlockImpl2 block = new BlockImpl2(seq1, null, s1From, s1To - s1From, -1, -1, -1, s2DeletePoint);
			block.setOriginAndTarget(csetP1, csetTarget);
			insp.deleted(block);
		}

		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			BlockImpl1 block = new BlockImpl1(s1From, s2From, length);
			block.setOriginAndTarget(csetP1, csetTarget);
			insp.same(block);
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
}
