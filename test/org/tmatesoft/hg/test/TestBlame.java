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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgIterateDirection;
import org.tmatesoft.hg.internal.FileAnnotation;
import org.tmatesoft.hg.internal.FileAnnotation.LineDescriptor;
import org.tmatesoft.hg.internal.FileAnnotation.LineInspector;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.repo.HgBlameFacility;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgBlameFacility.AddBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.Block;
import org.tmatesoft.hg.repo.HgBlameFacility.BlockData;
import org.tmatesoft.hg.repo.HgBlameFacility.ChangeBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.DeleteBlock;
import org.tmatesoft.hg.repo.HgBlameFacility.EqualBlock;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestBlame {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	
	@Test
	public void testSingleParentBlame() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		final String fname = "src/org/tmatesoft/hg/internal/PatchGenerator.java";
		final int checkChangeset = 539;
		HgDataFile df = repo.getFileNode(fname);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		new HgBlameFacility().annotateSingleRevision(df, checkChangeset, new DiffOutInspector(new PrintStream(bos)));
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
		HgDataFile df = repo.getFileNode(fname);
		OutputParser.Stub op = new OutputParser.Stub();
		ExecHelper eh = new ExecHelper(op, null);

		for (int startChangeset : new int[] { 539, 541 /*, TIP */}) {
			FileAnnotateInspector fa = new FileAnnotateInspector();
			FileAnnotation.annotate(df, startChangeset, fa);
			

			op.reset();
			eh.run("hg", "annotate", "-r", startChangeset == TIP ? "tip" : String.valueOf(startChangeset), fname);
			
			String[] hgAnnotateLines = splitLines(op.result());
			assertTrue("[sanity]", hgAnnotateLines.length > 0);
			assertEquals("Number of lines reported by native annotate and our impl", hgAnnotateLines.length, fa.lineRevisions.length);
	
			for (int i = 0; i < fa.lineRevisions.length; i++) {
				int hgAnnotateRevIndex = Integer.parseInt(hgAnnotateLines[i].substring(0, hgAnnotateLines[i].indexOf(':')));
				errorCollector.assertEquals(String.format("Revision mismatch for line %d", i+1), hgAnnotateRevIndex, fa.lineRevisions[i]);
			}
		}
	}
	
	@Test
	public void testComplexHistoryAnnotate() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate");
		HgDataFile df = repo.getFileNode("file1");
		HgBlameFacility af = new HgBlameFacility();
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DiffOutInspector dump = new DiffOutInspector(new PrintStream(bos));
		af.annotate(df, TIP, dump, HgIterateDirection.OldToNew);
		LinkedList<String> apiResult = new LinkedList<String>(Arrays.asList(splitLines(bos.toString())));
		
		LineGrepOutputParser gp = new LineGrepOutputParser("^@@.+");
		ExecHelper eh = new ExecHelper(gp, repo.getWorkingDir());
		for (int cs : dump.getReportedTargetRevisions()) {
			gp.reset();
			eh.run("hg", "diff", "-c", String.valueOf(cs), "-U", "0", df.getPath().toString());
			for (String expected : splitLines(gp.result())) {
				if (!apiResult.remove(expected)) {
					errorCollector.fail(String.format("Expected diff output '%s' for changes in revision %d", expected, cs));
				}
			}
		}
		errorCollector.assertTrue(String.format("Annotate API reported excessive diff: %s ", apiResult.toString()), apiResult.isEmpty());
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
	
	
	private void aaa() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		final String fname = "src/org/tmatesoft/hg/internal/PatchGenerator.java";
		final int checkChangeset = 539;
		HgDataFile df = repo.getFileNode(fname);
		HgBlameFacility af = new HgBlameFacility();
		DiffOutInspector dump = new DiffOutInspector(System.out);
		System.out.println("541 -> 543");
		af.annotateSingleRevision(df, 543, dump);
		System.out.println("539 -> 541");
		af.annotateSingleRevision(df, 541, dump);
		System.out.println("536 -> 539");
		af.annotateSingleRevision(df, checkChangeset, dump);
		System.out.println("531 -> 536");
		af.annotateSingleRevision(df, 536, dump);
		System.out.println(" -1 -> 531");
		af.annotateSingleRevision(df, 531, dump);
		
		FileAnnotateInspector fai = new FileAnnotateInspector();
		FileAnnotation.annotate(df, 541, fai);
		for (int i = 0; i < fai.lineRevisions.length; i++) {
			System.out.printf("%3d: LINE %d\n", fai.lineRevisions[i], i+1);
		}
	}

	private void bbb() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		final String fname = "src/org/tmatesoft/hg/repo/HgManifest.java";
		final int checkChangeset = 415;
		HgDataFile df = repo.getFileNode(fname);
		HgBlameFacility af = new HgBlameFacility();
		DiffOutInspector dump = new DiffOutInspector(System.out);
//		System.out.println("413 -> 415");
//		af.diff(df, 413, 415, dump);
//		System.out.println("408 -> 415");
//		af.diff(df, 408, 415, dump);
//		System.out.println("Combined (with merge):");
//		dump.needRevisions(true);
//		af.annotateChange(df, checkChangeset, dump);
		dump.needRevisions(true);
		af.annotate(df, checkChangeset, dump, HgIterateDirection.OldToNew);
	}
	
	private void ccc() throws Exception {
		HgRepository repo = new HgLookup().detect("/home/artem/hg/junit-test-repos/test-annotate/");
		HgDataFile df = repo.getFileNode("file1");
		HgBlameFacility af = new HgBlameFacility();
		DiffOutInspector dump = new DiffOutInspector(System.out);
		dump.needRevisions(true);
		af.annotate(df, TIP, dump, HgIterateDirection.OldToNew);
		System.out.println();
		af.annotate(df, TIP, new LineDumpInspector(true), HgIterateDirection.NewToOld);
		System.out.println();
		af.annotate(df, TIP, new LineDumpInspector(false), HgIterateDirection.NewToOld);
		System.out.println();
		FileAnnotateInspector fa = new FileAnnotateInspector();
		FileAnnotation.annotate(df, TIP, fa);
		for (int i = 0; i < fa.lineRevisions.length; i++) {
			System.out.printf("%d: LINE %d\n", fa.lineRevisions[i], i+1);
		}
	}

	public static void main(String[] args) throws Exception {
//		System.out.println(Arrays.equals(new String[0], splitLines("")));
//		System.out.println(Arrays.equals(new String[] { "abc" }, splitLines("abc")));
//		System.out.println(Arrays.equals(new String[] { "a", "bc" }, splitLines("a\nbc")));
//		System.out.println(Arrays.equals(new String[] { "a", "bc" }, splitLines("a\nbc\n")));
		new TestBlame().ccc();
	}

	private static class DiffOutInspector implements HgBlameFacility.BlockInspector {
		private final PrintStream out;
		private boolean dumpRevs;
		private IntVector reportedRevisionPairs = new IntVector();
		
		DiffOutInspector(PrintStream ps) {
			out = ps;
		}
		
		// Note, true makes output incompatible with 'hg diff'
		public void needRevisions(boolean dumpRevs) {
			this.dumpRevs = dumpRevs;
		}
		
		private void printRevs(Block b) {
			if (dumpRevs) {
				out.printf("[%3d -> %3d] ", b.originChangesetIndex(), b.targetChangesetIndex());
			}
			reportedRevisionPairs.add(b.originChangesetIndex(), b.targetChangesetIndex());
		}
		
		int[] getReportedTargetRevisions() {
			LinkedHashSet<Integer> rv = new LinkedHashSet<Integer>();
			for (int i = 1; i < reportedRevisionPairs.size(); i += 2) {
				rv.add(reportedRevisionPairs.get(i));
			}
			int[] x = new int[rv.size()];
			int i = 0;
			for (int v : rv) {
				x[i++] = v;
			}
			return x;
		}
		
		public void same(EqualBlock block) {
			// nothing 
		}
		
		public void deleted(DeleteBlock block) {
			printRevs(block);
			out.printf("@@ -%d,%d +%d,0 @@\n", block.firstRemovedLine() + 1, block.totalRemovedLines(), block.removedAt());
		}
		
		public void changed(ChangeBlock block) {
			printRevs(block);
			out.printf("@@ -%d,%d +%d,%d @@\n", block.firstRemovedLine() + 1, block.totalRemovedLines(), block.firstAddedLine() + 1, block.totalAddedLines());
		}
		
		public void added(AddBlock block) {
			printRevs(block);
			out.printf("@@ -%d,0 +%d,%d @@\n", block.insertedAt(), block.firstAddedLine() + 1, block.totalAddedLines());
		}
	}
	
	public static class LineGrepOutputParser implements OutputParser {
		
		private final Pattern pattern;
		private final StringBuilder result = new StringBuilder();

		public LineGrepOutputParser(String regexp) {
			pattern = Pattern.compile(regexp);
		}
		
		public void reset() {
			result.setLength(0);
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

	private static class FileAnnotateInspector implements LineInspector {
		private int[] lineRevisions;
		
		FileAnnotateInspector() {
		}
		
		public void line(int lineNumber, int changesetRevIndex, LineDescriptor ld) {
			if (lineRevisions == null) {
				lineRevisions = new int [ld.totalLines()];
				Arrays.fill(lineRevisions, NO_REVISION);
			}
			lineRevisions[lineNumber] = changesetRevIndex;
		}
	}

	private static class LineDumpInspector implements HgBlameFacility.BlockInspector {
		
		private final boolean lineByLine;

		public LineDumpInspector(boolean lineByLine) {
			this.lineByLine = lineByLine;
		}

		public void same(EqualBlock block) {
		}

		public void added(AddBlock block) {
			BlockData lines = block.addedLines();
			printBlock(lines, block.targetChangesetIndex(), block.firstAddedLine(), block.totalAddedLines(), "+++");
		}

		public void changed(ChangeBlock block) {
			deleted(block);
			added(block);
		}

		public void deleted(DeleteBlock block) {
			BlockData lines = block.removedLines();
			assert lines.elementCount() == block.totalRemovedLines();
			printBlock(lines, block.originChangesetIndex(), block.firstRemovedLine(), block.totalRemovedLines(), "---");
		}
		
		private void printBlock(BlockData lines, int cset, int first, int length, String marker) {
			assert lines.elementCount() == length;
			if (lineByLine) {
				for (int i = 0, ln = first; i < length; i++, ln++) {
					String line = new String(lines.elementAt(i).asArray());
					System.out.printf("%3d:%3d:%s:%s", cset, ln, marker, line);
				}
			} else {
				String content = new String(lines.asArray());
				System.out.printf("%3d:%s:[%d..%d):\n%s", cset, marker, first, first+length, content);
			}
		}
	}
}
