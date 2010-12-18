/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;


/**
 * @author artem
 *
 */
public abstract class HgRepository {

	
	private Changelog changelog;
	private boolean isInvalid = true;
	
	public boolean isInvalid() {
		return this.isInvalid;
	}
	
	protected void setInvalid(boolean invalid) {
		isInvalid = invalid;
	}

	public void log() {
		Changelog clog = getChangelog();
		assert clog != null;
		// TODO get data to the client
	}

	/**
	 * @return
	 */
	private Changelog getChangelog() {
		if (this.changelog == null) {
			this.changelog = new Changelog();
			// TODO init
		}
		return this.changelog;
	}

	public abstract String getLocation();
}
