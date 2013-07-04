/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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
package org.tmatesoft.hg.console;

import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.repo.HgRepository.WORKING_COPY;
import static org.tmatesoft.hg.util.LogFacility.Severity.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetFileSneaker;
import org.tmatesoft.hg.core.HgChangesetTreeHandler;
import org.tmatesoft.hg.core.HgCheckoutCommand;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgFileRenameHandlerMixin;
import org.tmatesoft.hg.core.HgFileRevision;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.HgManifestCommand;
import org.tmatesoft.hg.core.HgManifestHandler;
import org.tmatesoft.hg.core.HgRevertCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.BasicSessionContext;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.ConfigFile;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.ManifestRevision;
import org.tmatesoft.hg.internal.PathGlobMatcher;
import org.tmatesoft.hg.internal.RelativePathRewrite;
import org.tmatesoft.hg.internal.StreamLogFacility;
import org.tmatesoft.hg.repo.HgBookmarks;
import org.tmatesoft.hg.repo.HgBranches;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgDirstate;
import org.tmatesoft.hg.repo.HgDirstate.EntryKind;
import org.tmatesoft.hg.repo.HgDirstate.Record;
import org.tmatesoft.hg.repo.HgIgnore;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgMergeState;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRevisionMap;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgStatusCollector;
import org.tmatesoft.hg.repo.HgStatusInspector;
import org.tmatesoft.hg.repo.HgSubrepoLocation;
import org.tmatesoft.hg.repo.HgSubrepoLocation.Kind;
import org.tmatesoft.hg.repo.HgWorkingCopyStatusCollector;
import org.tmatesoft.hg.repo.ext.HgExtensionsManager;
import org.tmatesoft.hg.repo.ext.HgExtensionsManager.HgExt;
import org.tmatesoft.hg.repo.ext.Rebase;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.FileWalker;
import org.tmatesoft.hg.util.LogFacility;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Various debug dumps. 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("unused")
public class Main {
	
	private Options cmdLineOpts;
	private HgRepository hgRepo;

	public Main(String[] args) throws Exception {
		cmdLineOpts = Options.parse(args, Collections.<String>emptySet());
		hgRepo = cmdLineOpts.findRepository();
		if (hgRepo.isInvalid()) {
			System.err.printf("Can't find repository in: %s\n", hgRepo.getLocation());
			return;
		}
		System.out.println("REPO:" + hgRepo.getLocation());
	}

	public static void main(String[] args) throws Exception {
		Main m = new Main(args);
//		m.checkFileSneakerPerformance();
//		m.testRevert();
//		m.testCheckout();
//		m.tryExtensions();
//		m.dumpBookmarks();
//		m.dumpCommitLastMessage();
//		m.buildFileLog();
//		m.testConsoleLog();
//		m.testTreeTraversal();
//		m.testRevisionMap();
//		m.testSubrepos();
//		m.testReadWorkingCopy();
//		m.testParents();
//		m.testEffectiveFileLog();
//		m.testMergeState();
//		m.testFileStatus();
		m.dumpBranches();
//		m.inflaterLengthException();
//		m.dumpIgnored();
//		m.dumpDirstate();
//		m.testStatusInternals();
//		m.catCompleteHistory();
//		m.dumpCompleteManifestLow();
//		m.dumpCompleteManifestHigh();
//		m.bunchOfTests();
	}
	
	private void checkFileSneakerPerformance() throws Exception {
		HgChangesetFileSneaker fs1 = new HgChangesetFileSneaker(hgRepo);
		HgChangesetFileSneaker fs2 = new HgChangesetFileSneaker(hgRepo);
		fs1.followRenames(true);
		fs2.followRenames(true);
		Nodeid cset = hgRepo.getChangelog().getRevision(2);
		Path fname = Path.create("dir9/file9"); // close to the manifest end 
		fs1.changeset(cset);
		fs2.changeset(cset);
//		hgRepo.getManifest().getFileRevision(TIP, fname);
		final long start1 = System.nanoTime();
		boolean e1 = fs1.checkExists(fname);
		final long end1 = System.nanoTime();
		boolean e2 = fs2.checkExists(fname);
		final long end2 = System.nanoTime();
		Nodeid fr = hgRepo.getManifest().getFileRevision(TIP, fname);
		final long end3 = System.nanoTime();
		System.out.printf("\t1st run: %d ms, %b\n\t2nd run: %d ms, %b\n\tfile only: %d ms", (end1 - start1) / 1000000, e1, (end2 - end1) / 1000000, e2, (end3-end2)/1000000);
		if (!fr.equals(fs1.revision()) || !fr.equals(fs2.revision())) {
			throw new AssertionError();
		}
		ManifestRevision mr = new ManifestRevision(null, null);
		final long _s1 = System.nanoTime();
		hgRepo.getManifest().walk(0, 0, mr);
		final long _e1 = System.nanoTime();
		hgRepo.getManifest().getFileRevision(0, fname);
		final long _e2 = System.nanoTime();
		System.out.printf("\n\tManifestRevision:%d ms, getFileRevision:%d ms\n", (_e1-_s1)/1000000, (_e2-_e1)/1000000);
	}
	
	//  -agentlib:hprof=cpu=times,heap=sites,depth=10
	private void checkFileSneakerPerformance2() throws Exception {
		Path fname = Path.create("dir9/file9"); // close to the manifest end
		hgRepo.getManifest().getFileRevision(0, fname);
//		ManifestRevision mr = new ManifestRevision(null, null);
//		hgRepo.getManifest().walk(2, 2, mr);
	}
	
	
	private void testRevert() throws Exception {
		HgRevertCommand cmd = new HgRevertCommand(hgRepo);
		cmd.file(Path.create("a.txt")).execute();
	}
	
	private void testCheckout() throws Exception {
		HgCheckoutCommand coCmd = new HgCheckoutCommand(hgRepo);
		coCmd.changeset(17).execute();
	}
	
	private void tryExtensions() throws Exception {
		HgExtensionsManager em = hgRepo.getExtensions();
		if (!em.isEnabled(HgExt.Rebase)) {
			System.out.println("Rebase is not enabled");
			return;
		}
		Rebase re = em.getRebaseExtension();
		if (!re.refresh().isRebaseInProgress()) {
			System.out.println("No rebase is in progress");
			return;
		}
		System.out.printf("%s %s %s\n", re.getWorkingDirParent().shortNotation(), re.getTarget().shortNotation(), re.getExternalParent().shortNotation());
		System.out.printf("collapse:%b, keep:%b, keepbranches:%b\n", re.isCollapse(), re.isKeepOriginalRevisions(), re.isKeepBranchNames());
	}
	
	// TODO as test
	private void dumpBookmarks() throws Exception {
		HgBookmarks bm = hgRepo.getBookmarks();
		String active = bm.getActiveBookmarkName();
		ArrayList<String> all = new ArrayList<String>(bm.getAllBookmarks());
		Collections.sort(all);
		for (String bmname : all) {
			if (bmname.equals(active)) {
				System.out.print(" * ");
			} else {
				System.out.print("   ");
			}
			System.out.printf("%-26s (%s)\n", bmname, bm.getRevision(bmname).shortNotation());
		}
	}

	private void dumpCommitLastMessage() throws Exception {
		System.out.println(hgRepo.getCommitLastMessage());
	}

	private void buildFileLog() throws Exception {
		final long start = System.nanoTime();
		HgLogCommand cmd = new HgLogCommand(hgRepo);
		cmd.file("a2.txt", true, false);
		final int[] count = new int[] { 0 };
		class MyHandler implements HgChangesetTreeHandler, Adaptable {
			public void treeElement(HgChangesetTreeHandler.TreeElement entry) throws HgRuntimeException {
				StringBuilder sb = new StringBuilder();
				HashSet<Nodeid> test = new HashSet<Nodeid>(entry.childRevisions());
				for (HgChangeset cc : entry.children()) {
					sb.append(cc.getRevisionIndex());
					sb.append(':');
					sb.append(cc.getNodeid().shortNotation());
					sb.append(", ");
				}
				final Pair<Nodeid, Nodeid> parents = entry.parentRevisions();
				final boolean isJoin = !parents.first().isNull() && !parents.second().isNull();
				final boolean isFork = entry.children().size() > 1;
				final HgChangeset cset = entry.changeset();
				System.out.printf("%d:%s - %s (%s)\n", cset.getRevisionIndex(), cset.getNodeid().shortNotation(), cset.getComment(), cset.getPhase());
				System.out.printf("\tKnown as %s (file rev:%s)\n", entry.file().getPath(), entry.fileRevision().shortNotation());
				if (!isJoin && !isFork && !entry.children().isEmpty()) {
					HgChangeset p1 = entry.parents().first();
					HgChangeset p2 = entry.parents().second();
					System.out.printf("\tp1:%d, p2:%d\n", p1 == null ? -1 : p1.getRevisionIndex(), p2 == null ? -1 : p2.getRevisionIndex());
					System.out.printf("\t=> %s\n", sb);
				}
				if (isJoin) {
					HgChangeset p1 = entry.parents().first();
					HgChangeset p2 = entry.parents().second();
					System.out.printf("\tjoin <= (%d:%s, %d:%s)", p1.getRevisionIndex(), p1.getNodeid().shortNotation(), p2.getRevisionIndex(), p2.getNodeid().shortNotation());
					if (isFork) {
						System.out.print(", ");
					}
				}
				if (isFork) {
					if (!isJoin) {
						System.out.print('\t');
					}
					System.out.printf("fork => [%s]", sb);
				}
				if (isJoin || isFork) {
					System.out.println();
				}
				count[0]++;
			}

			public <T> T getAdapter(Class<T> adapterClass) {
				if (adapterClass == HgFileRenameHandlerMixin.class) {
					// in fact, new instance is not very nice, however
					// getAdapter callers are supposed to understand the risk of new instance
					// and cache returned value
					// besides, stateless implementation of RenameDumpHandler
					// doesn't really care about few instances 
					return adapterClass.cast(new Log.RenameDumpHandler());
				}
				return null;
			}
		};
		cmd.execute(new MyHandler());
		System.out.println(count[0]);
		final long end = System.nanoTime();
		System.out.printf("buildFileLog: %,d ms\n", (end-start)/1000);
	}

	private void buildFileLogOld() throws Exception {
		final HgDataFile fn = hgRepo.getFileNode("file1");
		final int[] fileChangesetRevisions = new int[fn.getRevisionCount()];
		fn.history(new HgChangelog.Inspector() {
			private int fileLocalRevisions = 0;
			private int[] parentRevisions = new int[2];
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				try {
					fileChangesetRevisions[fileLocalRevisions] = revisionNumber;
					fn.parents(fileLocalRevisions, parentRevisions, null, null);
					boolean join = parentRevisions[0] != -1 && parentRevisions[1] != -1;
					if (join) {
						System.out.print("join[");
					}
					if (parentRevisions[0] != -1) {
						System.out.printf("%2d->%2d, ", fileChangesetRevisions[parentRevisions[0]], revisionNumber);
					}
					if (parentRevisions[1] != -1) {
						System.out.printf("%2d->%2d, ", fileChangesetRevisions[parentRevisions[1]], revisionNumber);
					}
					if (join) {
						System.out.print("]");
					}
					fileLocalRevisions++;
				} catch (HgRuntimeException ex) {
					ex.printStackTrace();
				}
			}
		});
		System.out.println();
	}
	
	private void testConsoleLog() {
		LogFacility fc = new StreamLogFacility(Debug, true, System.out);
		System.out.printf("isDebug: %s, isInfo:%s\n", fc.isDebug(), fc.getLevel() == Info);
		fc.dump(getClass(), Debug, "%d", 1);
		fc.dump(getClass(), Info, "%d\n", 2);
		fc.dump(getClass(), Warn, "%d\n", 3);
		fc.dump(getClass(), Error, "%d", 4);
		Exception ex = new Exception();
		fc.dump(getClass(), Debug, ex, "message");
		fc.dump(getClass(), Info, ex, null);
		fc.dump(getClass(), Warn, ex, null);
		fc.dump(getClass(), Error, ex, "message");
	}
	
	private void testTreeTraversal() throws Exception {
		File repoRoot = hgRepo.getWorkingDir();
		Path.Source pathSrc = new Path.SimpleSource(new PathRewrite.Composite(new RelativePathRewrite(repoRoot), hgRepo.getToRepoPathHelper()));
		FileWalker w =  new FileWalker(new BasicSessionContext(null), repoRoot, pathSrc);
		int count = 0;
		final long start = System.currentTimeMillis();
		while (w.hasNext()) {
			count++;
			w.next();
		}
		System.out.printf("Traversal of %d files took %d ms", count, System.currentTimeMillis() - start);
	}
	
	/*
	 * cpython repo with 70715 revisions.
	 	3 revisions - 80 ms vs 250 ms (250ms init)
		4 revisions - 110 ms vs 265 ms (265 ms init)
		5 revisions - 94 vs 266.
		complete iteration in changelog.getRevisionIndex(tipNodeid) takes 47 ms
		compared to complete iteration inside RevisionMap.init() of 171 ms.
		The only difference is latter instantiates Nodeids, while former compares binary content as is.
		Hence, with 20-30 ms per regular getLocalRevision, it pays off to use RevisionMap with at least 15-20
		queries 
	 */
	private void testRevisionMap() throws Exception {
		HgChangelog changelog = hgRepo.getChangelog();
		HgRevisionMap<HgChangelog> rmap = new HgRevisionMap<HgChangelog>(changelog).init(); // warm-up, ensure complete file read
		int tip = changelog.getLastRevision();
		// take 5 arbitrary revisions at 0, 1/4, 2/4, 3/4 and 4/4 
		final Nodeid[] revs = new Nodeid[5];
		revs[4] = changelog.getRevision(0);
		revs[3] = changelog.getRevision(tip / 4);
		revs[2] = changelog.getRevision(tip / 2);
		revs[1] = changelog.getRevision(tip / 4 + tip / 2);
		revs[0] = changelog.getRevision(tip);
		long start = System.currentTimeMillis();
		for (int i = 0; i < revs.length; i++) {
			final int localRev = changelog.getRevisionIndex(revs[i]);
			System.out.printf("%d:%s\n", localRev, revs[i]);
		}
		System.out.println(System.currentTimeMillis() - start);
		System.out.println();
		//
		start = System.currentTimeMillis();
		rmap = new HgRevisionMap<HgChangelog>(changelog).init();
		long s2 = System.currentTimeMillis();
		for (int i = 0; i < revs.length; i++) {
			final int localRev = rmap.revisionIndex(revs[i]);
			System.out.printf("%d:%s\n", localRev, revs[i]);
		}
		System.out.println(System.currentTimeMillis() - start);
		System.out.printf("\t from that, init took %d ms\n", s2 - start);
		
	}

	// any repository with subrepositories
	private void testSubrepos() throws Exception {
		// @see TestSubrepo#testAccessAPI
		for (HgSubrepoLocation l : hgRepo.getSubrepositories()) {
			System.out.println(l.getLocation());
			System.out.println(l.getSource());
			System.out.println(l.getType());
			System.out.println(l.isCommitted() ? l.getRevision() : "not yet committed");
			if (l.getType() == Kind.Hg) {
				HgRepository r = l.getRepo();
				System.out.printf("%s (%s) has %d revisions\n", l.getLocation(), r.getLocation(), r.getChangelog().getLastRevision() + 1);
				if (r.getChangelog().getLastRevision() >= 0) {
					final RawChangeset c = r.getChangelog().range(TIP, TIP).get(0);
					System.out.printf("TIP: %s %s '%s'\n", c.user(), c.dateString(), c.comment());
				}
			}
		}
	}

	private void testReadWorkingCopy() throws Exception {
		for (String fname : cmdLineOpts.getList("")) {
			HgDataFile fn = hgRepo.getFileNode(fname);
			ByteArrayChannel sink = new ByteArrayChannel();
			fn.workingCopy(sink);
			System.out.printf("%s: read %d bytes of working copy", fname, sink.toArray().length);
		}
	}
	
	private void testParents() throws Exception {
		// hg parents cmd
		final Pair<Nodeid, Nodeid> wcParents = hgRepo.getWorkingCopyParents();
		ChangesetDumpHandler dump = new ChangesetDumpHandler(hgRepo);
		HgLogCommand cmd = new HgLogCommand(hgRepo);
		if (wcParents.hasFirst()) {
			cmd.changeset(wcParents.first()).execute(dump);
		}
		if (wcParents.hasSecond()) {
			cmd.changeset(wcParents.second()).execute(dump);
		}
		System.out.println("Branch:" + hgRepo.getWorkingCopyBranchName());
	}
	
	/*
	 *  -R \temp\hg\hg4j-50 src/org/tmatesoft/hg/internal/RevlogStream.java
	 *  
	 *  -R \temp\hg\cpython Lib/doctest.py, range 15907..68588, total 251 revision
	 *  no improvement (collect linkRev, hgchangelog.range([]))							10890 ms
	 *  improved history logic in HgDataFile (minimize reads of close revisions): 
	 *  		with no sort (defect for tool-created repos)					took	10500 ms
	 *  		with sort (to order revisions from linkRev before use)					  610 ms
	 *  			HgChangelog.range() - 92 calls
	 *  RevlogStream with separate iterate(int[] sortedRevisions,...)
	 *  		RevlogStream.ReaderN1.range(): 185										  380 ms 
	 */
	private void testEffectiveFileLog() throws Exception {
		for (String fname : cmdLineOpts.getList("")) {
			System.out.println(fname);
			final long start = System.currentTimeMillis();
			HgDataFile fn = hgRepo.getFileNode(fname);
			if (fn.exists()) {
				fn.history(new HgChangelog.Inspector() {
					public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
						System.out.printf("%d:%s\n", revisionNumber, nodeid);
					}
				});
			}
			System.out.printf("Done: %d\n", System.currentTimeMillis() - start);
		}
	}
	
	private void testMergeState() throws Exception {
		final HgMergeState mergeState = hgRepo.getMergeState();
		mergeState.refresh();
		System.out.printf("isMerging: %s, isStale: %s.\n", mergeState.isMerging(), mergeState.isStale());
		System.out.printf("P1:%s\nP2:%s\nState parent:%s\n",mergeState.getFirstParent().shortNotation(), mergeState.getSecondParent().shortNotation(), mergeState.getStateParent().shortNotation());
		for (HgMergeState.Entry e : mergeState.getConflicts()) {
			System.out.println(e.getState() + " " + e.getActualFile());
			System.out.println("p1:       " + formatFileRevision(e.getFirstParent()));
			System.out.println("p2:       " + formatFileRevision(e.getSecondParent()));
			System.out.println("ancestor: " + formatFileRevision(e.getCommonAncestor()));
			System.out.println();
		}
	}
	
	private static String formatFileRevision(HgFileRevision r) throws Exception {
		final ByteArrayChannel sink = new ByteArrayChannel();
		r.putContentTo(sink);
		return String.format("%s %s (%d bytes)", r.getPath(), r.getRevision(), sink.toArray().length);
	}
	
	private void testFileStatus() throws Exception {
//		final Path path = Path.create("src/org/tmatesoft/hg/util/");
//		final Path path = Path.create("src/org/tmatesoft/hg/internal/Experimental.java");
//		final Path path = Path.create("missing-dir/");
//		HgWorkingCopyStatusCollector wcsc = HgWorkingCopyStatusCollector.create(hgRepo, path);
		HgWorkingCopyStatusCollector wcsc = HgWorkingCopyStatusCollector.create(hgRepo, new PathGlobMatcher("mi**"));
		wcsc.walk(WORKING_COPY, new StatusDump());
	}
	
	/*
	 * Straightforward approach to collect branches, no use of branchheads.cache
	 * First, single run - 18 563
	 * 10 runs (after 1 warm up) of HgBranches.collect took 167391 ms, ~17 seconds per run.
	 */
	private void dumpBranches() throws Exception {
		final long start0 = System.currentTimeMillis();
		HgBranches b = hgRepo.getBranches();
		System.out.println("1:" + (System.currentTimeMillis() - start0));
		for (HgBranches.BranchInfo bi : b.getAllBranches()) {
			System.out.print(bi.getName());
//			System.out.print(" ");
//			System.out.print(bi.getStart());
			System.out.print(" ");
			System.out.print(bi.getHeads());
			if (bi.isClosed()) {
				System.out.print(" x ");
			}
			System.out.println();
		}
//		b.writeCache();
//		final long start = System.currentTimeMillis();
//		for (int i = 0; i < 10; i++) {
//			b.collect(ProgressSupport.Factory.get(null));
//		}
//		System.out.println("10:" + (System.currentTimeMillis() - start));
	}
	
	private void inflaterLengthException() throws Exception {
		HgDataFile f1 = hgRepo.getFileNode("src/com/tmate/hgkit/console/Bundle.java");
		HgDataFile f2 = hgRepo.getFileNode("test-repos.jar");
		System.out.println(f1.isCopy());
		System.out.println(f2.isCopy());
		ByteArrayChannel bac = new ByteArrayChannel();
		f1.content(1, bac); // 0: 1151, 1: 1139
		System.out.println(bac.toArray().length);
		f2.content(0, bac = new ByteArrayChannel()); // 0: 14269
		System.out.println(bac.toArray().length);
	}
	
	private void dumpIgnored() throws HgInvalidControlFileException {
		String[] toCheck = new String[] {"design.txt", "src/com/tmate/hgkit/ll/Changelog.java", "src/Extras.java", "bin/com/tmate/hgkit/ll/Changelog.class"};
		HgIgnore ignore = hgRepo.getIgnore();
		for (int i = 0; i < toCheck.length; i++) {
			System.out.println("Ignored " + toCheck[i] + ": " + ignore.isIgnored(Path.create(toCheck[i])));
		}
	}

	static class DirstateDump implements HgDirstate.Inspector {
		private final char[] x = new char[] {'n', 'a', 'r', 'm' };

		public boolean next(EntryKind kind, Record entry) {
			System.out.printf("%c %3o%6d %30tc\t\t%s", x[kind.ordinal()], entry.mode(), entry.size(), (long) entry.modificationTime() * 1000, entry.name());
			if (entry.copySource() != null) {
				System.out.printf(" --> %s", entry.copySource());
			}
			System.out.println();
			return true;
		}
	}
	
	private void dumpDirstate() throws Exception {
		new HgInternals(hgRepo).getDirstate().walk(new DirstateDump());
		HgWorkingCopyStatusCollector wcc = HgWorkingCopyStatusCollector.create(hgRepo, new Path.Matcher.Any());
		wcc.getDirstate().walk(new HgDirstate.Inspector() {
			
			public boolean next(EntryKind kind, Record entry) {
				System.out.printf("%s %s\n", kind, entry.name());
				return true;
			}
		});
	}

	
	private void catCompleteHistory() throws Exception {
		DigestHelper dh = new DigestHelper();
		for (String fname : cmdLineOpts.getList("")) {
			System.out.println(fname);
			HgDataFile fn = hgRepo.getFileNode(fname);
			if (fn.exists()) {
				int total = fn.getRevisionCount();
				System.out.printf("Total revisions: %d\n", total);
				for (int i = 0; i < total; i++) {
					ByteArrayChannel sink = new ByteArrayChannel();
					fn.content(i, sink);
					System.out.println("==========>");
					byte[] content = sink.toArray();
					System.out.println(new String(content));
					int[] parentRevisions = new int[2];
					byte[] parent1 = new byte[20];
					byte[] parent2 = new byte[20];
					fn.parents(i, parentRevisions, parent1, parent2);
					System.out.println(dh.sha1(parent1, parent2, content).asHexString());
				}
			} else {
				System.out.println(">>>Not found!");
			}
		}
	}

	private void dumpCompleteManifestLow() throws Exception {
		hgRepo.getManifest().walk(0, TIP, new ManifestDump());
	}

	public static final class ManifestDump implements HgManifest.Inspector {
		public boolean begin(int manifestRevision, Nodeid nid, int changelogRevision) {
			System.out.printf("%d : %s\n", manifestRevision, nid);
			return true;
		}

		public boolean next(Nodeid nid, Path fname, Flags flags) {
			System.out.println(nid + "\t" + fname + "\t\t" + flags);
			return true;
		}

		public boolean end(int revision) {
			System.out.println();
			return true;
		}
	}

	private void dumpCompleteManifestHigh() throws Exception {
		new HgManifestCommand(hgRepo).dirs(true).execute(new HgManifestHandler() {
			
			public void begin(Nodeid manifestRevision) {
				System.out.println(">> " + manifestRevision);
			}
			public void dir(Path p) {
				System.out.println(p);
			}
			public void file(HgFileRevision fileRevision) throws HgRuntimeException {
				System.out.print(fileRevision.getRevision());;
				System.out.print("   ");
				System.out.printf("%s %s", fileRevision.getParents().first().shortNotation(), fileRevision.getParents().second().shortNotation());
				System.out.print("   ");
				System.out.println(fileRevision.getPath());
			}
			
			public void end(Nodeid manifestRevision) {
				System.out.println();
			}
		}); 
	}

	private void bunchOfTests() throws Exception {
		final StatusDump dump = new StatusDump();
		dump.showIgnored = false;
		dump.showClean = false;
		HgStatusCollector sc = new HgStatusCollector(hgRepo);
		final int r1 = 0, r2 = 3;
		System.out.printf("Status for changes between revision %d and %d:\n", r1, r2);
		sc.walk(r1, r2, dump);
		// 
		System.out.println("\n\nSame, but sorted in the way hg status does:");
		HgStatusCollector.Record r = sc.status(r1, r2);
		sortAndPrint('M', r.getModified(), null);
		sortAndPrint('A', r.getAdded(), null);
		sortAndPrint('R', r.getRemoved(), null);
		//
		System.out.println("\n\nTry hg status --change <rev>:");
		sc.change(0, dump);
		System.out.println("\nStatus against working dir:");
		HgWorkingCopyStatusCollector wcc = new HgWorkingCopyStatusCollector(hgRepo);
		wcc.walk(WORKING_COPY, dump);
		System.out.println();
		System.out.printf("Manifest of the revision %d:\n", r2);
		hgRepo.getManifest().walk(r2, r2, new ManifestDump());
		System.out.println();
		System.out.printf("\nStatus of working dir against %d:\n", r2);
		r = wcc.status(r2);
		sortAndPrint('M', r.getModified(), null);
		sortAndPrint('A', r.getAdded(), r.getCopied());
		sortAndPrint('R', r.getRemoved(), null);
		sortAndPrint('?', r.getUnknown(), null);
		sortAndPrint('I', r.getIgnored(), null);
		sortAndPrint('C', r.getClean(), null);
		sortAndPrint('!', r.getMissing(), null);
	}
	
	private void sortAndPrint(char prefix, List<Path> ul, Map<Path, Path> copies) {
		ArrayList<Path> sortList = new ArrayList<Path>(ul);
		Collections.sort(sortList);
		for (Path s : sortList)  {
			System.out.print(prefix);
			System.out.print(' ');
			System.out.println(s);
			if (copies != null && copies.containsKey(s)) {
				System.out.println("  " + copies.get(s));
			}
		}
	}


	private void testStatusInternals() throws HgException, HgRuntimeException {
		HgDataFile n = hgRepo.getFileNode(Path.create("design.txt"));
		for (String s : new String[] {"011dfd44417c72bd9e54cf89b82828f661b700ed", "e5529faa06d53e06a816e56d218115b42782f1ba", "c18e7111f1fc89a80a00f6a39d51288289a382fc"}) {
			// expected: 359, 2123, 3079
			byte[] b = s.getBytes();
			final Nodeid nid = Nodeid.fromAscii(b, 0, b.length);
			System.out.println(s + " : " + n.getLength(nid));
		}
	}

	static void force_gc() {
		Runtime.getRuntime().runFinalization();
		Runtime.getRuntime().gc();
		Thread.yield();
		Runtime.getRuntime().runFinalization();
		Runtime.getRuntime().gc();
		Thread.yield();
	}

	private static class StatusDump implements HgStatusInspector {
		public boolean hideStatusPrefix = false; // hg status -n option
		public boolean showCopied = true; // -C
		public boolean showIgnored = true; // -i
		public boolean showClean = true; // -c

		public void modified(Path fname) {
			print('M', fname);
		}

		public void added(Path fname) {
			print('A', fname);
		}

		public void copied(Path fnameOrigin, Path fnameAdded) {
			added(fnameAdded);
			if (showCopied) {
				print(' ', fnameOrigin);
			}
		}

		public void removed(Path fname) {
			print('R', fname);
		}

		public void clean(Path fname) {
			if (showClean) {
				print('C', fname);
			}
		}

		public void missing(Path fname) {
			print('!', fname);
		}

		public void unknown(Path fname) {
			print('?', fname);
		}

		public void ignored(Path fname) {
			if (showIgnored) {
				print('I', fname);
			}
		}
		
		public void invalid(Path fname, Exception ex) {
			System.out.printf("FAILURE: %s\n", fname);
			ex.printStackTrace(System.out);
		}
		
		private void print(char status, Path fname) {
			if (!hideStatusPrefix) {
				System.out.print(status);
				System.out.print(' ');
			}
			System.out.println(fname);
		}
	}
}
