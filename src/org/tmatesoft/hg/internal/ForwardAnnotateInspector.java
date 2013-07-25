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

import org.tmatesoft.hg.core.HgAnnotateCommand.Inspector;
import org.tmatesoft.hg.core.HgBlameInspector;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Annotate file history iterating from parents to children
 * 
 * At the moment, doesn't handle start from any revision but 0
 * 
 * (+) May report annotate for any revision (with actual file change) in the visited range.
 * 
 * @see ReverseAnnotateInspector
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ForwardAnnotateInspector implements HgBlameInspector, HgBlameInspector.RevisionDescriptor.Recipient {
	final IntMap<IntSliceSeq> all = new IntMap<IntSliceSeq>(100);
	// revision->map(lineNumber->lineContent)
	private final IntMap<IntMap<byte[]>> lineContent = new IntMap<IntMap<byte[]>>(100);
	private IntSliceSeq current;
	private RevisionDescriptor revDescriptor;

	/**
	 * @return desired order of iteration for diff
	 */
	public HgIterateDirection iterateDirection() {
		return HgIterateDirection.OldToNew;
	}

	public void report(int revision, Inspector insp, ProgressSupport progress, CancelSupport cancel) throws HgCallbackTargetException, CancelledException {
		int totalLines = 0;
		if (!all.containsKey(revision)) {
			throw new IllegalArgumentException(String.format("Revision %d has not been visited", revision));
		}
		for (IntTuple t : all.get(revision)) {
			totalLines += t.at(0);
		}
		progress.start(totalLines);
		LineImpl li = new LineImpl();
		int line = 1;
		for (IntTuple t : all.get(revision)) {
			IntMap<byte[]> revLines = lineContent.get(t.at(1));
			for (int i = 0, x = t.at(0); i < x; i++) {
				final int lineInRev = t.at(2) + i;
				final byte[] lc = revLines.get(lineInRev);
				li.init(line++, lineInRev+1, t.at(1), lc);
				insp.next(li);
				progress.worked(1);
				cancel.checkCancelled();
			}
		}
		progress.done();
	}

	public void start(RevisionDescriptor rd) throws HgCallbackTargetException {
		all.put(rd.targetChangesetIndex(), current = new IntSliceSeq(3));
		revDescriptor = rd;
	}

	public void done(RevisionDescriptor rd) throws HgCallbackTargetException {
		revDescriptor = null;
	}

	public void same(EqualBlock block) throws HgCallbackTargetException {
		copyBlock(block.originChangesetIndex(), block.originStart(), block.length());
	}

	public void added(AddBlock block) throws HgCallbackTargetException {
		if (revDescriptor.isMerge() && block.originChangesetIndex() == revDescriptor.mergeChangesetIndex()) {
			copyBlock(block.originChangesetIndex(), block.insertedAt(), block.totalAddedLines());
			return;
		}
		BlockData addedLines = block.addedLines();
		IntMap<byte[]> revLines = lineContent.get(block.targetChangesetIndex());
		if (revLines == null) {
			lineContent.put(block.targetChangesetIndex(), revLines = new IntMap<byte[]>(block.totalAddedLines()));
		}
		for (int i = 0; i < block.totalAddedLines(); i++) {
			revLines.put(block.firstAddedLine() + i, addedLines.elementAt(i).asArray());
		}
		current.add(block.totalAddedLines(), block.targetChangesetIndex(), block.firstAddedLine());
	}

	public void changed(ChangeBlock block) throws HgCallbackTargetException {
		added(block);
	}

	public void deleted(DeleteBlock block) throws HgCallbackTargetException {
	}
	
	private void copyBlock(int originChangesetIndex, int blockStart, int length) {
		IntSliceSeq origin = all.get(originChangesetIndex);
		assert origin != null; // shall visit parents before came to this child
		int originPos = 0;
		int targetBlockLen = length;
		for (IntTuple t : origin) {
			int originBlockLen = t.at(0);
			int originBlockEnd = originPos + originBlockLen;
			if (originBlockEnd > blockStart) {
				// part of origin block from blockStart up to originBlockEnd, but not more
				// than size of the block (when blockStart is out of block start, i.e. < originPos)
				int originBlockOverlap = Math.min(originBlockLen, originBlockEnd - blockStart);
				assert originBlockOverlap > 0;
				// eat as much as there's left in the block being copied
				int originBlockConsumed = Math.min(originBlockOverlap, targetBlockLen);
				int originBlockLine = t.at(2);
				if (originPos < blockStart) {
					originBlockLine += originBlockLen-originBlockOverlap;
				}
				// copy fragment of original block;
				current.add(originBlockConsumed, t.at(1), originBlockLine);
				targetBlockLen -= originBlockConsumed;
				if (targetBlockLen == 0) {
					break;
				}
			}
			originPos += originBlockLen;
		}
	}
}