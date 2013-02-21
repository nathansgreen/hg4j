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

import java.util.LinkedList;

import org.tmatesoft.hg.internal.AnnotateFacility.AddBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.BlockData;
import org.tmatesoft.hg.internal.AnnotateFacility.ChangeBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.DeleteBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.EqualBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.LineInspector;


/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FileAnnotation implements AnnotateFacility.BlockInspectorEx {
		// blocks deleted in the target, as reported at the previous step
		private LinkedList<DeleteBlock> deleted = new LinkedList<DeleteBlock>();
		// blocks deleted in the origin, to become deletions in target at the next step
		private LinkedList<DeleteBlock> newDeleted = new LinkedList<DeleteBlock>();
		// keeps <startSeq1, startSeq2, len> of equal blocks, origin to target, from previous step
		// XXX smth like IntSliceVector to access triples (or slices of any size, in fact)
		// with easy indexing, e.g. #get(sliceIndex, indexWithinSlice)
		// and vect.get(7,2) instead of vect.get(7*SIZEOF_SLICE+2)
		private IntVector identical = new IntVector(20*3, 2*3);
		// equal blocks of the current iteration, to be recalculated before next step
		// to track line number (current target to ultimate target) mapping 
		private IntVector newIdentical = new IntVector(20*3, 2*3);
		
		private boolean[] knownLines;
		private final LineInspector delegate;
		
		public FileAnnotation(AnnotateFacility.LineInspector lineInspector) {
			delegate = lineInspector;
		}
		
		public void start(BlockData originContent, BlockData targetContent) {
			if (knownLines == null) {
				knownLines = new boolean[targetContent.elementCount()];
			}
		}

//		private static void ppp(IntVector v) {
//			for (int i = 0; i < v.size(); i+= 3) {
//				int len = v.get(i+2);
//				System.out.printf("[%d..%d) == [%d..%d);  ", v.get(i), v.get(i) + len, v.get(i+1), v.get(i+1) + len);
//			}
//			System.out.println();
//		}

		public void done() {
			if (identical.size() > 0) {
				// update line numbers of the intermediate target to point to ultimate target's line numbers
				IntVector v = new IntVector(identical.size(), 2*3);
				for (int i = 0; i < newIdentical.size(); i+= 3) {
					int originLine = newIdentical.get(i);
					int targetLine = newIdentical.get(i+1);
					int length = newIdentical.get(i+2);
					int startTargetLine = -1, startOriginLine = -1, c = 0;
					for (int j = 0; j < length; j++) {
						int lnInFinal = mapLineIndex(targetLine + j);
						if (lnInFinal == -1 || (startTargetLine != -1 && lnInFinal != startTargetLine + c)) {
							// the line is not among "same" in ultimate origin
							// or belongs to another/next "same" chunk 
							if (startOriginLine == -1) {
								continue;
							}
							v.add(startOriginLine);
							v.add(startTargetLine);
							v.add(c);
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
								assert lnInFinal == startTargetLine + c;
								c++;
							}
						}
					}
					if (startOriginLine != -1) {
						assert c > 0;
						v.add(startOriginLine);
						v.add(startTargetLine);
						v.add(c);
					}
				}
				newIdentical.clear();
				identical = v;
			} else {
				IntVector li = newIdentical;
				newIdentical = identical;
				identical = li;
			}
			LinkedList<DeleteBlock> ld = newDeleted;
			deleted.clear();
			newDeleted = deleted;
			deleted = ld;
		}
		
		public void same(EqualBlock block) {
			newIdentical.add(block.originStart());
			newIdentical.add(block.targetStart());
			newIdentical.add(block.length());
		}

		public void added(AddBlock block) {
			for (int i = 0, ln = block.firstAddedLine(), x = block.totalAddedLines(); i < x; i++, ln++) {
				int lnInFinal = mapLineIndex(ln);
				if (lnInFinal != -1 && !knownLines[lnInFinal]) {
					delegate.line(lnInFinal, block.targetChangesetIndex(), new LineDescriptor());
					knownLines[lnInFinal] = true;
				}
			}
		}
		
		public void changed(ChangeBlock block) {
			deleted(block);
			added(block);
		}

		public void deleted(DeleteBlock block) {
			newDeleted.add(block);
		}
		
		// line - index in the target
		private boolean isDeleted(int line) {
			for (DeleteBlock b : deleted) {
				if (b.firstRemovedLine() > line) {
					break;
				}
				// line >= b.firstRemovedLine
				if (b.firstRemovedLine() + b.totalRemovedLines() > line) {
					return true;
				}
			}
			return false;
		}

		// map target lines to the lines of the revision being annotated (the one that came first)
		private int mapLineIndex(int ln) {
			if (isDeleted(ln)) {
				return -1;
			}
			if (identical.isEmpty()) {
				return ln;
			}
			for (int i = 0; i < identical.size(); i += 3) {
				final int originStart = identical.get(i);
				if (originStart > ln) {
//					assert false;
					return -1;
				}
				// ln >= b.originStart
				final int length = identical.get(i+2);
				if (originStart + length > ln) {
					int targetStart = identical.get(i+1);
					return targetStart + (ln - originStart);
				}
			}
//			assert false;
			return -1;
		}
		
		private final class LineDescriptor implements AnnotateFacility.LineDescriptor {
			LineDescriptor() {
			}
			
			public int totalLines() {
				return FileAnnotation.this.knownLines.length;
			}
		}
	}