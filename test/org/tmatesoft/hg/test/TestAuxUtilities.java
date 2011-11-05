/*
 * Copyright (c) 2011 TMate Software Ltd
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

import java.io.IOException;
import java.nio.ByteBuffer;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.tmatesoft.hg.core.HgCatCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
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
		class InspectorImplementsAdaptable implements HgManifest.Inspector2, Adaptable {
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

			public boolean next(Nodeid nid, String fname, String flags) {
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
	public void testRevlogInspectors() throws Exception { // FIXME move to better place
		HgRepository repository = Configuration.get().find("branches-1"); // any repo
		repository.getChangelog().walk(0, TIP, new HgChangelog.RevisionInspector() {

			public void next(int localRevision, Nodeid revision, int linkedRevision) {
				Assert.assertEquals(localRevision, linkedRevision);
			}
		});
		final HgDataFile fileNode = repository.getFileNode("file1");
		fileNode.walk(0, TIP, new HgDataFile.RevisionInspector() {
			int i = 0;

			public void next(int localRevision, Nodeid revision, int linkedRevision) {
				Assert.assertEquals(i++, localRevision);
				Assert.assertEquals(fileNode.getChangesetLocalRevision(localRevision), linkedRevision);
				Assert.assertEquals(fileNode.getRevision(localRevision), revision);
			}
		});
		fileNode.walk(0, TIP, new HgDataFile.ParentInspector() {
			int i = 0;
			Nodeid[] all = new Nodeid[fileNode.getRevisionCount()];

			public void next(int localRevision, Nodeid revision, int parent1, int parent2, Nodeid nidParent1, Nodeid nidParent2) {
				Assert.assertEquals(i++, localRevision);
				all[localRevision] = revision;
				Assert.assertNotNull(revision);
				Assert.assertFalse(localRevision == 0 && (parent1 != -1 || parent2 != -1));
				Assert.assertFalse(localRevision > 0 && parent1 == -1 && parent2 == -1);
				if (parent1 != -1) {
					Assert.assertNotNull(nidParent1);
					// deliberately ==, not asserEquals to ensure same instance
					Assert.assertTrue(nidParent1 == all[parent1]);  
				}
				if (parent2 != -1) {
					Assert.assertNotNull(nidParent2);
					Assert.assertTrue(nidParent2 == all[parent2]);  
				}
			}
		});
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
	
	public static void main(String[] args) throws Exception {
		new TestAuxUtilities().testRepositoryConfig();
	}
}
