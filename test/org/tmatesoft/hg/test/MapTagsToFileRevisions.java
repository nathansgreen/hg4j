package org.tmatesoft.hg.test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.core.HgChangeset;
import org.tmatesoft.hg.core.HgChangesetHandler;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.HgLogCommand;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgTags;
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
		m.main();
		m = null;
		System.gc();
		System.out.printf("Free mem: %,d\n", Runtime.getRuntime().freeMemory());
	}
	
	private void main() throws HgException, CancelledException {
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
		final Map<String, Nodeid[]> file2rev2tag = new HashMap<String, Nodeid[]>();
		System.out.printf("Collecting manifests for %d tags\n", allTags.length);
		// effective translation of changeset revisions to their local indexes
		final HgChangelog.RevisionMap clogrmap = repository.getChangelog().new RevisionMap().init();
		int[] tagLocalRevs = new int[allTags.length];
		for (int i = 0; i < allTags.length; i++) {
			final Nodeid tagRevision = allTags[i].revision();
			tagLocalRevs[i] = clogrmap.localRevision(tagRevision);
		}
		System.out.printf("Prepared tag revisions to analyze: %d ms\n", System.currentTimeMillis() - start);
		//
		repository.getManifest().walk(new HgManifest.Inspector() {
			private final ArrayList<Integer> tagIndexAtRev = new ArrayList<Integer>();
			private final Pool<String> filenamePool = new Pool<String>();
			private final Pool<Nodeid> nodeidPool = new Pool<Nodeid>();

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				Nodeid cset = clogrmap.revision(changelogRevision);
				tagIndexAtRev.clear();
				for (int i = 0; i < allTags.length; i++) {
					if (cset.equals(allTags[i].revision())) {
						tagIndexAtRev.add(i);
					}
				}
				if (tagIndexAtRev.isEmpty()) {
					System.out.println("Can't happen, provided we iterate over revisions with tags only");
				}
				return true;
			}
			
			public boolean next(Nodeid nid, String fname, String flags) {
				fname = filenamePool.unify(fname);
				nid = nodeidPool.unify(nid);
				Nodeid[] m = file2rev2tag.get(fname);
				if (m == null) {
					file2rev2tag.put(fname, m = new Nodeid[allTags.length]);
				}
				for (int tagIndex : tagIndexAtRev) {
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
		final Nodeid[] allTagsOfTheFile = file2rev2tag.get(targetPath.toString());
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