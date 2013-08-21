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
import static org.tmatesoft.hg.core.HgIterateDirection.NewToOld;
import static org.tmatesoft.hg.core.HgIterateDirection.OldToNew;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgAnnotateCommand;
import org.tmatesoft.hg.core.HgAnnotateCommand.LineInfo;
import org.tmatesoft.hg.core.HgBlameInspector;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgDiffCommand;
import org.tmatesoft.hg.core.HgRepoFacade;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.internal.diff.ForwardAnnotateInspector;
import org.tmatesoft.hg.internal.diff.ReverseAnnotateInspector;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;

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
		final int checkChangeset = repo.getChangelog().getRevisionIndex(Nodeid.fromAscii("946b131962521f9199e1fedbdc2487d3aaef5e46")); // 539
		HgDataFile df = repo.getFileNode(fname);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		HgDiffCommand diffCmd = new HgDiffCommand(repo);
		diffCmd.file(df).changeset(checkChangeset);
		diffCmd.executeParentsAnnotate(new DiffOutInspector(new PrintStream(bos)));
		LineGrepOutputParser gp = new LineGrepOutputParser("^@@.+");
		ExecHelper eh = new ExecHelper(gp, null);
		eh.run("hg", "diff", "-c", String.valueOf(checkChangeset), "-U", "0", fname);
		//
		String[] apiResult = splitLines(bos.toString());
		String[] expected = splitLines(gp.result());
		Assert.assertArrayEquals(expected, apiResult);
	}
	
	@Test
	public void testFileLineAnnotate1() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		final String fname = "src/org/tmatesoft/hg/internal/PatchGenerator.java";
		HgDataFile df = repo.getFileNode(fname);
		AnnotateRunner ar = new AnnotateRunner(df.getPath(), null);

		final HgDiffCommand diffCmd = new HgDiffCommand(repo);
		diffCmd.file(df).order(NewToOld);
		final HgChangelog clog = repo.getChangelog();
		final int[] toTest = new int[] { 
			clog.getRevisionIndex(Nodeid.fromAscii("946b131962521f9199e1fedbdc2487d3aaef5e46")), // 539
			clog.getRevisionIndex(Nodeid.fromAscii("1e95f48d9886abe79b9711ab371bc877ca5e773e")), // 541 
			/*, TIP */};
		for (int cs : toTest) {
			ar.run(cs, false);
			diffCmd.range(0, cs);
			final ReverseAnnotateInspector insp = new ReverseAnnotateInspector();
			diffCmd.executeAnnotate(insp);
			AnnotateInspector fa = new AnnotateInspector().fill(cs, insp);
			doAnnotateLineCheck(cs, ar, fa);
		}
	}
	
	@Test
	public void testFileLineAnnotate2() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate");
		HgDataFile df = repo.getFileNode("file1");
		AnnotateRunner ar = new AnnotateRunner(df.getPath(), repo.getWorkingDir());

		final HgDiffCommand diffCmd = new HgDiffCommand(repo).file(df).order(NewToOld);
		for (int cs : new int[] { 4, 6 /*, 8 see below*/, TIP}) {
			ar.run(cs, false);
			diffCmd.range(0, cs);
			final ReverseAnnotateInspector insp = new ReverseAnnotateInspector();
			diffCmd.executeAnnotate(insp);
			AnnotateInspector fa = new AnnotateInspector().fill(cs, insp);
			doAnnotateLineCheck(cs, ar, fa);
		}
		/*`hg annotate -r 8` and HgBlameFacility give different result
		 * for "r0, line 5" line, which was deleted in rev2 and restored back in
		 * rev4 (both in default branch), while branch with r3 and r6 kept the line intact.
		 * HgBlame reports rev4 for the line, `hg annotate` gives original, rev0.
		 * However `hg annotate -r 4` shows rev4 for the line, too. The aforementioned rev0 for 
		 * the merge rev8 results from the iteration order and is implementation specific 
		 * (i.e. one can't tell which one is right). Mercurial walks from parents to children,
		 * and traces equal lines, while HgBlameFacility walks from child to parents and records 
		 * changes (additions). Seems it processes branch with rev3 and rev6 first 
		 * (printout in context.py, annotate and annotate.pair reveals that), and the line 0_5
		 * comes as unchanged through this branch, and later processing rev2 and rev4 doesn't 
		 * change that. 
		 */
	}
	
	@Test
	public void testComplexHistoryAnnotate() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate");
		HgDataFile df = repo.getFileNode("file1");
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		DiffOutInspector dump = new DiffOutInspector(new PrintStream(bos));
		HgDiffCommand diffCmd = new HgDiffCommand(repo);
		diffCmd.file(df).range(0, TIP).order(OldToNew);
		diffCmd.executeAnnotate(dump);
		LinkedList<String> apiResult = new LinkedList<String>(Arrays.asList(splitLines(bos.toString())));
		
		/*
		 * FIXME this is an ugly hack to deal with the way `hg diff -c <mergeRev>` describes the change
		 * and our merge handling approach. For merged revision m, and lines changed both in p1 and p2
		 * we report lines from p2 as pure additions, regardless of intersecting p1 changes (which
		 * are reported as deletions, if no sufficient changed lines in m found)
		 * So, here we try to combine deletion that follows a change (based on identical insertionPoint)
		 * into a single change
		 * To fix, need to find better approach to find out reference info (i.e. `hg diff -c` is flawed in this case,
		 * as it uses first parent only).
		 */
		Pattern fix = Pattern.compile("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@");
		int v1, v2, v3, v4;
		v1 = v2 = v3 = v4 = -1;
		for (ListIterator<String> it = apiResult.listIterator(); it.hasNext();) {
			String n = it.next();
			Matcher m = fix.matcher(n);
			if (m.find()) {
				int d1 = Integer.parseInt(m.group(1));
				int d2 = Integer.parseInt(m.group(2));
				int d3 = Integer.parseInt(m.group(3));
				int d4 = Integer.parseInt(m.group(4));
				if (v1 == d1 && d4 == 0) {
					it.previous(); // shift to current element
					it.previous(); // to real previous
					it.remove();
					it.next();
					it.set(String.format("@@ -%d,%d +%d,%d @@", v1, v2+d2, v3, v4));
				}
				v1 = d1;
				v2 = d2;
				v3 = d3;
				v4 = d4;
			}
		}
		
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

	
	@Test
	public void testPartialHistoryFollow() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate2");
		HgDataFile df = repo.getFileNode("file1b.txt");
		// rev3: file1 -> file1a,  rev7: file1a -> file1b, tip: rev10
		DiffOutInspector insp = new DiffOutInspector(new PrintStream(new OutputStream() {
			@Override
			public void write(int b) throws IOException {
				// NULL OutputStream
			}
		}));
		// rev6 changes rev4, rev4 changes rev3. Plus, anything changed 
		// earlier than rev2 shall be reported as new from change3
		int[] change_2_8_new2old = new int[] {4, 6, 3, 4, -1, 3}; 
		int[] change_2_8_old2new = new int[] {-1, 3, 3, 4, 4, 6 };
		final HgDiffCommand cmd = new HgDiffCommand(repo);
		cmd.file(df);
		cmd.range(2, 8).order(NewToOld);
		cmd.executeAnnotate(insp);
		Assert.assertArrayEquals(change_2_8_new2old, insp.getReportedRevisionPairs());
		insp.reset();
		cmd.order(OldToNew).executeAnnotate(insp);
		Assert.assertArrayEquals(change_2_8_old2new, insp.getReportedRevisionPairs());
		// same as 2 to 8, with addition of rev9 changes rev7  (rev6 to rev7 didn't change content, only name)
		int[] change_3_9_new2old = new int[] {7, 9, 4, 6, 3, 4, -1, 3 }; 
		int[] change_3_9_old2new = new int[] {-1, 3, 3, 4, 4, 6, 7, 9 };
		insp.reset();
		cmd.range(3, 9).order(NewToOld).executeAnnotate(insp);
		Assert.assertArrayEquals(change_3_9_new2old, insp.getReportedRevisionPairs());
		insp.reset();
		cmd.order(OldToNew).executeAnnotate(insp);
		Assert.assertArrayEquals(change_3_9_old2new, insp.getReportedRevisionPairs());
	}

	@Test
	public void testAnnotateCmdFollowNoFollow() throws Exception {
		HgRepoFacade hgRepoFacade = new HgRepoFacade();
		HgRepository repo = Configuration.get().find("test-annotate2");
		hgRepoFacade.init(repo);
		HgAnnotateCommand cmd = hgRepoFacade.createAnnotateCommand();
		final Path fname = Path.create("file1b.txt");
		final int changeset = TIP;
		AnnotateInspector ai = new AnnotateInspector();

		cmd.changeset(changeset);
		// follow
		cmd.file(fname);
		cmd.execute(ai);
		AnnotateRunner ar = new AnnotateRunner(fname, repo.getWorkingDir());
		ar.run(changeset, true);
		doAnnotateLineCheck(changeset, ar, ai);
		
		// no follow
		cmd.file(fname, false);
		ai = new AnnotateInspector();
		cmd.execute(ai);
		ar.run(changeset, false);
		doAnnotateLineCheck(changeset, ar, ai);
	}
	
	@Test
	public void testDiffTwoRevisions() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate");
		HgDataFile df = repo.getFileNode("file1");
		LineGrepOutputParser gp = new LineGrepOutputParser("^@@.+");
		ExecHelper eh = new ExecHelper(gp, repo.getWorkingDir());
		int[] toTest = { 3, 4, 5 }; // p1 ancestry line, p2 ancestry line, not in ancestry line
		final HgDiffCommand diffCmd = new HgDiffCommand(repo).file(df);
		for (int cs : toTest) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			diffCmd.range(cs, 8).executeDiff(new DiffOutInspector(new PrintStream(bos)));
			eh.run("hg", "diff", "-r", String.valueOf(cs), "-r", "8", "-U", "0", df.getPath().toString());
			//
			String[] apiResult = splitLines(bos.toString());
			String[] expected = splitLines(gp.result());
			Assert.assertArrayEquals("diff -r " + cs + "-r 8", expected, apiResult);
			gp.reset();
		}
	}
	
	/**
	 * Make sure boundary values are ok (down to BlameHelper#prepare and FileHistory) 
	 */
	@Test
	public void testAnnotateFirstFileRev() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate");
		HgDataFile df = repo.getFileNode("file1");
		LineGrepOutputParser gp = new LineGrepOutputParser("^@@.+");
		ExecHelper eh = new ExecHelper(gp, repo.getWorkingDir());
		eh.run("hg", "diff", "-c", "0", "-U", "0", df.getPath().toString());
		//
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		HgDiffCommand diffCmd = new HgDiffCommand(repo).file(df);
		diffCmd.changeset(0).executeParentsAnnotate(new DiffOutInspector(new PrintStream(bos)));
		//
		String[] apiResult = splitLines(bos.toString());
		String[] expected = splitLines(gp.result());
		Assert.assertArrayEquals(expected, apiResult);
	}
	
	@Test
	public void testAnnotateMergeMapViaBase() throws Exception {
		HgRepository repo = Configuration.get().find("test-annotate3");
		HgDataFile df1 = repo.getFileNode("file1");
		HgDataFile df4 = repo.getFileNode("file4");
		HgDataFile df5 = repo.getFileNode("file5");
		assertTrue("[sanity]", df1.exists() && df4.exists());
		// hg annotate handles merge in its own way, here we check 
		// how map(diff(p1->base->p2)) merge strategy works
		final String file1AnnotateResult = "3:1:1\n3:2:2x\n3:3:3y\n2:4:z\n0:1:1\n1:2:2x\n4:3:3y\n";
		final String file4AnnotateResult = "3:1:1\n1:2:2x\n4:3:3y\n2:4:z\n0:1:1\n3:6:2x\n3:7:3y\n";
		final String file5AnnotateResult = "0:1:1\n1:2:2x\n4:3:3y\n2:4:z\n5:5:1\n5:6:2x\n5:7:3y\n";
		HgAnnotateCommand cmd = new HgAnnotateCommand(repo);
		cmd.changeset(5);
		AnnotateInspector insp = new AnnotateInspector();
		// file1
		cmd.file(df1, false).execute(insp);
		doAnnotateLineCheck(5, splitLines(file1AnnotateResult), insp);
		// file4
		cmd.file(df4, false).execute(insp = new AnnotateInspector());
		doAnnotateLineCheck(5, splitLines(file4AnnotateResult), insp);
		// file5
		cmd.file(df5, false).execute(insp = new AnnotateInspector());
		doAnnotateLineCheck(5, splitLines(file5AnnotateResult), insp);
}

	// TODO HgWorkingCopyStatusCollector (and HgStatusCollector), with their ancestors (rev 59/69) have examples
	// of *incorrect* assignment of common lines (like "}") - our impl doesn't process common lines in any special way
	// while original diff lib does. Would be nice to behave as close to original, as possible.
	
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
	
	private void doAnnotateLineCheck(int cs, AnnotateRunner ar, AnnotateInspector hg4jResult) {
		String[] hgAnnotateLines = ar.getLines();
		assertTrue("[sanity]", hgAnnotateLines.length > 0);
		assertEquals("Number of lines reported by native annotate and our impl", hgAnnotateLines.length, hg4jResult.getLineCount());
		doAnnotateLineCheck(cs, hgAnnotateLines, hg4jResult);
	}

	private void doAnnotateLineCheck(int cs, String[] expectedAnnotateLines, AnnotateInspector hg4jResult) { 
		for (int i = 0; i < expectedAnnotateLines.length; i++) {
			String[] hgLine = expectedAnnotateLines[i].split(":");
			assertTrue(expectedAnnotateLines[i], hgLine.length >= 3);
			int hgAnnotateRevIndex = Integer.parseInt(hgLine[0].trim());
			int hgFirstAppLine = Integer.parseInt(hgLine[1].trim());
			String hgLineText = expectedAnnotateLines[i].substring(hgLine[0].length() + hgLine[1].length() + 2).trim(); 
			errorCollector.assertEquals(String.format("Revision mismatch for line %d (annotating rev: %d)", i+1, cs), hgAnnotateRevIndex, hg4jResult.getChangeset(i));
			errorCollector.assertEquals("Line text", hgLineText, hg4jResult.getLine(i).trim());
			errorCollector.assertEquals("Line in origin", hgFirstAppLine, hg4jResult.getOriginLine(i));
		}
	}
	
	private void ddd() throws Throwable {
//		HgRepository repo = new HgLookup().detect("/home/artem/hg/blame-merge/");
		HgRepository repo = new HgLookup().detect("/home/artem/hg/junit-test-repos/test-annotate3/");
		final DiffOutInspector insp = new DiffOutInspector(System.out);
		insp.needRevisions(true);
		new HgDiffCommand(repo).file(Path.create("file1")).executeParentsAnnotate(insp);
	}

	public static void main(String[] args) throws Throwable {
		TestBlame tt = new TestBlame();
		tt.ddd();
	}

	private static class DiffOutInspector implements HgBlameInspector {
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
		
		int[] getReportedRevisionPairs() {
			return reportedRevisionPairs.toArray();
		}
		
		void reset() {
			reportedRevisionPairs.clear();
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

	@SuppressWarnings("unused")
	private static class LineDumpInspector implements HgBlameInspector {
		
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
	
	/**
	 * Note, this class expects lines coming in natural sequence (not the order they are detected - possible with {@link ReverseAnnotateInspector})
	 * Once async lines are done, shall change implementation here 
	 */
	static class AnnotateInspector implements HgAnnotateCommand.Inspector {
		private int lineNumber = 1;
		private final ArrayList<String> lines = new ArrayList<String>();
		private final IntVector changesets = new IntVector();
		private final IntVector firstAppLines = new IntVector();

		AnnotateInspector fill(int rev, ReverseAnnotateInspector ai) throws HgCallbackTargetException, CancelledException {
			ai.report(rev, this, ProgressSupport.Factory.get(null), CancelSupport.Factory.get(null));
			return this;
		}
		AnnotateInspector fill(int rev, ForwardAnnotateInspector ai) throws HgCallbackTargetException, CancelledException {
			ai.report(rev, this, ProgressSupport.Factory.get(null), CancelSupport.Factory.get(null));
			return this;
		}

		public void next(LineInfo lineInfo) throws HgCallbackTargetException {
			Assert.assertEquals(lineInfo.getLineNumber(), lineNumber);
			lineNumber++;
			lines.add(new String(lineInfo.getContent()));
			changesets.add(lineInfo.getChangesetIndex());
			firstAppLines.add(lineInfo.getOriginLineNumber());
		}
		
		int getLineCount() {
			return changesets.size();
		}
		int getChangeset(int line) {
			return changesets.get(line);
		}
		String getLine(int line) {
			return lines.get(line);
		}
		int getOriginLine(int line) {
			return firstAppLines.get(line);
		}
	}
	
	private static class AnnotateRunner {
		private final ExecHelper eh;
		private final OutputParser.Stub op;
		private final Path file;
		
		public AnnotateRunner(Path filePath, File repoDir) {
			file = filePath;
			op = new OutputParser.Stub();
			eh = new ExecHelper(op, repoDir);
		}
		
		public void run(int cset, boolean follow) throws Exception {
			op.reset();
			ArrayList<String> args = new ArrayList<String>();
			args.add("hg");
			args.add("annotate");
			args.add("--line-number");
			args.add("-r");
			args.add(cset == TIP ? "tip" : String.valueOf(cset));
			if (!follow) {
				args.add("--no-follow");
			}
			args.add(file.toString());
			eh.run(args);
		}
		
		public String[] getLines() {
			return splitLines(op.result());
		}
	}
}
