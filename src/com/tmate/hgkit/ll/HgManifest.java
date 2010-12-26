/*
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

/**
 *
 * @author artem
 */
public class HgManifest extends Revlog {

	private final RevlogStream content;

	/*package-local*/ HgManifest(HgRepository hgRepo, RevlogStream content) {
		super(hgRepo);
		this.content = content;
	}

	public void dump() {
		Revlog.Inspector insp = new Revlog.Inspector() {
			public void next(int revisionNumber, int actualLen, int baseRevision, int linkRevision, int parent1Revision, int parent2Revision, byte[] nodeid, byte[] data) {
				System.out.println(revisionNumber);
				int i;
				String fname = null;
				String flags = null;
				Nodeid nid = null;
				for (i = 0; i < actualLen; i++) {
					int x = i;
					for( ; data[i] != '\n' && i < actualLen; i++) {
						if (fname == null && data[i] == 0) {
							fname = new String(data, x, i - x);
							x = i+1;
						}
					}
					if (i < actualLen) {
						assert data[i] == '\n'; 
						int nodeidLen = i - x < 40 ? i-x : 40;
						nid = Nodeid.fromAscii(data, x, nodeidLen);
						if (nodeidLen + x < i) {
							// 'x' and 'l' for executable bits and symlinks?
							// hg --debug manifest shows 644 for each regular file in my repo
							flags = new String(data, x + nodeidLen, i-x-nodeidLen);
						}
						System.out.println(nid + "\t" + fname + "\t\t" + flags);
					}
					nid = null;
					fname = flags = null;
				}
				System.out.println();
			}
		};
		content.iterate(0, -1, true, insp);
	}
}
