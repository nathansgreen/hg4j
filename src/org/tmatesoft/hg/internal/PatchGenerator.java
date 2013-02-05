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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.tmatesoft.hg.repo.HgDataFile;
import org.tmatesoft.hg.repo.HgLookup;
import org.tmatesoft.hg.repo.HgRepository;

/**
 * Mercurial cares about changes only up to the line level, e.g. a simple file version dump in manifest looks like (RevlogDump output):
 * 
 *   522:        233748      0        103      17438        433        522      521       -1     756073cf2321df44d3ed0585f2a5754bc8a1b2f6
 *   <PATCH>:
 *   3487..3578, 91:src/org/tmatesoft/hg/core/HgIterateDirection.java\00add61a8a665c5d8f092210767f812fe0d335ac8
 *   
 * I.e. for the {fname}{revision} entry format of manifest, not only {revision} is changed, but the whole line, with unchanged {fname} is recorded
 * in the patch.
 * 
 * Mercurial paper describes reasons for choosing this approach to delta generation, too.
 * 
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class PatchGenerator {

	private Map<ChunkSequence.ByteChain, IntVector> chunk2UseIndex;
	private ChunkSequence seq1, seq2;

	// get filled by #longestMatch, track start of common sequence in seq1 and seq2, respectively
	private int matchStartS1, matchStartS2;
	// get filled by #findMatchingBlocks, track start of changed/unknown sequence in seq1 and seq2
	private int changeStartS1, changeStartS2;

	public void init(byte[] data1, byte[] data2) {
		seq1 = new ChunkSequence(data1);
		seq1.splitByNewlines();
		seq2 = new ChunkSequence(data2);
		seq2.splitByNewlines();
		prepare(seq2);
	}

	private void prepare(ChunkSequence s2) {
		chunk2UseIndex = new HashMap<ChunkSequence.ByteChain, IntVector>();
		for (int i = 0, len = s2.chunkCount(); i < len; i++) {
			ChunkSequence.ByteChain bc = s2.chunk(i);
			IntVector loc = chunk2UseIndex.get(bc);
			if (loc == null) {
				chunk2UseIndex.put(bc, loc = new IntVector());
			}
			loc.add(i);
			// bc.registerUseIn(i) - BEWARE, use of bc here is incorrect
			// in this case need to find the only ByteChain to keep indexes
			// i.e. when there are few equal ByteChain instances, notion of "usedIn" shall be either shared (reference same vector)
			// or kept within only one of them
		}
//		for (ChunkSequence.ByteChain bc : chunk2UseIndex.keySet()) {
//			System.out.printf("%s: {", new String(bc.data()));
//			for (int x : chunk2UseIndex.get(bc).toArray()) {
//				System.out.printf(" %d,", x);
//			}
//			System.out.println("}");
//		}
	}
	
	public void findMatchingBlocks() {
		changeStartS1 = changeStartS2 = 0;
		findMatchingBlocks(0, seq1.chunkCount(), 0, seq2.chunkCount());
		if (changeStartS1 < seq1.chunkCount() || changeStartS2 < seq2.chunkCount()) {
			reportDeltaElement(seq1.chunkCount(), seq2.chunkCount());
		}
	}
	
	/**
	 * implementation based on Python's difflib.py and SequenceMatcher 
	 */
	public int longestMatch(int startS1, int endS1, int startS2, int endS2) {
		matchStartS1 = matchStartS2 = 0;
		int maxLength = 0;
		IntMap<Integer> chunkIndex2MatchCount = new IntMap<Integer>(8);
		for (int i = startS1; i < endS1; i++) {
			ChunkSequence.ByteChain bc = seq1.chunk(i);
			IntMap<Integer> newChunkIndex2MatchCount = new IntMap<Integer>(8);
			IntVector occurencesInS2 = chunk2UseIndex.get(bc);
			if (occurencesInS2 == null) {
				// chunkIndex2MatchCount.clear(); // TODO need clear instead of new instance
				chunkIndex2MatchCount = newChunkIndex2MatchCount;
				continue;
			}
			for (int j : occurencesInS2.toArray()) {
				// s1[i] == s2[j]
				if (j < startS2) {
					continue;
				}
				if (j >= endS2) {
					break;
				}
				int prevChunkMatches = chunkIndex2MatchCount.containsKey(j-1) ? chunkIndex2MatchCount.get(j-1) : 0;
				int k = prevChunkMatches + 1;
				newChunkIndex2MatchCount.put(j, k);
				if (k > maxLength) {
					matchStartS1 = i-k+1;
					matchStartS2 = j-k+1;
					maxLength = k;
				}
			}
			chunkIndex2MatchCount = newChunkIndex2MatchCount;
		}
		return maxLength;
	}
	
	public void findMatchingBlocks(int startS1, int endS1, int startS2, int endS2) {
		int matchLength = longestMatch(startS1, endS1, startS2, endS2);
		if (matchLength > 0) {
			final int saveStartS1 = matchStartS1, saveStartS2 = matchStartS2;
			if (startS1 < matchStartS1 && startS2 < matchStartS2) {
				findMatchingBlocks(startS1, matchStartS1, startS2, matchStartS2);
			}
			reportDeltaElement(saveStartS1, saveStartS2);
			changeStartS1 = saveStartS1 + matchLength;
			changeStartS2 = saveStartS2 + matchLength;
//			System.out.printf("match: from line #%d  and line #%d of length %d\n", saveStartS1, saveStartS2, matchLength);
			if (saveStartS1+matchLength < endS1 && saveStartS2+matchLength < endS2) {
				findMatchingBlocks(saveStartS1 + matchLength, endS1, saveStartS2 + matchLength, endS2);
			}
		}
	}
	
	private Patch deltaCollector;
	
	private void reportDeltaElement(int i, int j) {
		if (changeStartS1 < i) {
			if (changeStartS2 < j) {
				System.out.printf("changed [%d..%d) with [%d..%d)\n", changeStartS1, i, changeStartS2, j);
			} else {
				assert changeStartS2 == j;
				System.out.printf("deleted [%d..%d)\n", changeStartS1, i);
			}
			if (deltaCollector != null) {
				int from = seq1.chunk(changeStartS1).getOffset();
				int to = seq1.chunk(i).getOffset();
				byte[] data = seq2.data(changeStartS2, j);
				deltaCollector.add(from, to, data);
			}
		} else {
			assert changeStartS1 == i;
			if(changeStartS2 < j) {
				System.out.printf("added [%d..%d)\n", changeStartS2, j);
			} else {
				assert changeStartS2 == j;
				System.out.printf("adjustent equal blocks %d, %d and %d,%d\n", changeStartS1, i, changeStartS2, j);
			}
			if (deltaCollector != null) {
				int insPoint = seq1.chunk(changeStartS1).getOffset();
				byte[] data = seq2.data(changeStartS2, j);
				deltaCollector.add(insPoint, insPoint, data);
			}
		}
	}
	
	public static void main(String[] args) throws Exception {
		PatchGenerator pg1 = new PatchGenerator();
		pg1.init("hello".getBytes(), "hello\nworld".getBytes());
		pg1.findMatchingBlocks();
		if (Boolean.TRUE.booleanValue()) {
			return;
		}
		HgRepository repo = new HgLookup().detectFromWorkingDir();
		HgDataFile df = repo.getFileNode("cmdline/org/tmatesoft/hg/console/Main.java");
		ByteArrayChannel bac1, bac2;
		df.content(80, bac1 = new ByteArrayChannel());
		df.content(81, bac2 = new ByteArrayChannel());
//		String s1 = "line 1\nline 2\r\nline 3\n\nline 1\nline 2";
//		String s2 = "abc\ncdef\r\nline 2\r\nline 3\nline 2";
		PatchGenerator pg = new PatchGenerator();
		pg.init(bac1.toArray(), bac2.toArray());
		pg.findMatchingBlocks();
	}
	
	public Patch delta(byte[] prev, byte[] content) {
		deltaCollector = new Patch();
		init(prev, content);
		findMatchingBlocks();
		return deltaCollector;
	}
	
	private static class ChunkSequence {
		
		private final byte[] input;
		private ArrayList<ByteChain> lines;

		public ChunkSequence(byte[] data) {
			input = data;
		}
		
		public void splitByNewlines() {
			lines = new ArrayList<ByteChain>();
			int lastStart = 0;
			for (int i = 0; i < input.length; i++) {
				if (input[i] == '\n') {
					lines.add(new ByteChain(lastStart, i+1));
					lastStart = i+1;
				} else if (input[i] == '\r') {
					if (i+1 < input.length && input[i+1] == '\n') {
						i++;
					}
					lines.add(new ByteChain(lastStart, i+1));
					lastStart = i+1;
				}
			}
			if (lastStart < input.length) {
				lines.add(new ByteChain(lastStart, input.length));
			}
			// empty chunk to keep offset of input end
			lines.add(new ByteChain(input.length, input.length));
		}
		
		public ByteChain chunk(int index) {
			return lines.get(index);
		}
		
		public int chunkCount() {
			return lines.size();
		}
		
		public byte[] data(int chunkFrom, int chunkTo) {
			if (chunkFrom == chunkTo) {
				return new byte[0];
			}
			int from = chunk(chunkFrom).getOffset(), to = chunk(chunkTo).getOffset();
			byte[] rv = new byte[to - from];
			System.arraycopy(input, from, rv, 0, rv.length);
			return rv;
		}

		
		final class ByteChain {
			private final int start, end;
			private final int hash;
			
			ByteChain(int s, int e) {
				start = s;
				end = e;
				hash = calcHash(input, s, e);
			}
			
			/**
			 * byte offset of the this ByteChain inside ChainSequence 
			 */
			public int getOffset() {
				return start;
			}
			
			public byte[] data() {
				byte[] rv = new byte[end - start];
				System.arraycopy(input, start, rv, 0, rv.length);
				return rv;
			}
			
			@Override
			public boolean equals(Object obj) {
				if (obj == null || obj.getClass() != ByteChain.class) {
					return false;
				}
				ByteChain other = (ByteChain) obj;
				if (other.hash != hash || other.end - other.start != end - start) {
					return false;
				}
				return other.match(input, start);
			}
			
			private boolean match(byte[] oi, int from) {
				for (int i = start, j = from; i < end; i++, j++) {
					if (ChunkSequence.this.input[i] != oi[j]) {
						return false;
					}
				}
				return true;
			}
			
			@Override
			public int hashCode() {
				return hash;
			}
			
			@Override
			public String toString() {
				return String.format("[@%d\"%s\"]", start, new String(data()));
			}
		}

		// same as Arrays.hashCode(byte[]), just for a slice of a bigger array
		static int calcHash(byte[] data, int from, int to) {
			int result = 1;
			for (int i = from; i < to; i++) {
				result = 31 * result + data[i];
			}
			return result;
		}
	}
}
