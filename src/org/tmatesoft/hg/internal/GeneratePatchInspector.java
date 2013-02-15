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

import org.tmatesoft.hg.internal.PatchGenerator.DeltaInspector;
import org.tmatesoft.hg.internal.PatchGenerator.LineSequence;

class GeneratePatchInspector extends DeltaInspector<LineSequence> {
	private final Patch deltaCollector;
	
	GeneratePatchInspector(Patch p) {
		assert p != null;
		deltaCollector = p;
	}
	
	public static Patch delta(byte[] prev, byte[] content) {
		Patch rv = new Patch();
		PatchGenerator<LineSequence> pg = new PatchGenerator<LineSequence>();
		pg.init(new LineSequence(prev).splitByNewlines(), new LineSequence(content).splitByNewlines());
		pg.findMatchingBlocks(new GeneratePatchInspector(rv));
		return rv;
	}

	@Override
	protected void changed(int s1From, int s1To, int s2From, int s2To) {
		int from = seq1.chunk(s1From).getOffset();
		int to = seq1.chunk(s1To).getOffset();
		byte[] data = seq2.data(s2From, s2To);
		deltaCollector.add(from, to, data);
	}
	
	@Override
	protected void deleted(int s2DeletionPoint, int s1From, int s1To) {
		int from = seq1.chunk(s1From).getOffset();
		int to = seq1.chunk(s1To).getOffset();
		deltaCollector.add(from, to, new byte[0]);
	}
	
	@Override
	protected void added(int s1InsertPoint, int s2From, int s2To) {
		int insPoint = seq1.chunk(s1InsertPoint).getOffset();
		byte[] data = seq2.data(s2From, s2To);
		deltaCollector.add(insPoint, insPoint, data);
	}
}