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
package org.tmatesoft.hg.test;

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.internal.AnnotateFacility;
import org.tmatesoft.hg.internal.AnnotateFacility.AddBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.ChangeBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.DeleteBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.EqualBlock;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestBlame {

	
	@Test
	public void testSingleParentBlame() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		final String fname = "src/org/tmatesoft/hg/internal/PatchGenerator.java";
		final int checkChangeset = 539;
		HgDataFile df = repo.getFileNode(fname);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		new AnnotateFacility().annotateChange(df, checkChangeset, new DiffOutInspector(new PrintStream(bos)));
		LineGrepOutputParser gp = new LineGrepOutputParser("^@@.+");
		ExecHelper eh = new ExecHelper(gp, null);
		eh.run("hg", "diff", "-c", String.valueOf(checkChangeset), "-U", "0", fname);
		//
		String[] apiResult = splitLines(bos.toString());
		String[] expected = splitLines(gp.result());
		Assert.assertArrayEquals(expected, apiResult);
	}
	
	@Test
	public void testFileAnnotate() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		final String fname = "src/org/tmatesoft/hg/internal/PatchGenerator.java";
		final int checkChangeset = 539;
		HgDataFile df = repo.getFileNode(fname);
		AnnotateFacility af = new AnnotateFacility();
		System.out.println("536 -> 539");
		af.annotateChange(df, checkChangeset, new DiffOutInspector(System.out));
		System.out.println("531 -> 536");
		af.annotateChange(df, 536, new DiffOutInspector(System.out));
		System.out.println(" -1 -> 531");
		af.annotateChange(df, 531, new DiffOutInspector(System.out));
		
		FileAnnotation fa = new FileAnnotation();
		af.annotateChange(df, checkChangeset, fa);
		af.annotateChange(df, 536, fa);
		af.annotateChange(df, 531, fa);
		for (int i = 0; i < fa.lineRevisions.length; i++) {
			System.out.printf("%3d: %d\n", fa.lineRevisions[i], i+1);
		}
	}
	
	private static String[] splitLines(CharSequence seq) {
		int lineCount = 0;
		for (int i = 0, x = seq.length(); i < x; i++) {
			if (seq.charAt(i) == '\n') {
				lineCount++;
			}
		}
		if (seq.length() > 0 && seq.charAt(seq.length()-1) != '\n') {
			lineCount++;
		}
		String[] rv = new String[lineCount];
		int lineStart = 0, lineEnd = 0, ix = 0;
		do {
			while (lineEnd < seq.length() && seq.charAt(lineEnd) != '\n') lineEnd++;
			if (lineEnd == lineStart) {
				continue;
			}
			CharSequence line = seq.subSequence(lineStart, lineEnd);
			rv[ix++] = line.toString();
			lineStart = ++lineEnd;
		} while (lineStart < seq.length());
		assert ix == lineCount;
		return rv;
	}
	
	private void leftovers() {
		IntMap<String> linesOld = new IntMap<String>(100), linesNew = new IntMap<String>(100);
		System.out.println("Changes to old revision:");
		for (int i = linesOld.firstKey(), x = linesOld.lastKey(); i < x; i++) {
			if (linesOld.containsKey(i)) {
				System.out.println(linesOld.get(i));
			}
		}

		System.out.println("Changes in the new revision:");
		for (int i = linesNew.firstKey(), x = linesNew.lastKey(); i < x; i++) {
			if (linesNew.containsKey(i)) {
				System.out.println(linesNew.get(i));
			}
		}
	}
	
	public static void main(String[] args) throws Exception {
//		System.out.println(Arrays.equals(new String[0], splitLines("")));
//		System.out.println(Arrays.equals(new String[] { "abc" }, splitLines("abc")));
//		System.out.println(Arrays.equals(new String[] { "a", "bc" }, splitLines("a\nbc")));
//		System.out.println(Arrays.equals(new String[] { "a", "bc" }, splitLines("a\nbc\n")));
		new TestBlame().testFileAnnotate();
	}

	static class DiffOutInspector implements AnnotateFacility.Inspector {
		private final PrintStream out;
		
		DiffOutInspector(PrintStream ps) {
			out = ps;
		}
		
		public void same(EqualBlock block) {
			// nothing 
		}
		
		public void deleted(DeleteBlock block) {
			out.printf("@@ -%d,%d +%d,0 @@\n", block.firstRemovedLine() + 1, block.totalRemovedLines(), block.removedAt());
//			String[] lines = block.removedLines();
//			assert lines.length == block.totalRemovedLines();
//			for (int i = 0, ln = block.firstRemovedLine(); i < lines.length; i++, ln++) {
//				linesOld.put(ln, String.format("%3d:---:%s", ln, lines[i]));
//			}
		}
		
		public void changed(ChangeBlock block) {
//			deleted(block);
//			added(block);
			out.printf("@@ -%d,%d +%d,%d @@\n", block.firstRemovedLine() + 1, block.totalRemovedLines(), block.firstAddedLine() + 1, block.totalAddedLines());
		}
		
		public void added(AddBlock block) {
			out.printf("@@ -%d,0 +%d,%d @@\n", block.insertedAt(), block.firstAddedLine() + 1, block.totalAddedLines());
//			String[] addedLines = block.addedLines();
//			assert addedLines.length == block.totalAddedLines();
//			for (int i = 0, ln = block.firstAddedLine(), x = addedLines.length; i < x; i++, ln++) {
//				linesNew.put(ln, String.format("%3d:+++:%s", ln, addedLines[i]));
//			}
		}
	}
	
	public static class LineGrepOutputParser implements OutputParser {
		
		private final Pattern pattern;
		private final StringBuilder result = new StringBuilder();

		public LineGrepOutputParser(String regexp) {
			pattern = Pattern.compile(regexp);
		}
		
		public CharSequence result() {
			return result;
		}

		public void parse(CharSequence seq) {
			int lineStart = 0, lineEnd = 0;
			do {
				while (lineEnd < seq.length() && seq.charAt(lineEnd) != '\n') lineEnd++;
				if (lineEnd == lineStart) {
					continue;
				}
				CharSequence line = seq.subSequence(lineStart, lineEnd);
				if (pattern.matcher(line).matches()) {
					result.append(line);
					result.append('\n');
				}
				lineStart = ++lineEnd;
			} while (lineStart < seq.length());
		}
	}

	private static class FileAnnotation implements AnnotateFacility.InspectorEx {
		private int[] lineRevisions;
		private LinkedList<DeleteBlock> deleted = new LinkedList<DeleteBlock>();
		private LinkedList<DeleteBlock> newDeleted = new LinkedList<DeleteBlock>();
		// keeps <startSeq1, startSeq2, len> of equal blocks
		// XXX smth like IntSliceVector to access triples (or slices of any size, in fact)
		// with easy indexing, e.g. #get(sliceIndex, indexWithinSlice)
		// and vect.get(7,2) instead of vect.get(7*SIZEOF_SLICE+2)
		private IntVector identical = new IntVector(20*3, 2*3);
		private IntVector newIdentical = new IntVector(20*3, 2*3);
		
		public FileAnnotation() {
		}
		
		public void start(int originLineCount, int targetLineCount) {
			if (lineRevisions == null) {
				lineRevisions = new int [targetLineCount];
				Arrays.fill(lineRevisions, NO_REVISION);
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
				if (lnInFinal != -1 && historyUnknown(lnInFinal)) {
					lineRevisions[lnInFinal] = block.targetChangesetIndex();
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
		
		private boolean historyUnknown(int lineNumber) {
			return lineRevisions[lineNumber] == NO_REVISION;
		}

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
					assert false;
					return -1;
				}
				// ln >= b.originStart
				final int length = identical.get(i+2);
				if (originStart + length > ln) {
					int targetStart = identical.get(i+1);
					return targetStart + (ln - originStart);
				}
			}
			assert false;
			return -1;
		}
	}
}
