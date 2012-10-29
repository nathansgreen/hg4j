/*
 * Copyright (c) 2012 TMate Software Ltd
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
package org.tmatesoft.hg.repo.ext;

import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.repo.HgRepoConfig;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public abstract class HgExtensionsManager {
	
	public enum HgExt { 
		MQ ("mq"), Rebase("rebase");
		
		private final String mercurialExtName;

		private HgExt(String nativeName) {
			mercurialExtName = nativeName;
		}
		
		String getNativeName() {
			return mercurialExtName;
		}
	}
	
	private final Internals repo;
	private MqManager mqExt;
	private Rebase rebaseExt;

	protected HgExtensionsManager(Internals internalRepo) {
		repo = internalRepo;
	}

	public boolean isEnabled(HgExt e) {
		HgRepoConfig cfg = repo.getRepo().getConfiguration();
		return cfg.getExtensions().isEnabled(e.getNativeName());
	}

	public Rebase getRebaseExtension() {
		if (rebaseExt == null && isEnabled(HgExt.Rebase)) {
			rebaseExt = new Rebase(repo);
		}
		return rebaseExt;
	}
	
	public MqManager getMQ() {
		if (mqExt == null && isEnabled(HgExt.MQ)) {
			mqExt = new MqManager(repo);
		}
		return mqExt;
	}
}
