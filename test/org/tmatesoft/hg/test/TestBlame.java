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
package org.tmatesoft.hg.test;

import org.junit.Test;
import org.tmatesoft.hg.internal.AnnotateFacility;
import org.tmatesoft.hg.internal.AnnotateFacility.AddBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.ChangeBlock;
import org.tmatesoft.hg.internal.AnnotateFacility.DeleteBlock;
import org.tmatesoft.hg.internal.IntMap;
import org.tmatesoft.hg.internal.AnnotateFacility.Block;
import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestBlame {

	
	@Test
	public void testSingleParentBlame() throws Exception {
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		HgDataFile df = repo.getFileNode("src/org/tmatesoft/hg/internal/PatchGenerator.java");
		final IntMap<String> linesOld= new IntMap<String>(100);
		final IntMap<String> linesNew = new IntMap<String>(100);
		new AnnotateFacility().annotate(df, 539, new AnnotateFacility.Inspector() {
			
			public void same(Block block) {
				// TODO Auto-generated method stub
				
			}
			
			public void deleted(DeleteBlock block) {
				String[] lines = block.removedLines();
				assert lines.length == block.totalRemovedLines();
				for (int i = 0, ln = block.firstRemovedLine(); i < lines.length; i++, ln++) {
					linesOld.put(ln, String.format("%3d:---:%s", ln, lines[i]));
				}
			}
			
			public void changed(ChangeBlock block) {
				deleted(block);
				added(block);
			}
			
			public void added(AddBlock block) {
				String[] addedLines = block.addedLines();
				assert addedLines.length == block.totalAddedLines();
				for (int i = 0, ln = block.firstAddedLine(), x = addedLines.length; i < x; i++, ln++) {
					linesNew.put(ln, String.format("%3d:+++:%s", ln, addedLines[i]));
				}
			}
		});
		
		System.out.println("Changes to old revision:");
		for (int i = linesOld.firstKey(), x = linesOld.lastKey(); i < x; i++) {
			if (linesOld.containsKey(i)) {
				System.out.println(linesOld.get(i));
			}
		}

		System.out.println("Changes in the new revision:");
		for (int i = linesNew.firstKey(), x = linesNew.lastKey(); i < x; i++) {
			if (linesNew.containsKey(i)) {
				System.out.println(linesNew.get(i));
			}
		}
	}
	
	public static void main(String[] args) throws Exception {
		new TestBlame().testSingleParentBlame();
	}
}
