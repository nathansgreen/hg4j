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
package org.tmatesoft.hg.test;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.core.Nodeid;
import org.tmatesoft.hg.internal.ArrayHelper;
import org.tmatesoft.hg.repo.HgChangelog;
import org.tmatesoft.hg.repo.HgChangelog.RawChangeset;
import org.tmatesoft.hg.repo.HgRepository;
import org.tmatesoft.hg.util.Adaptable;
import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestAuxUtilities {

	@Test
	public void testArrayHelper() {
		String[] initial = {"d", "w", "k", "b", "c", "i", "a", "r", "e", "h" };
		ArrayHelper ah = new ArrayHelper();
		String[] result = initial.clone();
		ah.sort(result);
		String[] restored = restore(result, ah.getReverse());
		Assert.assertArrayEquals(initial, restored);
		//
		// few elements are on the right place from the very start and do not shift during sort.
		// make sure for them we've got correct reversed indexes as well
		initial = new String[] {"d", "h", "c", "b", "k", "i", "a", "r", "e", "w" };
		ah.sort(result = initial.clone());
		restored = restore(result, ah.getReverse());
		Assert.assertArrayEquals(initial, restored);
	}

	private static String[] restore(String[] sorted, int[] sortReverse) {
		String[] rebuilt = new String[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			int indexInOriginal = sortReverse[i];
			rebuilt[indexInOriginal-1] = sorted[i];
		}
		return rebuilt;
	}

	@Test
	public void testCancelSupport() throws Exception {
		HgRepository repository = Configuration.get().find("branches-1"); // any repo with more revisions
		class CancelImpl implements CancelSupport {
			private boolean shallStop = false;
			public void stop() {
				shallStop = true;
			}
			public void checkCancelled() throws CancelledException {
				if (shallStop) {
					throw new CancelledException();
				}
			}
		}
		class InspectorImplementsCancel implements HgChangelog.Inspector, CancelSupport {
			public final int when2stop;
			public int lastVisitet = 0;
			private final CancelImpl cancelImpl = new CancelImpl(); 

			public InspectorImplementsCancel(int limit) {
				when2stop = limit;
			}
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				lastVisitet = revisionNumber;
				if (revisionNumber == when2stop) {
					cancelImpl.stop();
				}
			}

			public void checkCancelled() throws CancelledException {
				cancelImpl.checkCancelled();
			}
		};
		class InspectorImplementsAdaptable implements HgChangelog.Inspector, Adaptable {
			public final int when2stop;
			public int lastVisitet = 0;
			private final CancelImpl cancelImpl = new CancelImpl();
			
			public InspectorImplementsAdaptable(int limit) {
				when2stop = limit;
			}
			
			public void next(int revisionNumber, Nodeid nodeid, RawChangeset cset) {
				lastVisitet = revisionNumber;
				if (revisionNumber == when2stop) {
					cancelImpl.stop();
				}
			}
			@SuppressWarnings("unchecked")
			public <T> T getAdapter(Class<T> adapterClass) {
				if (CancelSupport.class == adapterClass) {
					return (T) cancelImpl;
				}
				return null;
			}
			
		}
		//
		InspectorImplementsCancel insp1;
		repository.getChangelog().all(insp1= new InspectorImplementsCancel(2));
		Assert.assertEquals(insp1.when2stop, insp1.lastVisitet);
		repository.getChangelog().all(insp1 = new InspectorImplementsCancel(12));
		Assert.assertEquals(insp1.when2stop, insp1.lastVisitet);
		//
		InspectorImplementsAdaptable insp2;
		repository.getChangelog().all(insp2= new InspectorImplementsAdaptable(3));
		Assert.assertEquals(insp2.when2stop, insp2.lastVisitet);
		repository.getChangelog().all(insp2 = new InspectorImplementsAdaptable(10));
		Assert.assertEquals(insp2.when2stop, insp2.lastVisitet);
	}
}
