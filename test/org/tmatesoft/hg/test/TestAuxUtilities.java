/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.util.Path.CompareResult.*;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCatCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.internal.PathScope;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepoConfig;
import org.tmatesoft.hg.repo.HgRepoConfig.PathsSection;
import org.tmatesoft.hg.repo.HgRepoConfig.Section;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Pair;
import org.tmatesoft.hg.util.Path;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestAuxUtilities {

	@Rule
	public ErrorCollectorExt errorCollector = new ErrorCollectorExt();

	@Test
	public void testArrayHelper() {
		String[] initial = {"d", "w", "k", "b", "c", "i", "a", "r", "e", "h" };
		ArrayHelper ah = new ArrayHelper();
		String[] result = initial.clone();
		ah.sort(result);
		String[] restored = restore(result, ah.getReverse());
		Assert.assertArrayEquals(initial, restored);
		//
		// few elements are on the right place from the very start and do not shift during sort.
		// make sure for them we've got correct reversed indexes as well
		initial = new String[] {"d", "h", "c", "b", "k", "i", "a", "r", "e", "w" };
		ah.sort(result = initial.clone());
		restored = restore(result, ah.getReverse());
		Assert.assertArrayEquals(initial, restored);
	}

	private static String[] restore(String[] sorted, int[] sortReverse) {
		String[] rebuilt = new String[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			int indexInOriginal = sortReverse[i];
			rebuilt[indexInOriginal-1] = sorted[i];
		}
		return rebuilt;
	}

	static class CancelImpl implements CancelSupport {
		private boolean shallStop = false;
		public void stop() {
			shallStop = true;
		}
		public void checkCancelled() throws CancelledException {
			if (shallStop) {
				throw new CancelledException();
			}
		}
	}

	@Test
	public void testChangelogCancelSupport() throws Exception {
		HgRepository repository = Configuration.get().find("branches-1"); // any repo with more revisions
		class InspectorImplementsCancel implements HgChangelog.Inspector, CancelSupport {
			public final int when2stop;
			public int lastVisitet = 0;
			private final CancelImpl cancelImpl = new CancelImpl(); 

			public InspectorImplementsCancel(int limit) {
				when2stop = limit;
			}
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				lastVisitet = revisionNumber;
				if (revisionNumber == when2stop) {
					cancelImpl.stop();
				}
			}

			public void checkCancelled() throws CancelledException {
				cancelImpl.checkCancelled();
			}
		};
		class InspectorImplementsAdaptable implements HgChangelog.Inspector, Adaptable {
			public final int when2stop;
			public int lastVisitet = 0;
			private final CancelImpl cancelImpl = new CancelImpl();
			
			public InspectorImplementsAdaptable(int limit) {
				when2stop = limit;
			}
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				lastVisitet = revisionNumber;
				if (revisionNumber == when2stop) {
					cancelImpl.stop();
				}
			}
			public <T> T getAdapter(Class<T> adapterClass) {
				if (CancelSupport.class == adapterClass) {
					return adapterClass.cast(cancelImpl);
				}
				return null;
			}
			
		}
		//
		InspectorImplementsCancel insp1;
		repository.getChangelog().all(insp1= new InspectorImplementsCancel(2));
		Assert.assertEquals(insp1.when2stop, insp1.lastVisitet);
		repository.getChangelog().all(insp1 = new InspectorImplementsCancel(12));
		Assert.assertEquals(insp1.when2stop, insp1.lastVisitet);
		//
		InspectorImplementsAdaptable insp2;
		repository.getChangelog().all(insp2= new InspectorImplementsAdaptable(3));
		Assert.assertEquals(insp2.when2stop, insp2.lastVisitet);
		repository.getChangelog().all(insp2 = new InspectorImplementsAdaptable(10));
		Assert.assertEquals(insp2.when2stop, insp2.lastVisitet);
	}
	
	@Test
	public void testManifestCancelSupport() throws Exception {
		HgRepository repository = Configuration.get().find("branches-1"); // any repo with as many revisions as possible
		class InspectorImplementsAdaptable implements HgManifest.Inspector, Adaptable {
			public final int when2stop;
			public int lastVisitet = 0;
			private final CancelImpl cancelImpl = new CancelImpl(); 

			public InspectorImplementsAdaptable(int limit) {
				when2stop = limit;
			}

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				if (++lastVisitet == when2stop) {
					cancelImpl.stop();
				}
				return true;
			}

			public boolean end(int manifestRevision) {
				return true;
			}

			public <T> T getAdapter(Class<T> adapterClass) {
				if (CancelSupport.class == adapterClass) {
					return adapterClass.cast(cancelImpl);
				}
				return null;
			}

			public boolean next(Nodeid nid, Path fname, Flags flags) {
				return true;
			}
		}
		InspectorImplementsAdaptable insp1;
		repository.getManifest().walk(0, TIP, insp1= new InspectorImplementsAdaptable(3));
		Assert.assertEquals(insp1.when2stop, insp1.lastVisitet);
		repository.getManifest().walk(0, TIP, insp1 = new InspectorImplementsAdaptable(10));
		Assert.assertEquals(insp1.when2stop, insp1.lastVisitet);
	}
	
	@Test
	public void testCatCommandCancelSupport() throws Exception {
		HgRepository repository = Configuration.get().find("branches-1"); // any repo
		final HgCatCommand cmd = new HgCatCommand(repository);
		cmd.file(Path.create("file1"));
		cmd.set(new CancelSupport() {
			int i = 0;
			public void checkCancelled() throws CancelledException {
				if (i++ == 2) {
					throw new CancelledException();
				}
			}
		});
		try {
			cmd.execute(new ByteChannel() {
				
				public int write(ByteBuffer buffer) throws IOException, CancelledException {
					Assert.fail("Shall not get that far provided cancellation from command's CancelSupport is functional");
					return 0;
				}
			});
			Assert.fail("Command execution shall not fail silently, exception shall propagate");
		} catch (CancelledException ex) {
			// good!
		}
	}

	@Test
	public void testRevlogInspectors() throws Exception { // TODO move to better place
		HgRepository repository = Configuration.get().find("branches-1"); // any repo
		repository.getChangelog().indexWalk(0, TIP, new HgChangelog.RevisionInspector() {

			public void next(int localRevision, Nodeid revision, int linkedRevision) {
				Assert.assertEquals(localRevision, linkedRevision);
			}
		});
		final HgDataFile fileNode = repository.getFileNode("file1");
		fileNode.indexWalk(0, TIP, new HgDataFile.RevisionInspector() {
			int i = 0;

			public void next(int localRevision, Nodeid revision, int linkedRevision) {
				Assert.assertEquals(i++, localRevision);
				Assert.assertEquals(fileNode.getChangesetRevisionIndex(localRevision), linkedRevision);
				Assert.assertEquals(fileNode.getRevision(localRevision), revision);
			}
		});
		class ParentInspectorCheck implements HgDataFile.ParentInspector {
			private int i, c;
			private Nodeid[] all;
			private final int start;
			
			public ParentInspectorCheck(int start, int total) {
				this.start = start;
				i = start; // revision index being iterated
				c = 0; // index/counter of visited revisions
				all = new Nodeid[total];
			}

			public void next(int localRevision, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2) {
				Assert.assertEquals(i++, localRevision);
				all[c++] = revision;
				Assert.assertNotNull(revision);
				Assert.assertFalse(localRevision == 0 && (parent1 != -1 || parent2 != -1));
				Assert.assertFalse(localRevision > 0 && parent1 == -1 && parent2 == -1);
				if (parent1 != -1) {
					Assert.assertNotNull(nidParent1);
					if (parent1 >= start) {
						// deliberately ==, not asserEquals to ensure same instance
						Assert.assertTrue(nidParent1 == all[parent1-start]);  
					}
				}
				if (parent2 != -1) {
					Assert.assertNotNull(nidParent2);
					if (parent2 >= start) {
						Assert.assertTrue(nidParent2 == all[parent2-start]);
					}
				}
			}
		}; 
		fileNode.indexWalk(0, TIP, new ParentInspectorCheck(0, fileNode.getRevisionCount()));
		assert fileNode.getRevisionCount() > 2 : "prereq"; // need at least few revisions
		// there used to be a defect in #walk impl, assumption all parents come prior to a revision
		fileNode.indexWalk(1, 3, new ParentInspectorCheck(1, 3));
	}

	@Test
	@Ignore("just a dump for now, to compare values visually")
	public void testRepositoryConfig() throws Exception {
		HgRepository repo = Configuration.get().own();
		final HgRepoConfig cfg = repo.getConfiguration();
		Assert.assertNotNull(cfg.getPaths());
		Assert.assertNotNull(cfg.getExtensions());
		final Section dne = cfg.getSection("does-not-exist");
		Assert.assertNotNull(dne);
		Assert.assertFalse(dne.exists());
		for (Pair<String, String> p : cfg.getSection("ui")) {
			System.out.printf("%s = %s\n", p.first(), p.second());
		}
		final PathsSection p = cfg.getPaths();
		System.out.printf("Known paths: %d. default: %s(%s), default-push: %s(%s)\n", p.getKeys().size(), p.getDefault(), p.hasDefault(), p.getDefaultPush(), p.hasDefaultPush());
		for (String k : cfg.getPaths().getKeys()) {
			System.out.println(k);
		}
		Assert.assertFalse(p.hasDefault() ^ p.getDefault() != null);
		Assert.assertFalse(p.hasDefaultPush() ^ p.getDefaultPush() != null);
	}
	
	@Test
	public void testChangelogExtrasDecode() {
		final String s = "abc\u0123\r\ndef\n\txx\\yy";
		String r = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\0", "\\0");
//		System.out.println(r);
		String r2 = r.replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\0", "\00");
//		System.out.println(r2);
		Assert.assertTrue(s.equals(r2));
	}

	@Test
	public void testPathScope() {
		// XXX whether PathScope shall accept paths that are leading towards configured elements  
		Path[] scope = new Path[] {
			Path.create("a/"),
			Path.create("b/c"),
			Path.create("d/e/f/")
		};
		//
		// accept specified path, with files and folders below
		PathScope ps1 = new PathScope(true, scope);
		// folders
		errorCollector.assertTrue(ps1.accept(Path.create("a/")));    // == scope[0]
		errorCollector.assertTrue(ps1.accept(Path.create("a/d/")));  // scope[0] is parent and recursiveDir = true
		errorCollector.assertTrue(ps1.accept(Path.create("a/d/e/")));  // scope[0] is parent and recursiveDir = true
		errorCollector.assertTrue(!ps1.accept(Path.create("b/d/"))); // unrelated to any preconfigured
		errorCollector.assertTrue(ps1.accept(Path.create("b/")));    // arg is parent to scope[1]
		errorCollector.assertTrue(ps1.accept(Path.create("d/")));    // arg is parent to scope[2]
		errorCollector.assertTrue(ps1.accept(Path.create("d/e/")));  // arg is parent to scope[2]
		errorCollector.assertTrue(!ps1.accept(Path.create("d/g/"))); // unrelated to any preconfigured
		// files
		errorCollector.assertTrue(ps1.accept(Path.create("a/d")));  // "a/" is parent
		errorCollector.assertTrue(ps1.accept(Path.create("a/d/f")));  // "a/" is still a parent
		errorCollector.assertTrue(ps1.accept(Path.create("b/c")));  // ==
		errorCollector.assertTrue(!ps1.accept(Path.create("b/d"))); // file, !=
		//
		// accept only specified files, folders and their direct children, allow navigate to them from above (FileIterator contract)
		PathScope ps2 = new PathScope(true, false, true, scope);
		// folders
		errorCollector.assertTrue(!ps2.accept(Path.create("a/b/c/"))); // recursiveDirs = false
		errorCollector.assertTrue(ps2.accept(Path.create("b/")));      // arg is parent to scope[1] (IOW, scope[1] is nested under arg)
		errorCollector.assertTrue(ps2.accept(Path.create("d/")));      // scope[2] is nested under arg
		errorCollector.assertTrue(ps2.accept(Path.create("d/e/")));    // scope[2] is nested under arg
		errorCollector.assertTrue(!ps2.accept(Path.create("d/f/")));
		errorCollector.assertTrue(!ps2.accept(Path.create("b/f/")));
		// files
		errorCollector.assertTrue(!ps2.accept(Path.create("a/b/c")));  // file, no exact match
		errorCollector.assertTrue(ps2.accept(Path.create("d/e/f/g"))); // file under scope[2]
		errorCollector.assertTrue(!ps2.accept(Path.create("b/e")));    // unrelated file
		
		// matchParentDirs == false
		PathScope ps3 = new PathScope(false, true, true, Path.create("a/b/")); // match any dir/file under a/b/, but not above
		errorCollector.assertTrue(!ps3.accept(Path.create("a/")));
		errorCollector.assertTrue(ps3.accept(Path.create("a/b/c/d")));
		errorCollector.assertTrue(ps3.accept(Path.create("a/b/c")));
		errorCollector.assertTrue(!ps3.accept(Path.create("b/")));
		errorCollector.assertTrue(!ps3.accept(Path.create("d/")));
		errorCollector.assertTrue(!ps3.accept(Path.create("d/e/")));

		// match nested but not direct dir
		PathScope ps4 = new PathScope(false, true, false, Path.create("a/b/")); // match any dir/file *deep* under a/b/, 
		errorCollector.assertTrue(!ps4.accept(Path.create("a/")));
		errorCollector.assertTrue(!ps4.accept(Path.create("a/b/c")));
		errorCollector.assertTrue(ps4.accept(Path.create("a/b/c/d")));
	}

	@Test
	public void testPathCompareWith() {
		Path p1 = Path.create("a/b/");
		Path p2 = Path.create("a/b/c");
		Path p3 = Path.create("a/b"); // file with the same name as dir
		Path p4 = Path.create("a/b/c/d/");
		Path p5 = Path.create("d/");
		
		errorCollector.assertEquals(Same, p1.compareWith(p1));
		errorCollector.assertEquals(Same, p1.compareWith(Path.create(p1.toString())));
		errorCollector.assertEquals(Unrelated, p1.compareWith(null));
		errorCollector.assertEquals(Unrelated, p1.compareWith(p5));
		//
		errorCollector.assertEquals(Parent, p1.compareWith(p4));
		errorCollector.assertEquals(Nested, p4.compareWith(p1));
		errorCollector.assertEquals(ImmediateParent, p1.compareWith(p2));
		errorCollector.assertEquals(ImmediateChild, p2.compareWith(p1));
		//
		errorCollector.assertEquals(Unrelated, p2.compareWith(p3));
		errorCollector.assertEquals(Unrelated, p3.compareWith(p2));
	}
	
	
	public static void main(String[] args) throws Exception {
		new TestAuxUtilities().testRepositoryConfig();
	}
}
