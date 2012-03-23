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
package org.tmatesoft.hg.core;

/**
 * Indicates failure to find repository at specified location
 * XXX may provide information about alternatives tried
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
@SuppressWarnings("serial")
public class HgRepositoryNotFoundException extends HgException {

	private String location;

	public HgRepositoryNotFoundException(String message) {
		super(message);
	}
	
	public HgRepositoryNotFoundException setLocation(String location) {
		this.location = location;
		return this;
	}
	
	public String getLocation() {
		return this.location;
	}
}
