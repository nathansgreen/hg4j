/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

/**
 * Extends Revlog/uses RevlogStream?
 * ? name:HgFileNode?
 * @author artem
 */
public class HgDataFile extends Revlog {

	private final String path;
	
	/*package-local*/HgDataFile(HgRepository hgRepo) {
		super(hgRepo);
	}

	public String getPath() {
		return path; // hgRepo.backresolve(this) -> name?
	}

	private static final int TIP = -2;

	public byte[] content() {
		return content(TIP);
	}
	
	public byte[] content(int revision) {
		throw HgRepository.notImplemented();
	}
}
