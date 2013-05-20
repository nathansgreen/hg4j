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
public class DiffHelper<T extends DiffHelper.ChunkSequence<?>> {

	private Map<Object, IntVector> chunk2UseIndex;
	private T seq1, seq2;

	// get filled by #longestMatch, track start of common sequence in seq1 and seq2, respectively
	private int matchStartS1, matchStartS2;

	private MatchInspector<T> matchInspector; 

	public void init(T s1, T s2) {
		seq1 = s1;
		seq2 = s2;
		prepare(s2);
	}
	
	public void init(T s1) {
		if (seq2 == null) {
			throw new IllegalStateException("Use this #init() only when target sequence shall be matched against different origin");
		}
		seq1 = s1;
	}


	private void prepare(T s2) {
		chunk2UseIndex = new HashMap<Object, IntVector>();
		for (int i = 0, len = s2.chunkCount(); i < len; i++) {
			Object bc = s2.chunk(i);
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
	}
	
	public void findMatchingBlocks(MatchInspector<T> insp) {
		insp.begin(seq1, seq2);
		matchInspector = insp;
		findMatchingBlocks(0, seq1.chunkCount(), 0, seq2.chunkCount());
		insp.end();
	}
	
	/**
	 * implementation based on Python's difflib.py and SequenceMatcher 
	 */
	public int longestMatch(int startS1, int endS1, int startS2, int endS2) {
		matchStartS1 = matchStartS2 = 0;
		int maxLength = 0;
		IntMap<Integer> chunkIndex2MatchCount = new IntMap<Integer>(8);
		for (int i = startS1; i < endS1; i++) {
			Object bc = seq1.chunk(i);
			IntVector occurencesInS2 = chunk2UseIndex.get(bc);
			if (occurencesInS2 == null) {
				chunkIndex2MatchCount.clear();
				continue;
			}
			IntMap<Integer> newChunkIndex2MatchCount = new IntMap<Integer>(8);
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
	
	private void findMatchingBlocks(int startS1, int endS1, int startS2, int endS2) {
		int matchLength = longestMatch(startS1, endS1, startS2, endS2);
		if (matchLength > 0) {
			final int saveStartS1 = matchStartS1, saveStartS2 = matchStartS2;
			if (startS1 < matchStartS1 && startS2 < matchStartS2) {
				findMatchingBlocks(startS1, matchStartS1, startS2, matchStartS2);
			}
			matchInspector.match(saveStartS1, saveStartS2, matchLength);
			if (saveStartS1+matchLength < endS1 && saveStartS2+matchLength < endS2) {
				findMatchingBlocks(saveStartS1 + matchLength, endS1, saveStartS2 + matchLength, endS2);
			}
		}
	}
	
	public interface MatchInspector<T extends ChunkSequence<?>> {
		void begin(T s1, T s2);
		void match(int startSeq1, int startSeq2, int matchLength);
		void end();
	}
	
	static class MatchDumpInspector<T extends ChunkSequence<?>> implements MatchInspector<T> {
		private int matchCount;

		public void begin(T s1, T s2) {
			matchCount = 0;
		}

		public void match(int startSeq1, int startSeq2, int matchLength) {
			matchCount++;
			System.out.printf("match #%d: from line #%d  and line #%d of length %d\n", matchCount, startSeq1, startSeq2, matchLength);
		}

		public void end() {
			if (matchCount == 0) {
				System.out.println("NO MATCHES FOUND!");
			}
		}
	}
	
	/**
	 * Matcher implementation that translates "match/equal" notification to a delta-style "added/removed/changed". 
	 */
	public static class DeltaInspector<T extends ChunkSequence<?>> implements MatchInspector<T> {
		protected int changeStartS1, changeStartS2;
		protected T seq1, seq2;

		public void begin(T s1, T s2) {
			seq1 = s1;
			seq2 = s2;
			changeStartS1 = changeStartS2 = 0;
		}

		public void match(int startSeq1, int startSeq2, int matchLength) {
			reportDeltaElement(startSeq1, startSeq2, matchLength);
			changeStartS1 = startSeq1 + matchLength;
			changeStartS2 = startSeq2 + matchLength;
		}

		public void end() {
			if (changeStartS1 < seq1.chunkCount()-1 || changeStartS2 < seq2.chunkCount()-1) {
				reportDeltaElement(seq1.chunkCount()-1, seq2.chunkCount()-1, 0);
			}
		}

		protected void reportDeltaElement(int matchStartSeq1, int matchStartSeq2, int matchLength) {
			if (changeStartS1 < matchStartSeq1) {
				if (changeStartS2 < matchStartSeq2) {
					changed(changeStartS1, matchStartSeq1, changeStartS2, matchStartSeq2);
				} else {
					assert changeStartS2 == matchStartSeq2;
					deleted(matchStartSeq2, changeStartS1, matchStartSeq1);
				}
			} else {
				assert changeStartS1 == matchStartSeq1;
				if(changeStartS2 < matchStartSeq2) {
					added(changeStartS1, changeStartS2, matchStartSeq2);
				} else {
					assert changeStartS2 == matchStartSeq2;
					if (matchStartSeq1 > 0 || matchStartSeq2 > 0) {
						assert false : String.format("adjustent equal blocks %d, %d and %d,%d", changeStartS1, matchStartSeq1, changeStartS2, matchStartSeq2);
					}
				}
			}
			if (matchLength > 0) {
				unchanged(matchStartSeq1, matchStartSeq2, matchLength);
			}
		}

		/**
		 * [s1From..s1To) replaced with [s2From..s2To)
		 */
		protected void changed(int s1From, int s1To, int s2From, int s2To) {
			// NO-OP
		}

		protected void deleted(int s2DeletePoint, int s1From, int s1To) {
			// NO-OP
		}

		protected void added(int s1InsertPoint, int s2From, int s2To) {
			// NO-OP
		}

		protected void unchanged(int s1From, int s2From, int length) {
			// NO-OP
		}
	}
	
	public static class DeltaDumpInspector<T extends ChunkSequence<?>> extends DeltaInspector<T> {

		@Override
		protected void changed(int s1From, int s1To, int s2From, int s2To) {
			System.out.printf("changed [%d..%d) with [%d..%d)\n", s1From, s1To, s2From, s2To);
		}
		
		@Override
		protected void deleted(int s2DeletionPoint, int s1From, int s1To) {
			System.out.printf("deleted [%d..%d)\n", s1From, s1To);
		}
		
		@Override
		protected void added(int s1InsertPoint, int s2From, int s2To) {
			System.out.printf("added [%d..%d) at %d\n", s2From, s2To, s1InsertPoint);
		}

		@Override
		protected void unchanged(int s1From, int s2From, int length) {
			System.out.printf("same [%d..%d) and [%d..%d)\n", s1From, s1From + length, s2From, s2From + length);
		}
	}
	
	/**
	 * Generic sequence of chunk, where chunk is anything comparable to another chunk, e.g. a string or a single char
	 * Sequence diff algorithm above doesn't care about sequence nature.
	 */
	public interface ChunkSequence<T> {
		public T chunk(int index);
		public int chunkCount();
	}
	
	public static final class LineSequence implements ChunkSequence<LineSequence.ByteChain> {
		
		private final byte[] input;
		private ArrayList<ByteChain> lines;

		public LineSequence(byte[] data) {
			input = data;
		}
		
		public static LineSequence newlines(byte[] array) {
			return new LineSequence(array).splitByNewlines();
		}

		// sequence ends with fake, empty line chunk
		public LineSequence splitByNewlines() {
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
			lines.add(new ByteChain(input.length));
			return this;
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

		
		public final class ByteChain {
			private final int start, end;
			private final int hash;
			
			/**
			 * construct a chunk with a sole purpose to keep 
			 * offset of the data end
			 */
			ByteChain(int offset) {
				start = end = offset;
				// ensure this chunk doesn't match trailing chunk of another sequence
				hash = System.identityHashCode(this);
			}
			
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
					if (LineSequence.this.input[i] != oi[j]) {
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
