package org.tmatesoft.hg.test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgTags;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgTags.TagInfo;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.Path;

/**
 * @author Marc Strapetz
 */
public class MapTagsToFileRevisions {

	// Static =================================================================

	public static void main(String[] args) throws Exception {
		MapTagsToFileRevisions m = new MapTagsToFileRevisions();
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
//		m.collectTagsPerFile();
//		m.manifestWalk();
//		m.changelogWalk();
		m.revisionMap();
		m = null;
		System.gc();
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}

	/*
	 * Each 5000 revisions from cpython, total 15 revisions
	 * Direct clog.getLocalRevision: ~260 ms
	 * RevisionMap.localRevision: ~265 ms (almost 100% in #init())
	 * each 1000'th revision, total 71 revision: 1 230 vs 270
	 * each 2000'th revision, total 36 revision: 620 vs 270
	 * each 3000'th revision, total 24 revision: 410 vs 275
	 */
	private void revisionMap() throws Exception {
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final HgChangelog clog = repository.getChangelog();
		ArrayList<Nodeid> revisions = new ArrayList<Nodeid>();
		final int step = 5000;
		for (int i = 0, top = clog.getLastRevision(); i < top; i += step) {
			revisions.add(clog.getRevision(i));
		}
		final long s1 = System.nanoTime();
		for (Nodeid n : revisions) {
			int r = clog.getLocalRevision(n);
			if (r % step != 0) {
				throw new IllegalStateException(Integer.toString(r));
			}
		}
		System.out.printf("Direct lookup of %d revisions took %,d ns\n", revisions.size(), System.nanoTime() - s1);
		HgChangelog.RevisionMap rmap = clog.new RevisionMap();
		final long s2 = System.nanoTime();
		rmap.init();
		final long s3 = System.nanoTime();
		for (Nodeid n : revisions) {
			int r = rmap.localRevision(n);
			if (r % step != 0) {
				throw new IllegalStateException(Integer.toString(r));
			}
		}
		System.out.printf("RevisionMap time: %d ms, of that init() %,d ns\n", (System.nanoTime() - s2) / 1000000, s3 - s2);
	}

	private void changelogWalk() throws Exception {
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final long start = System.currentTimeMillis();
		repository.getChangelog().all(new HgChangelog.Inspector() {
			public int xx = 0;
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				if (xx+revisionNumber < 0) {
					System.out.println(xx);
					System.out.println(revisionNumber);
				}
				xx += revisionNumber;
			}
		});
		// cpython: 17 seconds, mem  132,9 -> 129,0 -> 131,7
		// cpyhton: 13 seconds. Of that, cumulative Patch.apply takes 8.8 seconds, RevlogStream.Inspector.next - 1.8
		System.out.printf("Total time: %d ms\n", System.currentTimeMillis() - start);
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}

	private void manifestWalk() throws Exception {
		System.out.println(System.getProperty("java.version"));
		final long start = System.currentTimeMillis();
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		repository.getManifest().walk(0, 10000, new HgManifest.Inspector2() {
			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				return true;
			}
			public boolean next(Nodeid nid, String fname, String flags) {
				throw new HgBadStateException(HgManifest.Inspector2.class.getName());
			}
			public boolean next(Nodeid nid, Path fname, Flags flags) {
				return true;
			}
			public boolean end(int manifestRevision) {
				return true;
			}
		});
		// cpython: 1,1 sec for 0..1000, 43 sec for 0..10000, 115 sec for 0..20000 (Pool with HashMap)
		// 2,4 sec for 1000..2000
		// cpython -r 1000: 484 files, -r 2000: 1015 files. Iteration 1000..2000; fnamePool.size:1019 nodeidPool.size:2989
		// nodeidPool for two subsequent revisions only: 840. 37 sec for 0..10000. 99 sec for 0..20k
		// 0..10000 fnamePool: hits:15989152, misses:3020
		//
		// With Pool<StringProxy> for fname and flags, Nodeid's ascii2bin through local array, overall byte[] iteration, 
		// 0..10k is 34 seconds now
		// Another run, 23 seconds now, seems nothing has been changed. Switched to Pool2 with DirectHashSet: 22,5 seconds
		System.out.printf("Total time: %d ms\n", System.currentTimeMillis() - start);
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}

	private void collectTagsPerFile() throws HgException, CancelledException {
		final long start = System.currentTimeMillis();
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final HgTags tags = repository.getTags();
		//
		// build cache
		//
		final TagInfo[] allTags = new TagInfo[tags.getTags().size()];
		tags.getTags().values().toArray(allTags);
		// file2rev2tag value is array of revisions, always of allTags.length. Revision index in the array
		// is index of corresponding TagInfo in allTags;
		final Map<Path, Nodeid[]> file2rev2tag = new HashMap<Path, Nodeid[]>();
		System.out.printf("Collecting manifests for %d tags\n", allTags.length);
		// effective translation of changeset revisions to their local indexes
		final HgChangelog.RevisionMap clogrmap = repository.getChangelog().new RevisionMap().init();
		int[] tagLocalRevs = new int[allTags.length];
		int x = 0;
		for (int i = 0; i < allTags.length; i++) {
			final Nodeid tagRevision = allTags[i].revision();
			final int tagLocalRev = clogrmap.localRevision(tagRevision);
			if (tagLocalRev != HgRepository.BAD_REVISION) {
				tagLocalRevs[x++] = tagLocalRev;
			}
		}
		if (x != allTags.length) {
			// some tags were removed (recorded Nodeid.NULL tagname)
			int[] copy = new int[x];
			System.arraycopy(tagLocalRevs, 0, copy, 0, x);
			tagLocalRevs = copy;
		}
		System.out.printf("Prepared tag revisions to analyze: %d ms\n", System.currentTimeMillis() - start);
		//
		repository.getManifest().walk(new HgManifest.Inspector2() {
			private int[] tagIndexAtRev = new int[4]; // it's unlikely there would be a lot of tags associated with a given cset

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				Nodeid cset = clogrmap.revision(changelogRevision);
				Arrays.fill(tagIndexAtRev, -1);
				for (int i = 0, x = 0; i < allTags.length; i++) {
					if (cset.equals(allTags[i].revision())) {
						tagIndexAtRev[x++] = i;
						if (x == tagIndexAtRev.length) {
							// expand twice as much
							int[] expanded = new int[x << 1];
							System.arraycopy(tagIndexAtRev, 0, expanded, 0, x);
							expanded[x] = -1; // just in case there'd be no more tags associated with this cset
							tagIndexAtRev = expanded;
						}
					}
				}
				if (tagIndexAtRev[0] == -1) {
					System.out.println("Can't happen, provided we iterate over revisions with tags only");
				}
				return true;
			}
			
			public boolean next(Nodeid nid, String fname, String flags) {
				throw new HgBadStateException(HgManifest.Inspector2.class.getName());
			}

			public boolean next(Nodeid nid, Path fname, HgManifest.Flags flags) {
				Nodeid[] m = file2rev2tag.get(fname);
				if (m == null) {
					file2rev2tag.put(fname, m = new Nodeid[allTags.length]);
				}
				for (int tagIndex : tagIndexAtRev) {
					if (tagIndex == -1) {
						break;
					}
					if (m[tagIndex] != null) {
						System.out.printf("There's another revision (%s) associated with tag %s already while we try to associate %s\n", m[tagIndex].shortNotation(), allTags[tagIndex].name(), nid.shortNotation());
					}
					m[tagIndex] = nid;
				}
				return true;
			}
			
			public boolean end(int manifestRevision) {
				return true;
			}
			
		}, tagLocalRevs);
		System.out.printf("Cache built: %d ms\n", System.currentTimeMillis() - start);
		//
		// look up specific file. This part is fast.
		final Path targetPath = Path.create("README");
		HgDataFile fileNode = repository.getFileNode(targetPath);
		final Nodeid[] allTagsOfTheFile = file2rev2tag.get(targetPath);
		// TODO if fileNode.isCopy, repeat for each getCopySourceName()
		for (int localFileRev = 0; localFileRev < fileNode.getRevisionCount(); localFileRev++) {
			Nodeid fileRev = fileNode.getRevision(localFileRev);
			int changesetLocalRev = fileNode.getChangesetLocalRevision(localFileRev);
			List<String> associatedTags = new LinkedList<String>();
			for (int i = 0; i < allTagsOfTheFile.length; i++) {
				if (fileRev.equals(allTagsOfTheFile[i])) {
					associatedTags.add(allTags[i].name());
				}
			}
			System.out.printf("%3d%7d%s\n", localFileRev, changesetLocalRev, associatedTags);
		}
		System.out.printf("Total time: %d ms\n", System.currentTimeMillis() - start);
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}

	public static void main2(String[] args) throws HgException, CancelledException {
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final Path targetPath = Path.create("README");
		final HgTags tags = repository.getTags();
		final Map<String, HgTags.TagInfo> tagToInfo = tags.getTags();
		final HgManifest manifest = repository.getManifest();
		final Map<Nodeid, List<String>> changeSetRevisionToTags = new HashMap<Nodeid, List<String>>();
		final HgDataFile fileNode = repository.getFileNode(targetPath);
		for (String tagName : tagToInfo.keySet()) {
			final HgTags.TagInfo info = tagToInfo.get(tagName);
			final Nodeid nodeId = info.revision();
			// TODO: This is not correct as we can't be sure that file at the corresponding revision is actually our target file (which may have been renamed, etc.)
			final Nodeid fileRevision = manifest.getFileRevision(repository.getChangelog().getLocalRevision(nodeId), targetPath);
			if (fileRevision == null) {
				continue;
			}

			final Nodeid changeSetRevision = fileNode.getChangesetRevision(fileRevision);
			List<String> revisionTags = changeSetRevisionToTags.get(changeSetRevision);
			if (revisionTags == null) {
				revisionTags = new ArrayList<String>();
				changeSetRevisionToTags.put(changeSetRevision, revisionTags);
			}
			revisionTags.add(tagName);
		}

		final HgLogCommand logCommand = new HgLogCommand(repository);
		logCommand.file(targetPath, true);
		logCommand.execute(new HgChangesetHandler() {
			public void next(HgChangeset changeset) {
				if (changeset.getAffectedFiles().contains(targetPath)) {
					System.out.println(changeset.getRevision() + " " + changeSetRevisionToTags.get(changeset.getNodeid()));
				}
			}
		});
	}
}