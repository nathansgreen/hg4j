/*
 * Copyright (c) 2012-2013 TMate Software Ltd
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

/**
 * Phases for a changeset is a new functionality in Mercurial 2.1
 * 
 * @since 1.1
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public enum HgPhase {
	
	Public("public"), Draft("draft"), Secret("secret"), Undefined("");

	private final String hgString;

	private HgPhase(String stringRepresentation) {
		hgString = stringRepresentation;
	}

//	public String toMercurialString() {
//		return hgString;
//	}

	public static HgPhase parse(int value) {
		switch (value) {
		case 0 : return Public;
		case 1 : return Draft;
		case 2 : return Secret;
		}
		throw new IllegalArgumentException(String.format("Bad phase index: %d", value));
	}
	
	public static HgPhase parse(String value) {
		if (Public.hgString.equals(value)) {
			return Public;
		}
		if (Draft.hgString.equals(value)) {
			return Draft;
		}
		if (Secret.hgString.equals(value)) {
			return Secret;
		}
		throw new IllegalArgumentException(String.format("Bad phase name: %d", value));
	}
	
	/**
	 * @return integer value Mercurial uses to identify the phase
	 */
	public int mercurialOrdinal() {
		if (this == Undefined) {
			throw new IllegalStateException("Undefined phase is an artifical value, which doesn't possess a valid native mercurial ordinal");
		}
		return ordinal(); // what a coincidence
	}
	
	public String mercurialString() {
		return hgString;
	}
}
