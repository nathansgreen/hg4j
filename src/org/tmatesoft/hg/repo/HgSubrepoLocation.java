/*
 * Copyright (c) 2011-2012 TMate Software Ltd
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

import java.io.File;

import org.tmatesoft.hg.core.HgRepositoryNotFoundException;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.Internals;
import org.tmatesoft.hg.util.Path;

/**
 * Descriptor for subrepository location
 * 
 * @see http://mercurial.selenic.com/wiki/Subrepository
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class HgSubrepoLocation {
	
	private final HgRepository owner;
	private final Kind kind;
	private final Path location;
	private final String source;
	private final Nodeid revInfo;

	public enum Kind { Hg, SVN, Git, }
	
	/**
	 * 
	 * @param parentRepo
	 * @param repoLocation path, shall be valid directory (i.e. even if .hgsub doesn't specify trailing slash, this one shall)
	 * @param actualLocation
	 * @param type
	 * @param revision may be <code>null</code>
	 */
	/*package-local*/ HgSubrepoLocation(HgRepository parentRepo, Path repoLocation, String actualLocation, Kind type, Nodeid revision) {
		owner = parentRepo;
		location = repoLocation;
		source = actualLocation;
		kind = type;
		revInfo = revision;
	}
	
	/**
	 * Sub-repository's location within owning repository, always directory, <code>path/to/nested</code>.
	 * <p>
	 * May differ from left-hand, key value from <code>.hgsub</code> if the latter doesn't include trailing slash, which is required 
	 * for {@link Path} objects
	 * 
	 * @return path to nested repository relative to owner's location
	 */
	public Path getLocation() {
		return location;
	}

	/**
	 * Right-hand value from <code>.hgsub</code>, with <code>[kind]</code> stripped, if any.
	 * @return sub-repository's source
	 */
	public String getSource() {
		return source;
	}
	
	/**
	 * Sub-repository kind, either Mercurial, Subversion or Git
	 * @return one of predefined constants
	 */
	public Kind getType() {
		return kind;
	}
	
	/**
	 * For a nested repository that has been committed at least once, returns
	 * its revision as known from <code>.hgsubstate</code>
	 * 
	 * <p>Note, this revision belongs to the nested repository history, not that of owning repository.
	 * 
	 * @return revision of the nested repository, or <code>null</code> if not yet committed
	 */
	public Nodeid getRevision() {
		return revInfo;
	}

	/**
	 * Answers whether this sub repository has ever been part of a commit of the owner repository
	 * 
	 * @return <code>true</code> if owning repository records {@link #getRevision() revision} of this sub-repository  
	 */
	public boolean isCommitted() {
		return revInfo != null;
	}
	
	/**
	 * Answers whether there are local changes in the sub-repository,  
	 * @return <code>true</code> if it's dirty
	 */
	public boolean hasChanges() {
		throw Internals.notImplemented();
	}

	/**
	 * Access repository that owns nested one described by this object
	 */
	public HgRepository getOwner() {
		return owner;
	}

	/**
	 * Access nested repository as a full-fledged Mercurial repository
	 * 
	 * @return object to access sub-repository
	 * @throws HgRepositoryNotFoundException if failed to find repository
	 * @throws HgRuntimeException subclass thereof to indicate issues with the library. <em>Runtime exception</em>
	 */
	public HgRepository getRepo() throws HgRepositoryNotFoundException {
		if (kind != Kind.Hg) {
			throw new HgInvalidStateException(String.format("Unsupported subrepository %s", kind));
		}
		return new HgLookup().detect(new File(owner.getWorkingDir(), source));
	}
}
