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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.internal.AnnotateFacility;
import org.tmatesoft.hg.internal.AnnotateFacility.AddBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.Block;
import org.tmatesoft.hg.internal.AnnotateFacility.ChangeBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.DeleteBlock;
import org.tmatesoft.hg.internal.IntMap;
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
		new AnnotateFacility().annotate(df, checkChangeset, new DiffOutInspector(new PrintStream(bos)));
		LineGrepOutputParser gp = new LineGrepOutputParser("^@@.+");
		ExecHelper eh = new ExecHelper(gp, null);
		eh.run("hg", "diff", "-c", String.valueOf(checkChangeset), "-U", "0", fname);
		//
		String[] apiResult = splitLines(bos.toString());
		String[] expected = splitLines(gp.result());
		Assert.assertArrayEquals(expected, apiResult);
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
		System.out.println(Arrays.equals(new String[0], splitLines("")));
		System.out.println(Arrays.equals(new String[] { "abc" }, splitLines("abc")));
		new TestBlame().testSingleParentBlame();
	}

	static class DiffOutInspector implements AnnotateFacility.Inspector {
		private final PrintStream out;
		
		DiffOutInspector(PrintStream ps) {
			out = ps;
		}
		
		public void same(Block block) {
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
}
