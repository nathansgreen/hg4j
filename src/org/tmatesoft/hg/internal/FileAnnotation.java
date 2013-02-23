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

import java.util.Formatter;

import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.repo.HgBlameFacility;
import org.tmatesoft.hg.repo.HgInvalidStateException;
import org.tmatesoft.hg.repo.HgBlameFacility.AddBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.BlockData;
import org.tmatesoft.hg.repo.HgBlameFacility.ChangeBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.DeleteBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.EqualBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.RevisionDescriptor;
import org.tmatesoft.hg.repo.HgDataFile;

/**
 * Produce output like 'hg annotate' does
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileAnnotation implements HgBlameFacility.BlockInspector, RevisionDescriptor.Recipient {

	@Experimental(reason="The line-by-line inspector likely to become part of core/command API")
	@Callback
	public interface LineInspector {
		/**
		 * Not necessarily invoked sequentially by line numbers
		 */
		void line(int lineNumber, int changesetRevIndex, BlockData lineContent, LineDescriptor ld);
	}

	public interface LineDescriptor {
		int totalLines();
	}

	/**
	 * Annotate file revision, line by line.
	 */
	public static void annotate(HgDataFile df, int changelogRevisionIndex, LineInspector insp) {
		if (!df.exists()) {
			return;
		}
		FileAnnotation fa = new FileAnnotation(insp);
		HgBlameFacility af = new HgBlameFacility();
		af.annotate(df, changelogRevisionIndex, fa, HgIterateDirection.NewToOld);
	}

	// keeps <startSeq1, startSeq2, len> of equal blocks, origin to target, from some previous step
	private RangeSeq activeEquals;
	// equal blocks of the current iteration, to be recalculated before next step
	// to track line number (current target to ultimate target) mapping 
	private RangeSeq intermediateEquals = new RangeSeq();

	private boolean[] knownLines;
	private final LineInspector delegate;
	private RevisionDescriptor revisionDescriptor;
	private BlockData lineContent;

	private IntMap<RangeSeq> mergedRanges = new IntMap<RangeSeq>(10);
	private IntMap<RangeSeq> equalRanges = new IntMap<RangeSeq>(10);
	private boolean activeEqualsComesFromMerge = false;

	public FileAnnotation(LineInspector lineInspector) {
		delegate = lineInspector;
	}

	public void start(RevisionDescriptor rd) {
		revisionDescriptor = rd;
		if (knownLines == null) {
			lineContent = rd.target();
			knownLines = new boolean[lineContent.elementCount()];
			activeEquals = new RangeSeq();
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
		RangeSeq v = intermediateEquals.intersect(activeEquals);
		if (activeEqualsComesFromMerge) {
			mergedRanges.put(rd.originChangesetIndex(), v);
		} else {
			equalRanges.put(rd.originChangesetIndex(), v);
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
		RangeSeq rs = null;
		if (revisionDescriptor.isMerge() && block.originChangesetIndex() == revisionDescriptor.mergeChangesetIndex()) {
			rs = mergedRanges.get(revisionDescriptor.mergeChangesetIndex());
			if (rs == null) {
				mergedRanges.put(revisionDescriptor.mergeChangesetIndex(), rs = new RangeSeq());
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
					delegate.line(lnInFinal, block.targetChangesetIndex(), lineContent.elementAt(lnInFinal), new LineDescriptorImpl());
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

	private final class LineDescriptorImpl implements LineDescriptor {
		LineDescriptorImpl() {
		}

		public int totalLines() {
			return FileAnnotation.this.knownLines.length;
		}
	}

	private static class RangeSeq {
		// XXX smth like IntSliceVector to access triples (or slices of any size, in fact)
		// with easy indexing, e.g. #get(sliceIndex, indexWithinSlice)
		// and vect.get(7,2) instead of vect.get(7*SIZEOF_SLICE+2)
		private final IntVector ranges = new IntVector(3*10, 3*5);
		private int count;
		
		public void add(int start1, int start2, int length) {
			if (count > 0) {
				int lastIndex = 3 * (count-1);
				int lastS1 = ranges.get(lastIndex);
				int lastS2 = ranges.get(lastIndex + 1);
				int lastLen = ranges.get(lastIndex + 2);
				if (start1 == lastS1 + lastLen && start2 == lastS2 + lastLen) {
					// new range continues the previous one - just increase the length
					ranges.set(lastIndex + 2, lastLen + length);
					return;
				}
			}
			ranges.add(start1, start2, length);
			count++;
		}
		
		public void clear() {
			ranges.clear();
			count = 0;
		}

		public int size() {
			return count;
		}

		public int mapLineIndex(int ln) {
			for (int i = 0; i < ranges.size(); i += 3) {
				int s1 = ranges.get(i);
				if (s1 > ln) {
					return -1;
				}
				int l = ranges.get(i+2);
				if (s1 + l > ln) {
					int s2 = ranges.get(i + 1);
					return s2 + (ln - s1);
				}
			}
			return -1;
		}
		
		public RangeSeq intersect(RangeSeq target) {
			RangeSeq v = new RangeSeq();
			for (int i = 0; i < ranges.size(); i += 3) {
				int originLine = ranges.get(i);
				int targetLine = ranges.get(i + 1);
				int length = ranges.get(i + 2);
				int startTargetLine = -1, startOriginLine = -1, c = 0;
				for (int j = 0; j < length; j++) {
					int lnInFinal = target.mapLineIndex(targetLine + j);
					if (lnInFinal == -1 || (startTargetLine != -1 && lnInFinal != startTargetLine + c)) {
						// the line is not among "same" in ultimate origin
						// or belongs to another/next "same" chunk 
						if (startOriginLine == -1) {
							continue;
						}
						v.add(startOriginLine, startTargetLine, c);
						c = 0;
						startOriginLine = startTargetLine = -1;
						// fall-through to check if it's not complete miss but a next chunk
					}
					if (lnInFinal != -1) {
						if (startOriginLine == -1) {
							startOriginLine = originLine + j;
							startTargetLine = lnInFinal;
							c = 1;
						} else {
							// lnInFinal != startTargetLine + s is covered above
							assert lnInFinal == startTargetLine + c;
							c++;
						}
					}
				}
				if (startOriginLine != -1) {
					assert c > 0;
					v.add(startOriginLine, startTargetLine, c);
				}
			}
			return v;
		}
		
		@SuppressWarnings("unused")
		public CharSequence dump() {
			StringBuilder sb = new StringBuilder();
			Formatter f = new Formatter(sb);
			for (int i = 0; i < ranges.size(); i += 3) {
				int s1 = ranges.get(i);
				int s2 = ranges.get(i + 1);
				int len = ranges.get(i + 2);
				f.format("[%d..%d) == [%d..%d);  ", s1, s1 + len, s2, s2 + len);
			}
			return sb;
		}
		
		@Override
		public String toString() {
			return String.format("RangeSeq[%d]:%s", count, dump());
		}
	}
}