package org.tmatesoft.hg.test;

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import org.tmatesoft.hg.core.*;
import org.tmatesoft.hg.internal.Pool;
import org.tmatesoft.hg.repo.*;
import org.tmatesoft.hg.repo.HgTags.TagInfo;
import org.tmatesoft.hg.util.*;

/**
 * @author Marc Strapetz
 */
public class MapTagsToFileRevisions {

	// Static =================================================================

	public static void main(String[] args) throws HgException, CancelledException {
		final long start = System.currentTimeMillis();
		final HgRepository repository = new HgLookup().detect(new File("/temp/hg/cpython"));
		final HgTags tags = repository.getTags();
		//
		// build cache
		final Map<String, Map<TagInfo, Nodeid>> file2tag2rev = new HashMap<String, Map<TagInfo, Nodeid>>();
		System.out.printf("Collecting manifests for %d tags\n", tags.getTags().size());
		// effective translation of changeset revisions to their local indexes
		final HgChangelog.RevisionMap clogrmap = repository.getChangelog().new RevisionMap().init();
		int[] tagLocalRevs = new int[tags.getTags().size()];
		int i = 0;
		for (TagInfo tag : tags.getTags().values()) {
			final Nodeid tagRevision = tag.revision();
			int tagLocalRev = clogrmap.localRevision(tagRevision);
			tagLocalRevs[i++] = tagLocalRev;
		}
		System.out.printf("Found tag revisions to analyze: %d\n", System.currentTimeMillis() - start);
		//
		repository.getManifest().walk(new HgManifest.Inspector() {
			private List<String> tagsAtRev;
			final Pool<String> filenamePool = new Pool<String>();
			final Pool<Nodeid> nodeidPool = new Pool<Nodeid>();

			public boolean begin(int mainfestRevision, Nodeid nid, int changelogRevision) {
				Nodeid cset = clogrmap.revision(changelogRevision);
				tagsAtRev = tags.tags(cset);
				if (tagsAtRev.isEmpty()) {
					System.out.println("Can't happen, provided we iterate over revisions with tags only");
				}
				return true;
			}
			
			public boolean next(Nodeid nid, String fname, String flags) {
				fname = filenamePool.unify(fname);
				nid = nodeidPool.unify(nid);
				Map<TagInfo, Nodeid> m = file2tag2rev.get(fname);
				if (m == null) {
					file2tag2rev.put(fname, m = new HashMap<TagInfo, Nodeid>());
				}
				for (String tag : tagsAtRev) {
					m.put(tags.getTags().get(tag), nid);
				}
				return true;
			}
			
			public boolean end(int manifestRevision) {
				return true;
			}
			
		}, tagLocalRevs);
		System.out.printf("Cache built: %d\n", System.currentTimeMillis() - start);
		//
		// look up specific file. This part is fast.
		final Path targetPath = Path.create("README");
		HgDataFile fileNode = repository.getFileNode(targetPath);
		// TODO if fileNode.isCopy, repeat for each getCopySourceName()
		for (int localFileRev = 0; localFileRev < fileNode.getRevisionCount(); localFileRev++) {
			Nodeid fileRev = fileNode.getRevision(localFileRev);
			int changesetLocalRev = fileNode.getChangesetLocalRevision(localFileRev);
			List<String> associatedTags = new LinkedList<String>();
			final Map<TagInfo, Nodeid> allTagsOfTheFile = file2tag2rev.get(targetPath.toString());
			for (Entry<TagInfo, Nodeid> e : allTagsOfTheFile.entrySet()) {
				Nodeid fileRevAtTag = e.getValue();
				if (fileRev.equals(fileRevAtTag)) {
					associatedTags.add(e.getKey().name());
				}
			}
			System.out.printf("%3d%7d%s\n", localFileRev, changesetLocalRev, associatedTags);
		}
		System.out.printf("Total time: %d", System.currentTimeMillis() - start);
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