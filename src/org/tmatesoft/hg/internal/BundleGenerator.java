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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.repo.HgRepository.NO_REVISION;
import static org.tmatesoft.hg.repo.HgRepository.TIP;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.DataSerializer.OutputStreamSerializer;
import org.tmatesoft.hg.internal.Patch.PatchDataSource;
import org.tmatesoft.hg.repo.HgBundle;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgInternals;
import org.tmatesoft.hg.repo.HgInvalidControlFileException;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.repo.HgRuntimeException;

/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class BundleGenerator {

	private final Internals repo;

	public BundleGenerator(Internals hgRepo) {
		repo = hgRepo;
	}
	
	public File create(List<Nodeid> changesets) throws HgIOException, IOException {
		final HgChangelog clog = repo.getRepo().getChangelog();
		final HgManifest manifest = repo.getRepo().getManifest();
		IntVector clogRevsVector = new IntVector(changesets.size(), 0);
		for (Nodeid n : changesets) {
			clogRevsVector.add(clog.getRevisionIndex(n));
		}
		clogRevsVector.sort(true);
		final int[] clogRevs = clogRevsVector.toArray();
		final IntMap<Nodeid> clogMap = new IntMap<Nodeid>(changesets.size());
		final IntVector manifestRevs = new IntVector(changesets.size(), 0);
		final List<HgDataFile> files = new ArrayList<HgDataFile>();
		clog.range(new HgChangelog.Inspector() {
			private Set<String> seenFiles = new HashSet<String>();
			public void next(int revisionIndex, Nodeid nodeid, RawChangeset cset) throws HgRuntimeException {
				clogMap.put(revisionIndex, nodeid);
				manifestRevs.add(manifest.getRevisionIndex(cset.manifest()));
				for (String f : cset.files()) {
					if (seenFiles.contains(f)) {
						continue;
					}
					seenFiles.add(f);
					HgDataFile df = repo.getRepo().getFileNode(f);
					files.add(df);
				}
			}
		}, clogRevs);
		manifestRevs.sort(true);
		//
		final File bundleFile = File.createTempFile("hg4j-", ".bundle");
		final FileOutputStream osBundle = new FileOutputStream(bundleFile);
		final OutputStreamSerializer outRaw = new OutputStreamSerializer(osBundle);
		outRaw.write("HG10UN".getBytes(), 0, 6);
		//
		RevlogStream clogStream = repo.getImplAccess().getChangelogStream();
		new ChunkGenerator(outRaw, clogMap).iterate(clogStream, clogRevs);
		outRaw.writeInt(0); // null chunk for changelog group
		//
		RevlogStream manifestStream = repo.getImplAccess().getManifestStream();
		new ChunkGenerator(outRaw, clogMap).iterate(manifestStream, manifestRevs.toArray(true));
		outRaw.writeInt(0); // null chunk for manifest group
		//
		EncodingHelper fnEncoder = repo.buildFileNameEncodingHelper();
		for (HgDataFile df : sortedByName(files)) {
			RevlogStream s = repo.getImplAccess().getStream(df);
			final IntVector fileRevs = new IntVector();
			s.iterate(0, TIP, false, new RevlogStream.Inspector() {
				
				public void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) throws HgRuntimeException {
					if (Arrays.binarySearch(clogRevs, linkRevision) >= 0) {
						fileRevs.add(revisionIndex);
					}
				}
			});
			fileRevs.sort(true);
			if (!fileRevs.isEmpty()) {
				// although BundleFormat page says "filename length, filename" for a file,
				// in fact there's a sort of 'filename chunk', i.e. filename length field includes
				// not only length of filename, but also length of the field itseld, i.e. filename.length+sizeof(int)
				byte[] fnameBytes = fnEncoder.toBundle(df.getPath());
				outRaw.writeInt(fnameBytes.length + 4);
				outRaw.writeByte(fnameBytes);
				new ChunkGenerator(outRaw, clogMap).iterate(s, fileRevs.toArray(true));
				outRaw.writeInt(0); // null chunk for file group
			}
		}
		outRaw.writeInt(0); // null chunk to indicate no more files (although BundleFormat page doesn't mention this)
		outRaw.done();
		osBundle.flush();
		osBundle.close();
		//return new HgBundle(repo.getSessionContext(), repo.getDataAccess(), bundleFile);
		return bundleFile;
	}
	
	private static Collection<HgDataFile> sortedByName(List<HgDataFile> files) {
		Collections.sort(files, new Comparator<HgDataFile>() {

			public int compare(HgDataFile o1, HgDataFile o2) {
				return o1.getPath().compareTo(o2.getPath());
			}
		});
		return files;
	}
	
	
	public static void main(String[] args) throws Exception {
		final HgLookup hgLookup = new HgLookup();
		HgRepository hgRepo = hgLookup.detectFromWorkingDir();
		BundleGenerator bg = new BundleGenerator(HgInternals.getImplementationRepo(hgRepo));
		ArrayList<Nodeid> l = new ArrayList<Nodeid>();
		l.add(Nodeid.fromAscii("9ef1fab9f5e3d51d70941121dc27410e28069c2d")); // 640
		l.add(Nodeid.fromAscii("2f33f102a8fa59274a27ebbe1c2903cecac6c5d5")); // 639
		l.add(Nodeid.fromAscii("d074971287478f69ab0a64176ce2284d8c1e91c3")); // 638
		File bundleFile = bg.create(l);
		HgBundle b = hgLookup.loadBundle(bundleFile);
//		Bundle.dump(b); // FIXME dependency from dependant code
	}

	private static class ChunkGenerator implements RevlogStream.Inspector {
		
		private final DataSerializer ds;
		private final IntMap<Nodeid> parentMap;
		private final IntMap<Nodeid> clogMap;
		private byte[] prevContent;
		private int startParent;

		public ChunkGenerator(DataSerializer dataSerializer, IntMap<Nodeid> clogNodeidMap) {
			ds = dataSerializer;
			parentMap = new IntMap<Nodeid>(clogNodeidMap.size());
			clogMap = clogNodeidMap;
		}
		
		public void iterate(RevlogStream s, int[] revisions) throws HgRuntimeException {
			int[] p = s.parents(revisions[0], new int[2]);
			startParent = p[0];
			int[] revs2read;
			if (startParent == NO_REVISION) {
				revs2read = revisions;
				prevContent = new byte[0];
			} else {
				revs2read = new int[revisions.length + 1];
				revs2read[0] = startParent;
				System.arraycopy(revisions, 0, revs2read, 1, revisions.length);
			}
			// FIXME this is a hack to fill parentsMap with 
			// parents of elements that we are not going to meet with regular
			// iteration, e.g. changes from a different branch (with some older parent),
			// scenario: two revisions added to two different branches
			// revisions[10, 11], parents(10) == 9, parents(11) == 7
			// revs2read == [9,10,11], and parentsMap lacks entry for parent rev7.
			fillMissingParentsMap(s, revisions);
			s.iterate(revs2read, true, this);
		}
		
		private void fillMissingParentsMap(RevlogStream s, int[] revisions) throws HgRuntimeException {
			int[] p = new int[2];
			for (int i = 1; i < revisions.length; i++) {
				s.parents(revisions[i], p);
				if (p[0] != NO_REVISION && Arrays.binarySearch(revisions, p[0]) < 0) {
					parentMap.put(p[0], Nodeid.fromBinary(s.nodeid(p[0]), 0));
				}
				if (p[1] != NO_REVISION && Arrays.binarySearch(revisions, p[1]) < 0) {
					parentMap.put(p[1], Nodeid.fromBinary(s.nodeid(p[1]), 0));
				}
			}
		}
		
		public void next(int revisionIndex, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, DataAccess data) throws HgRuntimeException {
			try {
				parentMap.put(revisionIndex, Nodeid.fromBinary(nodeid, 0));
				byte[] nextContent = data.byteArray();
				data.done();
				if (revisionIndex == startParent) {
					prevContent = nextContent;
					return;
				}
				Patch p = GeneratePatchInspector.delta(prevContent, nextContent);
				prevContent = nextContent;
				nextContent = null;
				PatchDataSource pds = p.new PatchDataSource();
				int len = pds.serializeLength() + 84;
				ds.writeInt(len);
				ds.write(nodeid, 0, Nodeid.SIZE);
				// TODO assert parents match those in previous group elements
				if (parent1Revision != NO_REVISION) {
					ds.writeByte(parentMap.get(parent1Revision).toByteArray());
				} else {
					ds.writeByte(Nodeid.NULL.toByteArray());
				}
				if (parent2Revision != NO_REVISION) {
					ds.writeByte(parentMap.get(parent2Revision).toByteArray());
				} else {
					ds.writeByte(Nodeid.NULL.toByteArray());
				}
				ds.writeByte(clogMap.get(linkRevision).toByteArray());
				pds.serialize(ds);
			} catch (IOException ex) {
				// XXX odd to have object with IOException to use where no checked exception is allowed 
				throw new HgInvalidControlFileException(ex.getMessage(), ex, null); 
			} catch (HgIOException ex) {
				throw new HgInvalidControlFileException(ex, true); // XXX any way to refactor ChunkGenerator not to get checked exception here?
			}
		}
	}
}
