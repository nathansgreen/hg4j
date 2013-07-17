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
package org.tmatesoft.hg.test;

import static java.lang.Integer.toBinaryString;
import static org.junit.Assert.*;
import static org.tmatesoft.hg.repo.HgRepository.TIP;
import static org.tmatesoft.hg.util.Path.CompareResult.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCatCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.internal.ByteVector;
import org.tmatesoft.hg.internal.IntSliceSeq;
import org.tmatesoft.hg.internal.IntTuple;
import org.tmatesoft.hg.internal.IntVector;
import org.tmatesoft.hg.internal.PathScope;
import org.tmatesoft.hg.internal.RangePairSeq;
import org.tmatesoft.hg.internal.RevisionDescendants;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.ByteChannel;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;
import org.tmatesoft.hg.util.ProgressSupport;

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
		ArrayHelper<String> ah = new ArrayHelper<String>(initial);
		String[] result = initial.clone();
		ah.sort(result, false, false);
		String[] restored = restore(result, ah.getReverseIndexes());
		assertArrayEquals(initial, restored);
		//
		// few elements are on the right place from the very start and do not shift during sort.
		// make sure for them we've got correct reversed indexes as well
		initial = new String[] {"d", "h", "c", "b", "k", "i", "a", "r", "e", "w" };
		ah = new ArrayHelper<String>(initial);
		ah.sort(result = new String[initial.length], true, true);
		restored = restore(result, ah.getReverseIndexes());
		assertArrayEquals(initial, restored);
		for (int i = 0; i < initial.length; i++) {
			String s = initial[i];
			errorCollector.assertEquals(i, ah.binarySearch(s, -1));
			errorCollector.assertEquals(Arrays.binarySearch(result, s), ah.binarySearchSorted(s));
		}
	}

	private static String[] restore(String[] sorted, int[] sortReverse) {
		String[] rebuilt = new String[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			int indexInOriginal = sortReverse[i];
			rebuilt[indexInOriginal] = sorted[i];
		}
		return rebuilt;
	}
	

	@Test
	public void checkSubProgress() {
		// no repo
		class PS implements ProgressSupport {
			
			@SuppressWarnings("unused")
			public int units;
			public int worked;
			public boolean done = false;
			
			public void start(int totalUnits) {
				units = totalUnits;
			}
			public void worked(int wu) {
				worked += wu;
			}
			public void done() {
				done = true;
			}
		};
		PS ps = new PS();
		ps.start(10);
		ProgressSupport.Sub s1 = new ProgressSupport.Sub(ps, 3);
		ProgressSupport.Sub s2 = new ProgressSupport.Sub(ps, 7);
		s1.start(10);
		s1.worked(1);
		s1.worked(1);
		s1.worked(1);
		s1.worked(1);
		// so far s1 consumed 40% of total 3 units
		assertEquals(1, ps.worked);
		s1.done();
		// now s1 consumed 100% of total 3 units
		assertEquals(3, ps.worked);
		assertFalse(ps.done);
		//
		s2.start(5);
		s2.worked(3);
		// s2 consumed 60% (3/5) of ps's 7 units
		// 3+4 == 3 from s1 + 0.6*7 
		assertEquals(3 + 4, ps.worked);
		s2.worked(2);
		assertEquals(3 + 7, ps.worked);
		assertFalse(ps.done);
		s2.done();
		//assertTrue(ps.done);
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
	
	static class CancelAtValue {
		public int lastSeen;
		public final int stopValue;
		protected final CancelImpl cancelImpl = new CancelImpl();

		protected CancelAtValue(int value) {
			stopValue = value;
		}
		
		protected void nextValue(int value) {
			lastSeen = value;
			if (value == stopValue) {
				cancelImpl.stop();
			}
		}
	}

	@Test
	public void testChangelogCancelSupport() throws Exception {
		HgRepository repository = Configuration.get().find("branches-1"); // any repo with more revisions
		class InspectorImplementsCancel extends CancelAtValue implements HgChangelog.Inspector, CancelSupport {

			public InspectorImplementsCancel(int limit) {
				super(limit);
			}
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				nextValue(revisionNumber);
			}

			public void checkCancelled() throws CancelledException {
				cancelImpl.checkCancelled();
			}
		};
		class InspectorImplementsAdaptable extends CancelAtValue implements HgChangelog.Inspector, Adaptable {
			public InspectorImplementsAdaptable(int limit) {
				super(limit);
			}
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				nextValue(revisionNumber);
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
		Assert.assertEquals(insp1.stopValue, insp1.lastSeen);
		repository.getChangelog().all(insp1 = new InspectorImplementsCancel(12));
		Assert.assertEquals(insp1.stopValue, insp1.lastSeen);
		//
		InspectorImplementsAdaptable insp2;
		repository.getChangelog().all(insp2= new InspectorImplementsAdaptable(3));
		Assert.assertEquals(insp2.stopValue, insp2.lastSeen);
		repository.getChangelog().all(insp2 = new InspectorImplementsAdaptable(10));
		Assert.assertEquals(insp2.stopValue, insp2.lastSeen);
	}
	
	@Test
	public void testManifestCancelSupport() throws Exception {
		HgRepository repository = Configuration.get().find("branches-1"); // any repo with as many revisions as possible
		class InspectorImplementsAdaptable extends CancelAtValue implements HgManifest.Inspector, Adaptable {
			public InspectorImplementsAdaptable(int limit) {
				super(limit);
			}

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				nextValue(lastSeen+1);
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
		Assert.assertEquals(insp1.stopValue, insp1.lastSeen);
		repository.getManifest().walk(0, TIP, insp1 = new InspectorImplementsAdaptable(10));
		Assert.assertEquals(insp1.stopValue, insp1.lastSeen);
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

			public void next(int localRevision, Nodeid revision, int linkedRevision) throws HgRuntimeException {
				assertEquals(i++, localRevision);
				assertEquals(fileNode.getChangesetRevisionIndex(localRevision), linkedRevision);
				assertEquals(fileNode.getRevision(localRevision), revision);
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
				assertEquals(i++, localRevision);
				all[c++] = revision;
				assertNotNull(revision);
				assertFalse(localRevision == 0 && (parent1 != -1 || parent2 != -1));
				assertFalse(localRevision > 0 && parent1 == -1 && parent2 == -1);
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

	/*
	 * This test checks not only RevisionDescendants class, but also
	 * Revlog.indexWalk implementation defect, aka:
	 * Issue 31: Revlog#walk doesn't handle ParentInspector correctly with start revision other than 0, fails with AIOOBE
	 */
	@Test
	public void testRevisionDescendants() throws Exception {
		HgRepository hgRepo = Configuration.get().find("branches-1");
		int[] roots = new int[] {0, 1, 2, 3, 4, 5};
		// 0: all revisions are descendants, 17 total.
		// 1: 2, 4, 7, 8, 9
		// 2: 7, 8, 9 
		// 3: 5,6, 10-16
		// 4: no children
		// 5: 6, 10-16
		// array values represent bit mask, '1' for revision that shall re reported as descendant
		// least significant bit is revision 0, and so on, so that 1<<revision points to bit in the bitmask
		int[] descendantBitset = new int[] { 0x01FFFF, 0x0396, 0x0384, 0x01FC68, 0x010, 0x01FC60 };
		RevisionDescendants[] result = new RevisionDescendants[roots.length];
		for (int i = 0; i < roots.length; i++) {
			result[i] = new RevisionDescendants(hgRepo, roots[i]);
			result[i].build();
		}
		/*
		for (int i = 0; i < roots.length; i++) {
			System.out.printf("For root %d descendats are:", roots[i]);
			for (int j = roots[i], x = hgRepo.getChangelog().getLastRevision(); j <= x; j++) {
				if (result[i].isDescendant(j)) {
					System.out.printf("%3d ", j);
				}
			}
			System.out.printf(", isEmpty:%b\n", !result[i].hasDescendants());
		}
		*/
		for (int i = 0; i < roots.length; i++) {
//			System.out.printf("%s & %s = 0x%x\n", toBinaryString(descendantBitset[i]), toBinaryString(~(1<<roots[i])), descendantBitset[i] & ~(1<<roots[i]));
			if ((descendantBitset[i] & ~(1<<roots[i])) != 0) {
				assertTrue(result[i].hasDescendants());
			} else {
				assertFalse(result[i].hasDescendants());
			}
			for (int j = roots[i], x = hgRepo.getChangelog().getLastRevision(); j <= x; j++) {
				int bit = 1<<j;
				boolean shallBeDescendant = (descendantBitset[i] & bit) != 0;
				String m = String.format("Check rev %d from root %d. Bit %s in %s, shallBeDescendant:%b", j, roots[i], toBinaryString(bit), toBinaryString(descendantBitset[i]), shallBeDescendant);
				if (result[i].isDescendant(j)) {
					assertTrue(m, shallBeDescendant);
				} else {
					assertFalse(m, shallBeDescendant);
				}
			}
		}
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
	
	@Test
	public void testIntVector() {
		IntVector v = new IntVector();
		v.add(10, 9, 8);
		v.add(7);
		errorCollector.assertEquals(4, v.size());
		v.clear();
		errorCollector.assertEquals(0, v.size());
		
		// vector that doesn't grow
		v = new IntVector(3, 0);
		v.add(1,2,3);
		try {
			v.add(4);
			errorCollector.fail("This vector instance is not supposed to grow on demand");
		} catch (UnsupportedOperationException ex) {
		}
		v = new IntVector(5, 2);
		v.add(10,9,8);
		v.add(7,6);
		v.add(5,4,3,2,1);
		errorCollector.assertEquals(10, v.size());
		// so far so good - grow() works
		// now, check reverse() 
		v.reverse();
		for (int i = 0; i < v.size(); i++) {
			errorCollector.assertEquals(i+1, v.get(i));
		}
	}
	
	@Test
	public void testRangePairSequence() {
		RangePairSeq rs = new RangePairSeq();
		rs.add(-1, 5, 3);
		rs.add(-1, 10, 2);
		rs.add(-1, 15, 3);
		rs.add(-1, 20, 3);
		errorCollector.assertFalse(rs.includesTargetLine(4));
		errorCollector.assertTrue(rs.includesTargetLine(7));
		errorCollector.assertFalse(rs.includesTargetLine(8));
		errorCollector.assertTrue(rs.includesTargetLine(10));
		errorCollector.assertFalse(rs.includesTargetLine(12));
	}
	
	@Test
	public void testByteVector() {
		ByteVector v = new ByteVector(4, 2);
		v.add(7);
		v.add(9);
		errorCollector.assertEquals(2, v.size());
		v.clear();
		errorCollector.assertEquals(0, v.size());
		v.add(10);
		v.add(9);
		v.add(8);
		v.add(7);
		v.add(6);
		errorCollector.assertEquals(5, v.size());
		v.add(5);
		v.add(4);
		errorCollector.assertEquals(7, v.size());
		byte x = 10;
		for (byte d : v.toByteArray()) {
			errorCollector.assertEquals(x, d);
			x--;
		}
		x = 10;
		byte[] dd = new byte[10];
		v.copyTo(dd);
		for (int i = 0; i < v.size(); i++) {
			errorCollector.assertEquals(x, dd[i]);
			x--;
		}
		errorCollector.assertTrue(v.equalsTo(new byte[] { 10,9,8,7,6,5,4 }));
	}
	
	@Test
	public void testIntSliceSeq() {
		IntSliceSeq s1 = new IntSliceSeq(3, 10, 10);
		s1.add(1,2,3);
		try {
			s1.add(1,2);
			errorCollector.fail("shall accept precise number of arguments");
		} catch (IllegalArgumentException ex) {
		}
		try {
			s1.add(1,2,3,4);
			errorCollector.fail("shall accept precise number of arguments");
		} catch (IllegalArgumentException ex) {
		}
		s1.add(21,22,23);
		errorCollector.assertEquals(2, s1.size());
		s1.add(7, 8, 9);
		s1.set(1, 4, 5, 6);
		IntTuple l = s1.last();
		errorCollector.assertEquals(7, l.at(0));
		errorCollector.assertEquals(8, l.at(1));
		errorCollector.assertEquals(9, l.at(2));
		int v = 1, slice = 0;
		for (IntTuple t : s1) {
			for (int i = 0; i < t.size(); i++) {
				errorCollector.assertEquals(String.format("Slice %d, element %d", slice, i), v++, t.at(i));
			}
			slice++;
		}
		errorCollector.assertEquals(10, v);
	}
	
	public static void main(String[] args) throws Throwable {
		TestAuxUtilities t = new TestAuxUtilities();
		t.testByteVector();
		t.errorCollector.verify();
	}
}
