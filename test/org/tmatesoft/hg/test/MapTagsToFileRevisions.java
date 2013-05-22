package org.tmatesoft.hg.test;

import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.tmatesoft.hg.core.HgCallbackTargetException;
import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;
import org.tmatesoft.hg.repo.HgTags;
import org.tmatesoft.hg.repo.HgTags.TagInfo;
import org.tmatesoft.hg.repo.HgRevisionMap;
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
//		m.measurePatchAffectsArbitraryRevisionRead();
//		m.collectTagsPerFile();
//		m.manifestWalk();
//		m.changelogWalk();
//		m.revisionMap();
		m.buildFile2ChangelogRevisionMap(".hgtags", "README", "configure.in", "Misc/NEWS");
		m = null;
		System.gc();
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}
	

	// revision == 2406  -   5 ms per run (baseRevision == 2406)
	// revision == 2405  -  69 ms per run (baseRevision == 1403)
	public void measurePatchAffectsArbitraryRevisionRead() throws Exception {
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final DoNothingManifestInspector insp = new DoNothingManifestInspector();
		final int revision = 2405;
		// warm-up.
		repository.getManifest().walk(revision, revision, insp);
		final int runs = 10;
		final long start = System.nanoTime();
		for (int i = 0; i < runs; i++) {
			repository.getManifest().walk(revision, revision, insp);
		}
		final long end = System.nanoTime();
		System.out.printf("%d ms per run\n", (end - start)/ (runs*1000000));
	}

	/*
	 * .hgtags, 261 revisions
	 * Approach 1: total 83, init: 0, iteration: 82
	 * Approach 2: total 225, init: 206, iteration: 19
	 * README, 465 revisions
	 * Approach 1: total 162, init: 0, iteration: 161
	 * Approach 2: total 231, init: 198, iteration: 32
	 * configure.in, 1109 revisions
	 * Approach 1: total 409, init: 1, iteration: 407
	 * Approach 2: total 277, init: 203, iteration: 74
	 */
	/* New data, 0.9.0v (another CPU!)
	 *.hgtags, 306 revisions
	 * Approach 0: total 136
	 * Approach 1: total 53, init: 1, iteration: 52
	 * Approach 2: total 95, init: 78, iteration: 17
	 * Approach 3: total 17
	 * 
	 * README, 499 revisions
	 * Approach 0: total 149
	 * Approach 1: total 43, init: 0, iteration: 43
	 * Approach 2: total 102, init: 86, iteration: 16
	 * Approach 3: total 18
	 * 
	 * configure.in, 1170 revisions
	 * Approach 0: total 321
	 * Approach 1: total 116, init: 0, iteration: 115
	 * Approach 2: total 140, init: 79, iteration: 60
	 * Approach 3: total 30
	 * 
	 * Misc/NEWS, 10863 revisions
	 * Approach 0: total 4946
	 * Approach 1: total 309, init: 6, iteration: 302
	 * Approach 2: total 213, init: 63, iteration: 150
	 * Approach 3: total 140
 	 */
	private void buildFile2ChangelogRevisionMap(String... fileNames) throws Exception {
		final HgRepository repository = new HgLookup().detect(new File("/home/artem/hg/cpython"));
		final HgChangelog clog = repository.getChangelog();
		// warm-up
		HgRevisionMap<HgChangelog> clogMap = new HgRevisionMap<HgChangelog>(clog).init();

		for (String fname : fileNames) {
			HgDataFile fileNode = repository.getFileNode(fname);
			// warm-up
			HgRevisionMap<HgDataFile> fileMap = new HgRevisionMap<HgDataFile>(fileNode).init();
			//
			final int latestRevision = fileNode.getLastRevision();
			//
			final long start_0 = System.nanoTime();
			final Map<Nodeid, Nodeid> changesetToNodeid_0 = new HashMap<Nodeid, Nodeid>();
			for (int fileRevisionIndex = 0; fileRevisionIndex <= latestRevision; fileRevisionIndex++) {
				Nodeid fileRevision = fileNode.getRevision(fileRevisionIndex);
				Nodeid changesetRevision = fileNode.getChangesetRevision(fileRevision);
				changesetToNodeid_0.put(changesetRevision, fileRevision);
			}
			final long end_0 = System.nanoTime();
			//
			final long start_1 = System.nanoTime();
			fileMap = new HgRevisionMap<HgDataFile>(fileNode).init();
			final long start_1a = System.nanoTime();
			final Map<Nodeid, Nodeid> changesetToNodeid_1 = new HashMap<Nodeid, Nodeid>();
			for (int revision = 0; revision <= latestRevision; revision++) {
				final Nodeid nodeId = fileMap.revision(revision);
				int localCset = fileNode.getChangesetRevisionIndex(revision);
				final Nodeid changesetId = clog.getRevision(localCset);
//				final Nodeid changesetId = fileNode.getChangesetRevision(nodeId);
				changesetToNodeid_1.put(changesetId, nodeId);
			}
			final long end_1 = System.nanoTime();
			//
			final long start_2 = System.nanoTime();
			clogMap = new HgRevisionMap<HgChangelog>(clog).init();
			fileMap = new HgRevisionMap<HgDataFile>(fileNode).init();
			final Map<Nodeid, Nodeid> changesetToNodeid_2 = new HashMap<Nodeid, Nodeid>();
			final long start_2a = System.nanoTime();
			for (int revision = 0; revision <= latestRevision; revision++) {
				Nodeid nidFile = fileMap.revision(revision);
				int localCset = fileNode.getChangesetRevisionIndex(revision);
				Nodeid nidCset = clogMap.revision(localCset);
				changesetToNodeid_2.put(nidCset, nidFile);
			}
			final long end_2 = System.nanoTime();
			Assert.assertEquals(changesetToNodeid_1, changesetToNodeid_2);
			//
			final long start_3 = System.nanoTime();
			final Map<Nodeid, Nodeid> changesetToNodeid_3 = new HashMap<Nodeid, Nodeid>();
			fileNode.indexWalk(0, TIP, new HgDataFile.RevisionInspector() {
	
				public void next(int fileRevisionIndex, Nodeid revision, int linkedRevisionIndex) throws HgRuntimeException {
					changesetToNodeid_3.put(clog.getRevision(linkedRevisionIndex), revision);
				}
			});
			final long end_3 = System.nanoTime();
			Assert.assertEquals(changesetToNodeid_1, changesetToNodeid_3);
			System.out.printf("%s, %d revisions\n", fname, 1+latestRevision);
			System.out.printf("Approach 0: total %d\n", (end_0 - start_0)/1000000);
			System.out.printf("Approach 1: total %d, init: %d, iteration: %d\n", (end_1 - start_1)/1000000, (start_1a - start_1)/1000000, (end_1 - start_1a)/1000000);
			System.out.printf("Approach 2: total %d, init: %d, iteration: %d\n", (end_2 - start_2)/1000000, (start_2a - start_2)/1000000, (end_2 - start_2a)/1000000);
			System.out.printf("Approach 3: total %d\n", (end_3 - start_3)/1000000);
		} 
	}

	/*
	 * Each 5000 revisions from cpython, total 15 revisions
	 * Direct clog.getRevisionIndex: ~260 ms
	 * RevisionMap.revisionIndex: ~265 ms (almost 100% in #init())
	 * each 1000'th revision, total 71 revision: 1 230 vs 270
	 * each 2000'th revision, total 36 revision: 620 vs 270
	 * each 3000'th revision, total 24 revision: 410 vs 275
	 */
	public void revisionMap() throws Exception {
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final HgChangelog clog = repository.getChangelog();
		ArrayList<Nodeid> revisions = new ArrayList<Nodeid>();
		final int step = 5000;
		for (int i = 0, top = clog.getLastRevision(); i < top; i += step) {
			revisions.add(clog.getRevision(i));
		}
		final long s1 = System.nanoTime();
		for (Nodeid n : revisions) {
			int r = clog.getRevisionIndex(n);
			if (r % step != 0) {
				throw new IllegalStateException(Integer.toString(r));
			}
		}
		System.out.printf("Direct lookup of %d revisions took %,d ns\n", revisions.size(), System.nanoTime() - s1);
		HgRevisionMap<HgChangelog> rmap = new HgRevisionMap<HgChangelog>(clog);
		final long s2 = System.nanoTime();
		rmap.init();
		final long s3 = System.nanoTime();
		for (Nodeid n : revisions) {
			int r = rmap.revisionIndex(n);
			if (r % step != 0) {
				throw new IllegalStateException(Integer.toString(r));
			}
		}
		System.out.printf("RevisionMap time: %d ms, of that init() %,d ns\n", (System.nanoTime() - s2) / 1000000, s3 - s2);
	}

	public void changelogWalk() throws Exception {
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

	public void manifestWalk() throws Exception {
		System.out.println(System.getProperty("java.version"));
		final long start = System.currentTimeMillis();
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		repository.getManifest().walk(0, 10000, new DoNothingManifestInspector());
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
	
	private int[] collectLocalTagRevisions(HgRevisionMap<HgChangelog> clogrmap, TagInfo[] allTags, IntMap<List<TagInfo>> tagLocalRev2TagInfo) {
		int[] tagLocalRevs = new int[allTags.length];
		int x = 0;
		for (int i = 0; i < allTags.length; i++) {
			final Nodeid tagRevision = allTags[i].revision();
			final int tagRevisionIndex = clogrmap.revisionIndex(tagRevision);
			if (tagRevisionIndex != HgRepository.BAD_REVISION) {
				tagLocalRevs[x++] = tagRevisionIndex;
				List<TagInfo> tagsAssociatedWithRevision = tagLocalRev2TagInfo.get(tagRevisionIndex);
				if (tagsAssociatedWithRevision == null) {
					tagLocalRev2TagInfo.put(tagRevisionIndex, tagsAssociatedWithRevision = new LinkedList<TagInfo>());
				}
				tagsAssociatedWithRevision.add(allTags[i]);
			}
		}
		if (x != allTags.length) {
			// some tags were removed (recorded Nodeid.NULL tagname)
			int[] copy = new int[x];
			System.arraycopy(tagLocalRevs, 0, copy, 0, x);
			tagLocalRevs = copy;
		}
		return tagLocalRevs;
	}

	public void collectTagsPerFile() throws HgException, CancelledException, HgRuntimeException {
		final long start = System.currentTimeMillis();
		final HgRepository repository = new HgLookup().detect(new File("/home/artem/hg/cpython"));
		final HgTags tags = repository.getTags();
		//
		// build cache
		//
		final TagInfo[] allTags = new TagInfo[tags.getAllTags().size()];
		tags.getAllTags().values().toArray(allTags);
		// effective translation of changeset revisions to their local indexes
		final HgRevisionMap<HgChangelog> clogrmap = new HgRevisionMap<HgChangelog>(repository.getChangelog()).init();
		// map to look up tag by changeset local number
		final IntMap<List<TagInfo>> tagLocalRev2TagInfo = new IntMap<List<TagInfo>>(allTags.length);
		System.out.printf("Collecting manifests for %d tags\n", allTags.length);
		final int[] tagLocalRevs = collectLocalTagRevisions(clogrmap, allTags, tagLocalRev2TagInfo);
		System.out.printf("Prepared %d tag revisions to analyze: %d ms\n", tagLocalRevs.length, System.currentTimeMillis() - start);

		final Path targetPath = Path.create("README");
		//
		collectTagsPerFile_Approach_1(clogrmap, tagLocalRevs, allTags, targetPath);
		System.out.printf("Total time: %d ms\n", System.currentTimeMillis() - start);

		System.out.println("\nApproach 2");
		collectTagsPerFile_Approach_2(repository, tagLocalRevs, tagLocalRev2TagInfo, targetPath);
	}
		
	// Approach 1. Build map with all files, their revisions and corresponding tags
	//
	private void collectTagsPerFile_Approach_1(final HgRevisionMap<HgChangelog> clogrmap, final int[] tagLocalRevs, final TagInfo[] allTags, Path targetPath) throws HgException, IllegalArgumentException, HgRuntimeException {
		HgRepository repository = clogrmap.getRepo();
		final long start = System.currentTimeMillis();
		// file2rev2tag value is array of revisions, always of allTags.length. Revision index in the array
		// is index of corresponding TagInfo in allTags;
		final Map<Path, Nodeid[]> file2rev2tag = new HashMap<Path, Nodeid[]>();
		repository.getManifest().walk(new HgManifest.Inspector() {
			private int[] tagIndexAtRev = new int[4]; // it's unlikely there would be a lot of tags associated with a given cset

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				// may do better here using tagLocalRev2TagInfo, but need to change a lot, too lazy now
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
		HgDataFile fileNode = repository.getFileNode(targetPath);
		final Nodeid[] allTagsOfTheFile = file2rev2tag.get(targetPath);
		// TODO if fileNode.isCopy, repeat for each getCopySourceName()
		for (int fileRevIndex = 0; fileRevIndex < fileNode.getRevisionCount(); fileRevIndex++) {
			Nodeid fileRev = fileNode.getRevision(fileRevIndex);
			int changesetRevIndex = fileNode.getChangesetRevisionIndex(fileRevIndex);
			List<String> associatedTags = new LinkedList<String>();
			for (int i = 0; i < allTagsOfTheFile.length; i++) {
				if (fileRev.equals(allTagsOfTheFile[i])) {
					associatedTags.add(allTags[i].name());
				}
			}
			System.out.printf("%3d%7d%s\n", fileRevIndex, changesetRevIndex, associatedTags);
		}
	}
	
	private void collectTagsPerFile_Approach_2(HgRepository repository, final int[] tagLocalRevs, final IntMap<List<TagInfo>> tagRevIndex2TagInfo, Path targetPath) throws HgException, HgRuntimeException {
		//
		// Approach 2. No all-file map. Collect file revisions recorded at the time of tagging,
		// then for each file revision check if it is among those above, and if yes, take corresponding tags
		HgDataFile fileNode = repository.getFileNode(targetPath);
		final long start2 = System.nanoTime();
		final Map<Integer, Nodeid> fileRevisionAtTagRevision = new HashMap<Integer, Nodeid>();
		final Map<Nodeid, List<String>> fileRev2TagNames = new HashMap<Nodeid, List<String>>();
		HgManifest.Inspector collectFileRevAtCset = new HgManifest.Inspector() {
			
			private int csetRevIndex;

			public boolean next(Nodeid nid, Path fname, Flags flags) {
				fileRevisionAtTagRevision.put(csetRevIndex, nid);
				if (tagRevIndex2TagInfo.containsKey(csetRevIndex)) {
					List<String> tags = fileRev2TagNames.get(nid);
					if (tags == null) {
						fileRev2TagNames.put(nid, tags = new ArrayList<String>(3));
					}
					for (TagInfo ti : tagRevIndex2TagInfo.get(csetRevIndex)) {
						tags.add(ti.name());
					}
				}
				return true;
			}
			
			public boolean end(int manifestRevision) {
				return true;
			}
			
			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				csetRevIndex = changelogRevision;
				return true;
			}
		};
		repository.getManifest().walkFileRevisions(targetPath, collectFileRevAtCset,tagLocalRevs);
		
		final long start2a = System.nanoTime();
		fileNode.indexWalk(0, TIP, new HgDataFile.RevisionInspector() {

			public void next(int fileRevisionIndex, Nodeid fileRevision, int changesetRevisionIndex) {
				List<String> associatedTags = new LinkedList<String>();
				
				for (int taggedRevision : tagLocalRevs) {
					// current file revision can't appear in tags that point to earlier changelog revisions (they got own file revision)
					if (taggedRevision >= changesetRevisionIndex) {
						// z points to some changeset with tag
						Nodeid wasKnownAs = fileRevisionAtTagRevision.get(taggedRevision);
						if (wasKnownAs.equals(fileRevision)) {
							// has tag associated with changeset at index z
							List<TagInfo> tagsAtRev = tagRevIndex2TagInfo.get(taggedRevision);
							assert tagsAtRev != null;
							for (TagInfo ti : tagsAtRev) {
								associatedTags.add(ti.name());
							}
						}
					}
				}
				// 
				System.out.printf("%3d%7d%s\n", fileRevisionIndex, changesetRevisionIndex, associatedTags);
			}
		});
		for (int i = 0, lastRev = fileNode.getLastRevision(); i <= lastRev; i++) {
			Nodeid fileRevision = fileNode.getRevision(i);
			List<String> associatedTags2 = fileRev2TagNames.get(fileRevision);
			int changesetRevIndex = fileNode.getChangesetRevisionIndex(i);
			System.out.printf("%3d%7d%s\n", i, changesetRevIndex, associatedTags2 == null ? Collections.emptyList() : associatedTags2);
		}
		System.out.printf("Alternative total time: %d ms, of that init: %d ms\n", (System.nanoTime() - start2)/1000000, (start2a-start2)/1000000);
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}

	static class DoNothingManifestInspector implements HgManifest.Inspector {
		public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
			return true;
		}
		public boolean next(Nodeid nid, Path fname, Flags flags) {
			return true;
		}
		public boolean end(int manifestRevision) {
			return true;
		}
	}
	
	public static void main2(String[] args) throws HgCallbackTargetException, HgException, CancelledException, HgRuntimeException {
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final Path targetPath = Path.create("README");
		final HgTags tags = repository.getTags();
		final Map<String, HgTags.TagInfo> tagToInfo = tags.getAllTags();
		final HgManifest manifest = repository.getManifest();
		final Map<Nodeid, List<String>> changeSetRevisionToTags = new HashMap<Nodeid, List<String>>();
		final HgDataFile fileNode = repository.getFileNode(targetPath);
		for (String tagName : tagToInfo.keySet()) {
			final HgTags.TagInfo info = tagToInfo.get(tagName);
			final Nodeid nodeId = info.revision();
			// TODO: This is not correct as we can't be sure that file at the corresponding revision is actually our target file (which may have been renamed, etc.)
			final Nodeid fileRevision = manifest.getFileRevision(repository.getChangelog().getRevisionIndex(nodeId), targetPath);
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
			public void cset(HgChangeset changeset) {
				if (changeset.getAffectedFiles().contains(targetPath)) {
					System.out.println(changeset.getRevisionIndex() + " " + changeSetRevisionToTags.get(changeset.getNodeid()));
				}
			}
		});
	}
}