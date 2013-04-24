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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.tmatesoft.hg.core.HgRepositoryNotFoundException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayDataAccess;
import org.tmatesoft.hg.internal.DiffHelper;
import org.tmatesoft.hg.internal.Patch;
import org.tmatesoft.hg.internal.DiffHelper.LineSequence;
import org.tmatesoft.hg.internal.RevlogDump.RevlogReader;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgManifest;
import org.tmatesoft.hg.repo.HgManifest.Flags;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Path;

/**
 * Not a real JUnit test now
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestRevlog {
	private ByteBuffer patchData;

	public static void main(String[] args) throws Exception {
		File indexFile = new File("/home/artem/hg/cpython/.hg/store/00manifest.i");
		new TestRevlog().run(indexFile);
	}
	
	private void run(File indexFile) throws Exception {
		final boolean shallDumpDiff = Boolean.TRUE.booleanValue();
		final boolean thoroughCheck = Boolean.TRUE.booleanValue();
		//
		RevlogReader rr = new RevlogReader(indexFile);
		rr.init(true);
		rr.needData(true);
		int startEntryIndex = 76507; // 150--87 
		rr.startFrom(startEntryIndex);
		rr.readNext();
		ByteBuffer baseRevision = null;
		if (rr.isPatch()) {
			byte[] cc = getRevisionTrueContent(indexFile.getParentFile(), rr.entryIndex, rr.linkRevision);
			baseRevision = ByteBuffer.wrap(cc);
		} else {
			baseRevision = ByteBuffer.allocate(rr.getDataLength());
			rr.getData(baseRevision);
			baseRevision.flip();
		}
		ByteArrayDataAccess baseRevisionContent = new ByteArrayDataAccess(baseRevision.array(), baseRevision.arrayOffset(), baseRevision.remaining());
		//
		final long start = System.currentTimeMillis();
		int n = 1419;
		Patch seqPatch = null, normalizedPatch = null;
		while (rr.hasMore() && n-- > 0) {
			rr.readNext();
			if (!rr.isPatch()) {
				break;
			}
			if (rr.getDataLength() == 0) {
				System.out.printf("Empty content of revision %d\n", rr.entryIndex);
				continue;
			}
			Patch p1 = createPatch(rr);
			if (seqPatch != null) {
				if (n < 1) {
					System.out.println("+" + p1);
					System.currentTimeMillis();
				}
				seqPatch = seqPatch.apply(p1);
				Patch ppp = normalizedPatch.apply(p1);
				normalizedPatch = ppp.normalize();
				if (n <= 1) {
					System.out.println("=" + seqPatch);
				}
				if (n == 0) {
					System.out.println("A" + ppp);
					System.out.println("N" + normalizedPatch);
					normalizedPatch = ppp;
				}
				//
				if (!thoroughCheck) {
					if (baseRevisionContent.length() + seqPatch.patchSizeDelta() != rr.actualLen) {
						System.out.printf("Sequential patches:\tPatchRevision #%d (+%d, cset:%d) failed\n", rr.entryIndex, rr.entryIndex - startEntryIndex, rr.linkRevision);
					}
					if (baseRevisionContent.length() + normalizedPatch.patchSizeDelta() != rr.actualLen) {
						System.out.printf("Normalized patches:\tPatchRevision #%d (+%d, cset:%d) failed\n", rr.entryIndex, rr.entryIndex - startEntryIndex, rr.linkRevision);
					}
				} else {
					byte[] origin = getRevisionTrueContent(indexFile.getParentFile(), rr.entryIndex, rr.linkRevision);
					try {
						byte[] result1 = seqPatch.apply(baseRevisionContent, rr.actualLen);
						if (!Arrays.equals(result1, origin)) {
							System.out.printf("Sequential patches:\tPatchRevision #%d (+%d, cset:%d) failed\n", rr.entryIndex, rr.entryIndex - startEntryIndex, rr.linkRevision);
						}
					} catch (ArrayIndexOutOfBoundsException ex) {
						System.err.printf("Failure at entry %d (+%d)\n", rr.entryIndex, rr.entryIndex - startEntryIndex);
						ex.printStackTrace();
					}
					try {
						byte[] result2 = normalizedPatch.apply(baseRevisionContent, rr.actualLen);
						if (!Arrays.equals(result2, origin)) {
							System.out.printf("Normalized patches:\tPatchRevision #%d (+%d, cset:%d) failed\n", rr.entryIndex, rr.entryIndex - startEntryIndex, rr.linkRevision);
						}
					} catch (ArrayIndexOutOfBoundsException ex) {
						System.err.printf("Failure at entry %d (+%d)\n", rr.entryIndex, rr.entryIndex - startEntryIndex);
						ex.printStackTrace();
					}
				}
			} else {
				seqPatch = p1;
				normalizedPatch = p1.normalize();
			}
		}
		final long end1 = System.currentTimeMillis();
		//
//		byte[] result = seqPatch.apply(baseRevisionContent, rr.actualLen);
		byte[] result = normalizedPatch.apply(baseRevisionContent, rr.actualLen);
		final long end2 = System.currentTimeMillis();
		byte[] origin = getRevisionTrueContent(indexFile.getParentFile(), rr.entryIndex, rr.linkRevision);
		final long end3 = System.currentTimeMillis();
		rr.done();
		System.out.printf("Collected patches up to revision %d. Patches total: %d, last contains %d elements\n", rr.entryIndex, rr.entryIndex - startEntryIndex + 1, seqPatch.count());
		if (!Arrays.equals(result, origin)) {
			if (shallDumpDiff) {
				diff(result, origin);
				dumpLineDifference(result, origin);
			} else {
				System.out.println("FAILURE!");
			}
		} else {
			System.out.println("OK!");
			System.out.printf("Iterate: %d ms, apply collected: %d ms, total=%d ms; Conventional: %d ms\n", (end1-start), (end2-end1), (end2-start), (end3-end2));
		}
		Patch normalized = normalizedPatch; //seqPatch.normalize();
		System.out.printf("N%s\n%d => %d patch elements\n", normalized, seqPatch.count(), normalized.count());
//		System.out.println(rs);
	}

	private void dumpLineDifference(byte[] result, byte[] origin) {
		String rs = new String(result).replace('\0', '\t');
		String os = new String(origin).replace('\0', '\t');
		String[] rsLines = rs.split("\n");
		String[] osLines = os.split("\n");
		int approxPos = 0;
		for (int i = 0; i < Math.min(rsLines.length, osLines.length); i++) {
			if (!rsLines[i].equals(osLines[i])) {
				System.out.printf("@%d (offset ~%d)\n\t%s\n\t%s\n", i, approxPos, osLines[i], rsLines[i]);
			}
			approxPos += rsLines[i].length() + 1;
		}
	}

	private void diff(byte[] result, byte[] origin) {
		DiffHelper<LineSequence> pg = new DiffHelper<LineSequence>();
		pg.init(LineSequence.newlines(origin), LineSequence.newlines(result));
		pg.findMatchingBlocks(new DiffHelper.DeltaDumpInspector<LineSequence>());
	}

	private Patch createPatch(RevlogReader rr) throws IOException {
		assert rr.isPatch();
		if (patchData == null || patchData.capacity() < rr.getDataLength()) {
			patchData = ByteBuffer.allocate(rr.getDataLength());
		} else {
			patchData.clear();
		}
		rr.getData(patchData);
		patchData.flip();
		Patch patch1 = new Patch();
		patch1.read(new ByteArrayDataAccess(patchData.array(), patchData.arrayOffset(), patchData.remaining()));
		return patch1;
	}
	
	private byte[] getRevisionTrueContent(File repoLoc, final int manifestRev, int clogRev) throws HgRepositoryNotFoundException {
		HgRepository hgRepo = new HgLookup().detect(repoLoc);
		final ByteArrayOutputStream out = new ByteArrayOutputStream(1024 * 1000);
		hgRepo.getManifest().walk(clogRev, clogRev, new HgManifest.Inspector() {
			
			public boolean next(Nodeid nid, Path fname, Flags flags) {
				try {
					out.write(fname.toString().getBytes());
					out.write(0);
					out.write(nid.toString().getBytes());
					if (flags == Flags.Exec) {
						out.write('x');
					} else if (flags == Flags.Link) {
						out.write('l');
					}
					out.write('\n');
				} catch (IOException e) {
					throw new IllegalStateException(e);
				}
				return true;
			}
			
			public boolean end(int manifestRevisionIndex) {
				return false;
			}
			
			public boolean begin(int manifestRevisionIndex, Nodeid manifestRevision, int changelogRevisionIndex) {
				if (manifestRev != manifestRevisionIndex) {
					throw new IllegalStateException(String.valueOf(manifestRevisionIndex));
				}
				return true;
			}
		});
		return out.toByteArray();
	}
}
