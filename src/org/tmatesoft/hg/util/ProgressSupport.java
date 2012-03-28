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
package org.tmatesoft.hg.util;

/**
 * Mix-in to report progress of a long-running operation
 *  
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public interface ProgressSupport {

	// -1 for unspecified?
	public void start(int totalUnits);
	public void worked(int units); // fraction of totalUnits from #start(int)
	// XXX have to specify whether PS implementors may expect #done regardless of job completion (i.e. in case of cancellation) 
	public void done();

	static class Factory {

		/**
		 * @param target object that might be capable to report progress. Can be <code>null</code>
		 * @return support object extracted from target or an empty, no-op implementation
		 */
		public static ProgressSupport get(Object target) {
			ProgressSupport ps = Adaptable.Factory.getAdapter(target, ProgressSupport.class, null);
			if (ps != null) {
				return ps;
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
	
	class Sub implements ProgressSupport {
		private int perChildWorkUnitMultiplier; // to multiply child ps units
		private int perChildWorkUnitDivisor; // to scale down to parent ps units
		private int unitsConsumed; // parent ps units consumed so far
		private int fraction = 0; // leftovers of previous not completely consumed work units
		private final ProgressSupport ps;
		private final int psUnits; // total parent ps units

		public Sub(ProgressSupport parent, int parentUnits) {
			if (parent == null) {
				throw new IllegalArgumentException();
			}
			ps = parent;
			psUnits = parentUnits;
		}

		public void start(int totalUnits) {
//			perChildWorkUnit = (psUnits*100) / totalUnits;
			perChildWorkUnitDivisor = 10 * totalUnits;
			perChildWorkUnitMultiplier = psUnits * perChildWorkUnitDivisor / totalUnits;
			
		}

		public void worked(int worked) {
			int x = fraction + worked * perChildWorkUnitMultiplier;
			int u = x / perChildWorkUnitDivisor;
			fraction = x % perChildWorkUnitDivisor;
			if (u > 0) {
				ps.worked(u);
				unitsConsumed += u;
			}
		}

		public void done() {
			ps.worked(psUnits - unitsConsumed);
		}
	}
	
	interface Target<T> {
		T set(ProgressSupport ps);
	}
}
