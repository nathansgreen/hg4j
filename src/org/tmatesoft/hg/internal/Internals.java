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
package org.tmatesoft.hg.internal;

import static org.tmatesoft.hg.internal.RequiresFile.*;

import java.util.ArrayList;
import java.util.List;

import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.PathRewrite;

/**
 * Fields/members that shall not be visible  
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class Internals {
	
	private int revlogVersion = 0;
	private int requiresFlags = 0;
	private List<Filter.Factory> filterFactories;
	

	public Internals() {
	}

	public/*for tests, otherwise pkg*/ void setStorageConfig(int version, int flags) {
		revlogVersion = version;
		requiresFlags = flags;
	}

	// XXX perhaps, should keep both fields right here, not in the HgRepository
	public PathRewrite buildDataFilesHelper() {
		return new StoragePathHelper((requiresFlags & STORE) != 0, (requiresFlags & FNCACHE) != 0, (requiresFlags & DOTENCODE) != 0);
	}

	public PathRewrite buildRepositoryFilesHelper() {
		if ((requiresFlags & STORE) != 0) {
			return new PathRewrite() {
				public String rewrite(String path) {
					return "store/" + path;
				}
			};
		} else {
			return new PathRewrite() {
				public String rewrite(String path) {
					//no-op
					return path;
				}
			};
		}
	}

	public ConfigFile newConfigFile() {
		return new ConfigFile();
	}

	public List<Filter.Factory> getFilters(HgRepository hgRepo, ConfigFile cfg) {
		if (filterFactories == null) {
			filterFactories = new ArrayList<Filter.Factory>();
			if (cfg.hasEnabledExtension("eol")) {
				NewlineFilter.Factory ff = new NewlineFilter.Factory();
				ff.initialize(hgRepo, cfg);
				filterFactories.add(ff);
			}
			if (cfg.hasEnabledExtension("keyword")) {
				KeywordFilter.Factory ff = new KeywordFilter.Factory();
				ff.initialize(hgRepo, cfg);
				filterFactories.add(ff);
			}
		}
		return filterFactories;
	}
}
