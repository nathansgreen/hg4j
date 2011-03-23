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
package org.tmatesoft.hg.repo;

import static org.tmatesoft.hg.core.Nodeid.NULL;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.tmatesoft.hg.core.HgBadStateException;
import org.tmatesoft.hg.core.HgException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ByteArrayChannel;
import org.tmatesoft.hg.internal.ByteArrayDataAccess;
import org.tmatesoft.hg.internal.DataAccess;
import org.tmatesoft.hg.internal.DataAccessProvider;
import org.tmatesoft.hg.internal.DigestHelper;
import org.tmatesoft.hg.internal.InflaterDataAccess;
import org.tmatesoft.hg.internal.RevlogStream;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.util.CancelledException;

/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgBundle {

	private final File bundleFile;
	private final DataAccessProvider accessProvider;

	HgBundle(DataAccessProvider dap, File bundle) {
		accessProvider = dap;
		bundleFile = bundle;
	}

	private DataAccess getDataStream() throws IOException {
		DataAccess da = accessProvider.create(bundleFile);
		byte[] signature = new byte[6];
		if (da.length() > 6) {
			da.readBytes(signature, 0, 6);
			if (signature[0] == 'H' && signature[1] == 'G' && signature[2] == '1' && signature[3] == '0') {
				if (signature[4] == 'G' && signature[5] == 'Z') {
					return new InflaterDataAccess(da, 6, da.length() - 6);
				}
				if (signature[4] == 'B' && signature[5] == 'Z') {
					throw HgRepository.notImplemented();
				}
				if (signature[4] != 'U' || signature[5] != 'N') {
					throw new HgBadStateException("Bad bundle signature:" + new String(signature));
				}
				// "...UN", fall-through
			} else {
				da.reset();
			}
		}
		return da;
	}

	// shows changes recorded in the bundle that are missing from the supplied repository 
	public void changes(final HgRepository hgRepo) throws HgException, IOException {
		Inspector insp = new Inspector() {
			DigestHelper dh = new DigestHelper();
			boolean emptyChangelog = true;
			private DataAccess prevRevContent;

			public void changelogStart() {
				emptyChangelog = true;
				
			}

			public void changelogEnd() {
				if (emptyChangelog) {
					throw new IllegalStateException("No changelog group in the bundle"); // XXX perhaps, just be silent and/or log?
				}
			}

/*
 * Despite that BundleFormat wiki says: "Each Changelog entry patches the result of all previous patches 
 * (the previous, or parent patch of a given patch p is the patch that has a node equal to p's p1 field)",
 *  it seems not to hold true. Instead, each entry patches previous one, regardless of whether the one
 *  before is its parent (i.e. ge.firstParent()) or not.
 *  
Actual state in the changelog.i
Index    Offset      Flags     Packed     Actual   Base Rev   Link Rev  Parent1  Parent2     nodeid
  50:          9212      0        209        329         48         50       49       -1     f1db8610da62a3e0beb8d360556ee1fd6eb9885e
  51:          9421      0        278        688         48         51       50       -1     9429c7bd1920fab164a9d2b621d38d57bcb49ae0
  52:          9699      0        154        179         52         52       50       -1     30bd389788464287cee22ccff54c330a4b715de5
  53:          9853      0        133        204         52         53       51       52     a6f39e595b2b54f56304470269a936ead77f5725
  54:          9986      0        156        182         54         54       52       -1     fd4f2c98995beb051070630c272a9be87bef617d

Excerpt from bundle (nodeid, p1, p2, cs):
   f1db8610da62a3e0beb8d360556ee1fd6eb9885e 26e3eeaa39623de552b45ee1f55c14f36460f220 0000000000000000000000000000000000000000 f1db8610da62a3e0beb8d360556ee1fd6eb9885e; patches:4
   9429c7bd1920fab164a9d2b621d38d57bcb49ae0 f1db8610da62a3e0beb8d360556ee1fd6eb9885e 0000000000000000000000000000000000000000 9429c7bd1920fab164a9d2b621d38d57bcb49ae0; patches:3
>  30bd389788464287cee22ccff54c330a4b715de5 f1db8610da62a3e0beb8d360556ee1fd6eb9885e 0000000000000000000000000000000000000000 30bd389788464287cee22ccff54c330a4b715de5; patches:3
   a6f39e595b2b54f56304470269a936ead77f5725 9429c7bd1920fab164a9d2b621d38d57bcb49ae0 30bd389788464287cee22ccff54c330a4b715de5 a6f39e595b2b54f56304470269a936ead77f5725; patches:3
   fd4f2c98995beb051070630c272a9be87bef617d 30bd389788464287cee22ccff54c330a4b715de5 0000000000000000000000000000000000000000 fd4f2c98995beb051070630c272a9be87bef617d; patches:3

To recreate 30bd..e5, one have to take content of 9429..e0, not its p1 f1db..5e
 */
			public boolean element(GroupElement ge) {
				emptyChangelog = false;
				HgChangelog changelog = hgRepo.getChangelog();
				try {
					if (prevRevContent == null) { 
						if (NULL.equals(ge.firstParent()) && NULL.equals(ge.secondParent())) {
							prevRevContent = new ByteArrayDataAccess(new byte[0]);
						} else {
							final Nodeid base = ge.firstParent();
							if (!changelog.isKnown(base) /*only first parent, that's Bundle contract*/) {
								throw new IllegalStateException(String.format("Revision %s needs a parent %s, which is missing in the supplied repo %s", ge.node().shortNotation(), base.shortNotation(), hgRepo.toString()));
							}
							ByteArrayChannel bac = new ByteArrayChannel();
							changelog.rawContent(base, bac); // FIXME get DataAccess directly, to avoid
							// extra byte[] (inside ByteArrayChannel) duplication just for the sake of subsequent ByteArrayDataChannel wrap.
							prevRevContent = new ByteArrayDataAccess(bac.toArray());
						}
					}
					//
					byte[] csetContent = ge.apply(prevRevContent);
					dh = dh.sha1(ge.firstParent(), ge.secondParent(), csetContent); // XXX ge may give me access to byte[] content of nodeid directly, perhaps, I don't need DH to be friend of Nodeid?
					if (!ge.node().equalsTo(dh.asBinary())) {
						throw new IllegalStateException("Integrity check failed on " + bundleFile + ", node:" + ge.node());
					}
					ByteArrayDataAccess csetDataAccess = new ByteArrayDataAccess(csetContent);
					if (changelog.isKnown(ge.node())) {
						System.out.print("+");
					} else {
						System.out.print("-");
					}
					RawChangeset cs = RawChangeset.parse(csetDataAccess);
					System.out.println(cs.toString());
					prevRevContent = csetDataAccess.reset();
				} catch (CancelledException ex) {
					return false;
				} catch (Exception ex) {
					throw new HgBadStateException(ex); // FIXME
				}
				return true;
			}

			public void manifestStart() {}
			public void manifestEnd() {}
			public void fileStart(String name) {}
			public void fileEnd(String name) {}

		};
		inspectChangelog(insp);
	}

	public void dump() throws IOException {
		Dump dump = new Dump();
		inspectAll(dump);
		System.out.println("Total files:" + dump.names.size());
		for (String s : dump.names) {
			System.out.println(s);
		}
	}

	// callback to minimize amount of Strings and Nodeids instantiated
	public interface Inspector {
		void changelogStart();

		void changelogEnd();

		void manifestStart();

		void manifestEnd();

		void fileStart(String name);

		void fileEnd(String name);

		/**
		 * @param element
		 *            data element, instance might be reused
		 * @return <code>true</code> to continue
		 */
		boolean element(GroupElement element);
	}

	public static class Dump implements Inspector {
		public final LinkedList<String> names = new LinkedList<String>();

		public void changelogStart() {
			System.out.println("Changelog group");
		}

		public void changelogEnd() {
		}

		public void manifestStart() {
			System.out.println("Manifest group");
		}

		public void manifestEnd() {
		}

		public void fileStart(String name) {
			names.add(name);
			System.out.println(name);
		}

		public void fileEnd(String name) {
		}

		public boolean element(GroupElement ge) {
			try {
				System.out.printf("  %s %s %s %s; patches:%d\n", ge.node(), ge.firstParent(), ge.secondParent(), ge.cset(), ge.patches().size());
			} catch (Exception ex) {
				ex.printStackTrace(); // FIXME
			}
			return true;
		}
	}

	public void inspectChangelog(Inspector inspector) throws IOException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		DataAccess da = getDataStream();
		try {
			if (da.isEmpty()) {
				return;
			}
			inspector.changelogStart();
			readGroup(da, inspector);
			inspector.changelogEnd();
		} finally {
			da.done();
		}
	}

	public void inspectManifest(Inspector inspector) throws IOException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		DataAccess da = getDataStream();
		try {
			if (da.isEmpty()) {
				return;
			}
			skipGroup(da); // changelog
			if (!da.isEmpty()) {
				inspector.manifestStart();
				readGroup(da, inspector);
				inspector.manifestEnd();
			}
		} finally {
			da.done();
		}
	}

	public void inspectFiles(Inspector inspector) throws IOException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		DataAccess da = getDataStream();
		try {
			if (!da.isEmpty()) {
				skipGroup(da); // changelog
			}
			if (!da.isEmpty()) {
				skipGroup(da); // manifest
			}
			while (!da.isEmpty()) {
				int fnameLen = da.readInt();
				if (fnameLen <= 4) {
					break; // null chunk, the last one.
				}
				byte[] nameBuf = new byte[fnameLen - 4];
				da.readBytes(nameBuf, 0, nameBuf.length);
				String fname = new String(nameBuf);
				inspector.fileStart(fname);
				readGroup(da, inspector);
				inspector.fileEnd(fname);
			}
		} finally {
			da.done();
		}
	}

	public void inspectAll(Inspector inspector) throws IOException {
		if (inspector == null) {
			throw new IllegalArgumentException();
		}
		DataAccess da = getDataStream();
		try {
			if (da.isEmpty()) {
				return;
			}
			inspector.changelogStart();
			readGroup(da, inspector);
			inspector.changelogEnd();
			//
			if (da.isEmpty()) {
				return;
			}
			inspector.manifestStart();
			readGroup(da, inspector);
			inspector.manifestEnd();
			//
			while (!da.isEmpty()) {
				int fnameLen = da.readInt();
				if (fnameLen <= 4) {
					break; // null chunk, the last one.
				}
				byte[] fnameBuf = new byte[fnameLen - 4];
				da.readBytes(fnameBuf, 0, fnameBuf.length);
				String name = new String(fnameBuf);
				inspector.fileStart(name);
				readGroup(da, inspector);
				inspector.fileEnd(name);
			}
		} finally {
			da.done();
		}
	}

	private static void readGroup(DataAccess da, Inspector inspector) throws IOException {
		int len = da.readInt();
		boolean good2go = true;
		while (len > 4 && !da.isEmpty() && good2go) {
			byte[] nb = new byte[80];
			da.readBytes(nb, 0, 80);
			int dataLength = len - 84 /* length field + 4 nodeids */;
			byte[] data = new byte[dataLength];
			da.readBytes(data, 0, dataLength);
			DataAccess slice = new ByteArrayDataAccess(data); // XXX in fact, may pass a slicing DataAccess.
			// Just need to make sure that we seek to proper location afterwards (where next GroupElement starts),
			// regardless whether that slice has read it or not.
			GroupElement ge = new GroupElement(nb, slice);
			good2go = inspector.element(ge);
			len = da.isEmpty() ? 0 : da.readInt();
		}
		// need to skip up to group end if inspector told he don't want to continue with the group, 
		// because outer code may try to read next group immediately as we return back.
		while (len > 4 && !da.isEmpty()) {
			da.skip(len - 4 /* length field */);
			len = da.isEmpty() ? 0 : da.readInt();
		}
	}

	private static void skipGroup(DataAccess da) throws IOException {
		int len = da.readInt();
		while (len > 4 && !da.isEmpty()) {
			da.skip(len - 4); // sizeof(int)
			len = da.isEmpty() ? 0 : da.readInt();
		}
	}

	public static class GroupElement {
		private final byte[] header; // byte[80] takes 120 bytes, 4 Nodeids - 192
		private final DataAccess dataAccess;
		private List<RevlogStream.PatchRecord> patches;

		GroupElement(byte[] fourNodeids, DataAccess rawDataAccess) {
			assert fourNodeids != null && fourNodeids.length == 80;
			header = fourNodeids;
			dataAccess = rawDataAccess;
		}

		public Nodeid node() {
			return Nodeid.fromBinary(header, 0);
		}

		public Nodeid firstParent() {
			return Nodeid.fromBinary(header, 20);
		}

		public Nodeid secondParent() {
			return Nodeid.fromBinary(header, 40);
		}

		public Nodeid cset() { // cs seems to be changeset
			return Nodeid.fromBinary(header, 60);
		}

		public DataAccess rawData() {
			return dataAccess;
		}
		
		public List<RevlogStream.PatchRecord> patches() throws IOException {
			if (patches == null) {
				dataAccess.reset();
				LinkedList<RevlogStream.PatchRecord> p = new LinkedList<RevlogStream.PatchRecord>();
				while (!dataAccess.isEmpty()) {
					RevlogStream.PatchRecord pr = RevlogStream.PatchRecord.read(dataAccess);
					p.add(pr);
				}
				patches = p;
			}
			return patches;
		}

		public byte[] apply(DataAccess baseContent) throws IOException {
			return RevlogStream.apply(baseContent, -1, patches());
		}
	}
}
