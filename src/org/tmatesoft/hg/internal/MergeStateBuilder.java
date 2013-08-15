/*
 * Copyright (c) 2013 TMate Software Ltd
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

import org.tmatesoft.hg.core.HgIOException;
import org.tmatesoft.hg.repo.HgMergeState;
import org.tmatesoft.hg.util.Path;

/**
 * Constructs merge/state file
 * 
 * @see HgMergeState
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class MergeStateBuilder {
	
	private final Internals repo;

	public MergeStateBuilder(Internals implRepo) {
		repo = implRepo;
	}
	
	public void resolved() {
		throw Internals.notImplemented();
	}

	public void unresolved(Path file) {
		throw Internals.notImplemented();
	}

	public void serialize(Transaction tr) throws HgIOException {
	}
}
