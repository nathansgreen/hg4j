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
package org.tmatesoft.hg.util;

/**
 * Mix-in to report progress of a long-running operation
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface ProgressSupport {

	public void start(int totalUnits);
	public void worked(int units);
	public void done();

	static class Factory {

		/**
		 * @param target object that might be capable to report progress. Can be <code>null</code>
		 * @return support object extracted from target or an empty, no-op implementation
		 */
		public static ProgressSupport get(Object target) {
			if (target instanceof ProgressSupport) {
				return (ProgressSupport) target;
			}
			if (target instanceof Adaptable) {
				ProgressSupport ps = ((Adaptable) target).getAdapter(ProgressSupport.class);
				if (ps != null) {
					return ps;
				}
			}
			return new ProgressSupport() {
				public void start(int totalUnits) {
				}
				public void worked(int units) {
				}
				public void done() {
				}
			};
		}
	}
}
