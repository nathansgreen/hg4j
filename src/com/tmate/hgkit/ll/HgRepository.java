/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;


/**
 * @author artem
 */
public abstract class HgRepository {

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

	public void log() {
		Revlog clog = getChangelog();
		assert clog != null;
		// TODO get data to the client
	}

	public final Changelog getChangelog() {
		if (this.changelog == null) {
			// might want delegate to protected createChangelog() some day
			this.changelog = new Changelog(this);
			// TODO init
		}
		return this.changelog;
	}
	
	public final HgManifest getManifest() {
		if (this.manifest == null) {
			this.manifest = new HgManifest(this);
		}
		return this.manifest;
	}
	
	public final Object/*HgDirstate*/ getDirstate() {
		throw notImplemented();
	}

	public abstract HgDataFile getFileNode(String path);

	public abstract String getLocation();


	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 */
	public abstract RevlogStream resolve(String string);
}
