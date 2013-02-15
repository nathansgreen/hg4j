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

import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.PatchGenerator.ChunkSequence;
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
	
	public void annotate(HgDataFile df, int changestRevisionIndex, Inspector insp) {
		Nodeid fileRev = df.getRepo().getManifest().getFileRevision(changestRevisionIndex, df.getPath());
		int fileRevIndex = df.getRevisionIndex(fileRev);
		int[] fileRevParents = new int[2];
		df.parents(fileRevIndex, fileRevParents, null, null);
		if (fileRevParents[0] != NO_REVISION && fileRevParents[1] != NO_REVISION) {
			// merge
		} else if (fileRevParents[0] == fileRevParents[1]) {
			// may be equal iff both are unset
			assert fileRevParents[0] == NO_REVISION;
			// everything added
			insp.added(null);
		} else {
			int soleParent = fileRevParents[0] == NO_REVISION ? fileRevParents[1] : fileRevParents[0];
			assert soleParent != NO_REVISION;
			try {
				ByteArrayChannel c1, c2;
				df.content(soleParent, c1 = new ByteArrayChannel());
				df.content(fileRevIndex, c2 = new ByteArrayChannel());
				int parentChangesetRevIndex = df.getChangesetRevisionIndex(soleParent);
				PatchGenerator pg = new PatchGenerator();
				pg.init(c1.toArray(), c2.toArray());
				pg.findMatchingBlocks(new BlameBlockInspector(insp));
			} catch (CancelledException ex) {
				// TODO likely it was bad idea to throw cancelled exception from content()
				// deprecate and provide alternative?
				HgInvalidStateException ise = new HgInvalidStateException("ByteArrayChannel never throws CancelledException");
				ise.initCause(ex);
				throw ise;
			}
		}
	}

	@Callback
	public interface Inspector {
		void same(Block block);
		void added(AddBlock block);
		void changed(ChangeBlock block);
		void deleted(DeleteBlock block);
	}
	
	public interface Block {
//		boolean isMergeRevision();
//		int fileRevisionIndex();
//		int originFileRevisionIndex();
//		String[] lines();
//		byte[] data();
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

	static class BlameBlockInspector extends PatchGenerator.DeltaInspector {
		private final Inspector insp;

		public BlameBlockInspector(Inspector inspector) {
			assert inspector != null;
			insp = inspector;
		}

		@Override
		protected void changed(int s1From, int s1To, int s2From, int s2To) {
			insp.changed(new BlockImpl2(seq1, seq2, s1From, s1To-s1From, s2From, s2To - s2From, s1From, s2From));
		}
		
		@Override
		protected void added(int s1InsertPoint, int s2From, int s2To) {
			insp.added(new BlockImpl2(null, seq2, -1, -1, s2From, s2To - s2From, s1InsertPoint, -1));
		}
		
		@Override
		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			insp.deleted(new BlockImpl2(seq1, null, s1From, s1To - s1From, -1, -1, -1, s2DeletePoint));
		}

		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			insp.same(new BlockImpl(seq2, s2From, length));
		}
	}

	static class BlockImpl implements Block {
		private final ChunkSequence seq;
		private final int start;
		private final int length;
		
		BlockImpl() {
			// FIXME delete this cons
			seq = null;
			start = length = -1;
		}

		BlockImpl(ChunkSequence s, int blockStart, int blockLength) {
			seq = s;
			start = blockStart;
			length = blockLength;
		}
		
	}
	
	static class BlockImpl2 implements ChangeBlock {
		
		private final ChunkSequence oldSeq;
		private final ChunkSequence newSeq;
		private final int s1Start;
		private final int s1Len;
		private final int s2Start;
		private final int s2Len;
		private final int s1InsertPoint;
		private final int s2DeletePoint;

		public BlockImpl2(ChunkSequence s1, ChunkSequence s2, int s1Start, int s1Len, int s2Start, int s2Len, int s1InsertPoint, int s2DeletePoint) {
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
	}
}
