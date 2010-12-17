/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

/**
 * @author artem
 *
 */
public class HgRepository {

	
	private Changelog changelog;

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
}
