/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;


/**
 * @author artem
 */
public abstract class HgRepository {

	public static final int TIP = -1;
	// TODO NULLNODEID

	// temp aux marker method
	public static IllegalStateException notImplemented() {
		return new IllegalStateException("Not implemented");
	}

	
	private Changelog changelog;
	private HgManifest manifest;

	private boolean isInvalid = true;
	
	public boolean isInvalid() {
		return this.isInvalid;
	}
	
	protected void setInvalid(boolean invalid) {
		isInvalid = invalid;
	}

	public final Changelog getChangelog() {
		if (this.changelog == null) {
			// might want delegate to protected createChangelog() some day
			RevlogStream content = resolve(toStoragePath("00changelog.i", false)); // XXX perhaps, knowledge about filenames should be in LocalHgRepo?
			this.changelog = new Changelog(this, content);
		}
		return this.changelog;
	}
	
	public final HgManifest getManifest() {
		if (this.manifest == null) {
			RevlogStream content = resolve(toStoragePath("00manifest.i", false));
			this.manifest = new HgManifest(this, content);
		}
		return this.manifest;
	}

	public abstract HgDataFile getFileNode(String path);

	public abstract String getLocation();


	protected abstract String toStoragePath(String path, boolean isData);

	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 */
	protected abstract RevlogStream resolve(String repositoryPath);

	public abstract void status(int rev1, int rev2 /*WorkingDir - TIP, TIP?*/, StatusInspector inspector);

	public interface StatusInspector {
		void modified(String fname);
		void added(String fname);
		void copied(String fnameOrigin, String fnameAdded); // if copied files of no interest, should delegate to self.added(fnameAdded);
		void removed(String fname);
		void clean(String fname);
		void missing(String fname); // aka deleted (tracked by Hg, but not available in FS any more
		void unknown(String fname); // not tracked
		void ignored(String fname);
	}
}
