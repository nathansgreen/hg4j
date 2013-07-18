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

import java.util.Arrays;

import org.tmatesoft.hg.core.HgAnnotateCommand;
import org.tmatesoft.hg.core.HgBlameInspector;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.core.HgBlameInspector.RevisionDescriptor;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Produce output like 'hg annotate' does.
 * Expects revisions to come in order from child to parent.
 * Unlike {@link ForwardAnnotateInspector}, can be easily modified to report lines as soon as its origin is detected.
 * 
 * (+) Handles annotate of partial history, at any moment lines with ({@link #knownLines} == <code>false</code> indicate lines
 * that were added prior to any revision already visited. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ReverseAnnotateInspector implements HgBlameInspector, RevisionDescriptor.Recipient {

	// keeps <startSeq1, startSeq2, len> of equal blocks, origin to target, from some previous step
	private RangePairSeq activeEquals;
	// equal blocks of the current iteration, to be recalculated before next step
	// to track line number (current target to ultimate target) mapping 
	private RangePairSeq intermediateEquals = new RangePairSeq();

	private boolean[] knownLines;
	private RevisionDescriptor revisionDescriptor;
	private BlockData lineContent;

	private IntMap<RangePairSeq> mergedRanges = new IntMap<RangePairSeq>(10);
	private IntMap<RangePairSeq> equalRanges = new IntMap<RangePairSeq>(10);
	private boolean activeEqualsComesFromMerge = false;

	private int[] lineRevisions;

	/**
	 * @return desired order of iteration for diff
	 */
	public HgIterateDirection iterateDirection() {
		return HgIterateDirection.NewToOld;
	}

	public void report(int annotateRevIndex, HgAnnotateCommand.Inspector insp, ProgressSupport progress, CancelSupport cancel) throws HgCallbackTargetException, CancelledException {
		LineImpl li = new LineImpl();
		progress.start(lineRevisions.length);
		for (int i = 0; i < lineRevisions.length; i++) {
			byte[] c = lineContent.elementAt(i).asArray();
			li.init(i+1, lineRevisions[i], c);
			insp.next(li);
			progress.worked(1);
			cancel.checkCancelled();
		}
		progress.done();
	}

	public void start(RevisionDescriptor rd) {
		revisionDescriptor = rd;
		if (knownLines == null) {
			lineContent = rd.target();
			knownLines = new boolean[lineContent.elementCount()];
			lineRevisions = new int [lineContent.elementCount()];
			Arrays.fill(lineRevisions, NO_REVISION);
			activeEquals = new RangePairSeq();
			activeEquals.add(0, 0, knownLines.length);
			equalRanges.put(rd.targetChangesetIndex(), activeEquals);
		} else {
			activeEquals = equalRanges.get(rd.targetChangesetIndex());
			if (activeEquals == null) {
				// we didn't see this target revision as origin yet
				// the only way this may happen is that this revision was a merge parent
				activeEquals = mergedRanges.get(rd.targetChangesetIndex());
				activeEqualsComesFromMerge = true;
				if (activeEquals == null) {
					throw new HgInvalidStateException(String.format("Can't find previously visited revision %d (while in %d->%1$d diff)", rd.targetChangesetIndex(), rd.originChangesetIndex()));
				}
			}
		}
	}

	public void done(RevisionDescriptor rd) {
		// update line numbers of the intermediate target to point to ultimate target's line numbers
		RangePairSeq v = intermediateEquals.intersect(activeEquals);
		if (activeEqualsComesFromMerge) {
			mergedRanges.put(rd.originChangesetIndex(), v);
		} else {
			equalRanges.put(rd.originChangesetIndex(), v);
		}
		if (rd.isMerge() && !mergedRanges.containsKey(rd.mergeChangesetIndex())) {
			// seen merge, but no lines were merged from p2.
			// Add empty range to avoid uncertainty when a parent of p2 pops in
			mergedRanges.put(rd.mergeChangesetIndex(), new RangePairSeq());
		}
		intermediateEquals.clear();
		activeEquals = null;
		activeEqualsComesFromMerge = false;
		revisionDescriptor = null;
	}

	public void same(EqualBlock block) {
		intermediateEquals.add(block.originStart(), block.targetStart(), block.length());
	}

	public void added(AddBlock block) {
		RangePairSeq rs = null;
		if (revisionDescriptor.isMerge() && block.originChangesetIndex() == revisionDescriptor.mergeChangesetIndex()) {
			rs = mergedRanges.get(revisionDescriptor.mergeChangesetIndex());
			if (rs == null) {
				mergedRanges.put(revisionDescriptor.mergeChangesetIndex(), rs = new RangePairSeq());
			}
		}
		if (activeEquals.size() == 0) {
			return;
		}
		for (int i = 0, ln = block.firstAddedLine(), x = block.totalAddedLines(); i < x; i++, ln++) {
			int lnInFinal = activeEquals.mapLineIndex(ln);
			if (lnInFinal != -1/* && !knownLines[lnInFinal]*/) {
				if (rs != null) {
					rs.add(block.insertedAt() + i, lnInFinal, 1);
				} else {
					line(lnInFinal, block.targetChangesetIndex());
				}
				knownLines[lnInFinal] = true;
			}
		}
	}

	public void changed(ChangeBlock block) {
		added(block);
	}

	public void deleted(DeleteBlock block) {
	}

	private void line(int lineNumber, int changesetRevIndex) {
		lineRevisions[lineNumber] = changesetRevIndex;
	}
}