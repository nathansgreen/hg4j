/*
 * Copyright (c) 2010, 2011 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import org.tmatesoft.hg.core.Path;
import org.tmatesoft.hg.util.PathRewrite;


/**
 * Shall be as state-less as possible, all the caching happens outside the repo, in commands/walkers
 * @author artem
 */
public abstract class HgRepository {

	public static final int TIP = -1;
	public static final int BAD_REVISION = Integer.MIN_VALUE;
	public static final int WORKING_COPY = -2;

	// temp aux marker method
	public static IllegalStateException notImplemented() {
		return new IllegalStateException("Not implemented");
	}

	private Changelog changelog;
	private HgManifest manifest;
	private HgTags tags;

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
	
	public final HgTags getTags() {
		if (tags == null) {
			tags = createTags();
		}
		return tags;
	}
	
	protected abstract HgTags createTags();

	public abstract HgDataFile getFileNode(String path);
	public abstract HgDataFile getFileNode(Path path);

	public abstract String getLocation();
	
	public abstract PathRewrite getPathHelper();


	protected abstract String toStoragePath(String path, boolean isData);

	/**
	 * Perhaps, should be separate interface, like ContentLookup
	 */
	protected abstract RevlogStream resolve(String repositoryPath);
}
