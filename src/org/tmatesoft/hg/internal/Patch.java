/*
 * Copyright (c) 2011-2013 TMate Software Ltd
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Formatter;

/**
 * @see http://mercurial.selenic.com/wiki/BundleFormat
 * in Changelog group description
 * 
 * range [start..end) in original source gets replaced with data of length (do not keep, use data.length instead)
 * range [end(i)..start(i+1)) is copied from the source
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public final class Patch {
	private final IntVector starts, ends;
	private final ArrayList<byte[]> data;
	private final boolean shallNormalize;

	private static byte[] generate(int c) {
		byte[] rv = new byte[c];
		for (int i = 0; i < c; i++) {
			byte x = (byte) ('a' + i);
			rv[i] = x;
		}
		return rv;
	}

	public static void main(String[] args) {
		Patch p1 = new Patch(), p2 = new Patch();
		// simple cases (one element in either patch)
		// III: (1,10 20) & (5,15,15) p2End from [p1End..p1AppliedEnd] (i.e. within p1 range but index is past p2 end index) 
		//  II: (1,10,7) & (3,15,15) insideP2 = true and no more p1 entries
		//  II: (1,1,10) & (3,11,15)
		// independent: (1,10,10) & (15,25,10);  (15, 25, 10) & (1, 10, 10) 
		//   I: (15, 25, 10) & (10, 20, 10). result: [10, 20, 10] [20, 25, 5]
		//  IV: (15, 25, 10) & (10, 30, 20)
		// 
		// cycle with insideP2
		//
		// cycle with insideP1
		//
		// multiple elements in patches (offsets)
		p1.add(15, 25, generate(10));
		p2.add(10, 30, generate(20));
		System.out.println("p1: " + p1);
		System.out.println("p2: " + p2);
		Patch r = p1.apply(p2);
		System.out.println("r: " + r);
	}
	
	public Patch() {
		this(16, false);
	}
	
	public Patch(boolean normalizeOnChange) {
		this(16, normalizeOnChange);
	}

	public Patch(int sizeHint, boolean normalizeOnChange) {
		shallNormalize = normalizeOnChange;
		starts = new IntVector(sizeHint, -1);
		ends = new IntVector(sizeHint, -1);
		data = new ArrayList<byte[]>(sizeHint);
	}
	
	public String toString() {
		StringBuilder sb = new StringBuilder();
		Formatter f = new Formatter(sb);
		for (int i = 0; i < count(); i++) {
			f.format("[%d, %d, %d] ", starts.get(i), ends.get(i), data.get(i).length);
		}
		return sb.toString();
	}
	
	public int count() {
		return data.size();
	}

	// number of bytes this patch will add (or remove, if negative) from the base revision
	public int patchSizeDelta() {
		int rv = 0;
		int prevEnd = 0;
		for (int i = 0, x = data.size(); i < x; i++) {
			final int start = starts.get(i);
			final int len = data.get(i).length;
			rv += start - prevEnd; // would copy from original
			rv += len; // and add new
			prevEnd = ends.get(i);
		}
		rv -= prevEnd;
		return rv;
	}
	
	public byte[] apply(DataAccess baseRevisionContent, int outcomeLen) throws IOException {
		if (outcomeLen == -1) {
			outcomeLen = baseRevisionContent.length() + patchSizeDelta();
		}
		int prevEnd = 0, destIndex = 0;
		byte[] rv = new byte[outcomeLen];
		for (int i = 0, x = data.size(); i < x; i++) {
			final int start = starts.get(i);
			baseRevisionContent.seek(prevEnd);
			// copy source bytes that were not modified (up to start of the record)
			baseRevisionContent.readBytes(rv, destIndex, start - prevEnd);
			destIndex += start - prevEnd;
			// insert new data from the patch, if any
			byte[] d = data.get(i);
			System.arraycopy(d, 0, rv, destIndex, d.length);
			destIndex += d.length;
			prevEnd = ends.get(i);
		}
		baseRevisionContent.seek(prevEnd);
		// copy everything in the source past last record's end
		baseRevisionContent.readBytes(rv, destIndex, (baseRevisionContent.length() - prevEnd));
		return rv;
	}
	
	public void clear() {
		starts.clear();
		ends.clear();
		data.clear();
	}
	
	/**
	 * Initialize instance from stream. Any previous patch information (i.e. if instance if reused) is cleared first.
	 * Read up to the end of DataAccess and interpret data as patch records.
	 */
	public void read(DataAccess da) throws IOException {
		clear();
		while (!da.isEmpty()) {
			readOne(da);
		}
	}

	/**
	 * Caller is responsible to ensure stream got some data to read
	 */
	public void readOne(DataAccess da) throws IOException {
		int s = da.readInt();
		int e = da.readInt();
		int len = da.readInt();
		byte[] src = new byte[len];
		da.readBytes(src, 0, len);
		starts.add(s);
		ends.add(e);
		data.add(src);
	}
	
	/**
	 * @return how many bytes the patch would take if written down using BundleFormat structure (start, end, length, data)
	 */
	public int serializedLength() {
		int totalDataLen = 0;
		for (byte[] d : data) {
			totalDataLen += d.length;
		}
		int prefix = 3 * 4 * count(); // 3 integer fields per entry * sizeof(int) * number of entries
		return prefix + totalDataLen;
	}
	
	/*package-local*/ void serialize(DataSerializer out) throws IOException {
		for (int i = 0, x = data.size(); i < x; i++) {
			final int start = starts.get(i);
			final int end = ends.get(i);
			byte[] d = data.get(i);
			out.writeInt(start, end, d.length);
			out.write(d, 0, d.length);
		}
	}
	
	private void add(Patch p, int i) {
		add(p.starts.get(i), p.ends.get(i), p.data.get(i));
	}

	/*package-local*/ void add(int start, int end, byte[] d) {
		if (start == end && d.length == 0) {
			System.currentTimeMillis();
			return;
		}
		int last;
		if (shallNormalize && (last = starts.size()) > 0) {
			last--;
			if (ends.get(last) == start) {
				byte[] d1 = data.get(last);
				byte[] nd;
				if (d1.length > 0 && d.length > 0) {
					nd = new byte[d1.length + d.length];
					System.arraycopy(d1, 0, nd, 0, d1.length);
					System.arraycopy(d, 0, nd, d1.length, d.length);
				} else {
					nd = d1.length == 0 ? d : d1 ;
				}
				ends.set(last, end);
				data.set(last, nd);
				return;
			}
			// fall-through
		}
		starts.add(start);
		ends.add(end);
		data.add(d);
	}
	
	// copies [start..end) bytes from the d
	private static byte[] subarray(byte[] d, int start, int end) {
		byte[] r = new byte[end-start];
		System.arraycopy(d, start, r, 0, r.length);
		return r;
	}

	/**
	 * Modify this patch with subsequent patch 
	 */
	public /*SHALL BE PUBLIC ONCE TESTING ENDS*/ Patch apply(Patch another) {
		Patch r = new Patch(count() + another.count() * 2, shallNormalize);
		int p1TotalAppliedDelta = 0; // value to add to start and end indexes of the older patch to get their values as if
		// in the patched text, iow, directly comparable with respective indexes from the newer patch.
		int p1EntryStart = 0, p1EntryEnd = 0, p1EntryLen = 0;
		byte[] p1Data = null;
		boolean insideP1entry = false;
		int p2 = 0, p1 = 0;
		final int p2Max = another.count(), p1Max = this.count();
L0:		for (; p2 < p2Max; p2++) {
			int p2EntryStart = another.starts.get(p2);
			int p2EntryEnd = another.ends.get(p2);
			final int p2EntryRange = p2EntryEnd - p2EntryStart;
			final byte[] p2Data = another.data.get(p2);
			boolean insideP2entry = false;
			// when we iterate p1 elements within single p2, we need to remember where p2
			// shall ultimately start in terms of p1
			int p2EntrySavedStart = -1;
			///
			
L1:			while (p1 < p1Max) {
				if (!insideP1entry) {
					p1EntryStart = starts.get(p1);
					p1EntryEnd = ends.get(p1);
					p1Data = data.get(p1);
					p1EntryLen = p1Data.length;
				}// else keep values

				final int p1EntryDelta = p1EntryLen - (p1EntryEnd - p1EntryStart); // number of actually inserted(+) or deleted(-) chars
				final int p1EntryAppliedStart = p1TotalAppliedDelta + p1EntryStart;
				final int p1EntryAppliedEnd = p1EntryAppliedStart + p1EntryLen; // end of j'th patch entry in the text which is source for p2
				
				if (insideP2entry) {
					if (p2EntryEnd <= p1EntryAppliedStart) {
						r.add(p2EntrySavedStart, p2EntryEnd - p1TotalAppliedDelta, p2Data);
						insideP2entry = false;
						continue L0; 
					}
					if (p2EntryEnd >= p1EntryAppliedEnd) {
						// when p2EntryEnd == p1EntryAppliedEnd, I assume p1TotalAppliedDelta can't be used for p2EntryEnd to get it to p1 range, but rather shall be
						// augmented with current p1 entry and at the next p1 entry (likely to hit p1EntryAppliedStart > p2EntryEnd above) would do the rest 
						insideP1entry = false;
						p1++;
						p1TotalAppliedDelta += p1EntryDelta;
						continue L1;
					}
					// p1EntryAppliedStart < p2EntryEnd < p1EntryAppliedEnd
					// can add up to p1EntryEnd here (not only to p1EntryStart), but decided
					// to leave p1 intact here, to avoid delta/range recalculation 
					r.add(p2EntrySavedStart, p1EntryStart, p2Data);
					// consume part of p1 overlapped by current p2
					final int p1DataPartShift = p2EntryEnd - p1EntryAppliedStart;
					// p2EntryEnd < p1EntryAppliedEnd		==> p2EntryEnd < p1EntryAppliedStart + p1EntryLen
					//										==> p2EntryEnd - p1EntryAppliedStart < p1EntryLen
					assert p1DataPartShift < p1EntryLen;
					p1EntryLen -= p1DataPartShift;
					p1Data = subarray(p1Data, p1DataPartShift, p1Data.length);
					p1TotalAppliedDelta += p1DataPartShift;
					insideP1entry = true;
					insideP2entry = false;
					continue L0;
				}

				if (p1EntryAppliedStart < p2EntryStart) {
					if (p1EntryAppliedEnd <= p2EntryStart) { // p1EntryAppliedEnd in fact index of the first char *after* patch
						// completely independent, copy and continue
						r.add(p1EntryStart, p1EntryEnd, p1Data);
						insideP1entry = false;
						p1++;
						// fall-through to get p1TotalAppliedDelta incremented
					} else { // SKETCH: II or III - p2 start inside p1 range
						// remember, p1EntryDelta may be negative
						// shall break j'th entry into few 
						// fix p1's end/length
						// p1EntryAppliedStart < p2EntryStart < p1EntryAppliedEnd, or, alternatively
						// p2EntryStart is from (p1EntryAppliedStart .. p1EntryAppliedStart + p1EntryLen)
						int p1DataPartEnd = p2EntryStart - p1EntryAppliedStart;
						assert p1DataPartEnd < p1EntryLen;
						r.add(p1EntryStart, p1EntryEnd, subarray(p1Data, 0, p1DataPartEnd));
						if (p2EntryEnd <= p1EntryAppliedEnd) { // p2 fits completely into p1
							r.add(p1EntryEnd, p1EntryEnd, p2Data);
							// p2 consumed, p1 has p1EntryLen - p1DataPartEnd - p2EntryRange bytes left to *insert*
							insideP1entry = true;
							p1EntryStart = p1EntryEnd;
							p1EntryLen -= p1DataPartEnd;
							p1EntryLen -= p2EntryRange;
							// p2EntryEnd <= p1EntryAppliedEnd						==> p2EntryEnd <= p1EntryAppliedStart + p1EntryLen
							// -p2EntryStart    									==> p2EntryRange <= p1EntryAppliedStart-p2EntryStart + p1EntryLen
							// p1EntryAppliedStart-p2EntryStart = -p1DataPartEnd	==> p2EntryRange <= p1EntryLen - p1DataEndPart
							//	+p1DataEndPart										==> p2EntryRange + p1DataEndPart <= p1EntryLen
							assert p1EntryLen >= 0;
							// p1EntryLen==0 with insideP1entry == true is nor really good here (gives empty patch elements x;x;0), 
							// however changing <= to < in p2EntryEnd <= p1EntryAppliedEnd above leads to errors
							p1Data = subarray(p1Data, p1DataPartEnd+p2EntryRange, p1Data.length);
							// augment total delta with p1EntryDelta part already consumed (p1EntryLen is pure insertion left for the next step) 
							p1TotalAppliedDelta += (p1EntryDelta - p1EntryLen);
							continue L0;
						} else {
							// p1 is consumed, take next
							insideP1entry = false;
							p1++;
							insideP2entry = true;
							p2EntrySavedStart = p1EntryEnd; // this is how far we've progressed in p1
							// fall-through to get p1TotalAppliedDelta updated with consumed p1
						}
					}
				} else { // p1EntryAppliedStart >= p2EntryStart
					if (p2EntryEnd < p1EntryAppliedStart) {
						// newer patch completely fits between two older patches 
						r.add(p2EntryStart - p1TotalAppliedDelta, p2EntryEnd - p1TotalAppliedDelta, p2Data);
						// SHALL NOT increment p1TotalAppliedDelta as we didn't use any of p1
						continue L0; // next p2 
					} else { // p2EntryEnd >= p1EntryAppliedStart
						// SKETCH: I or IV:
						// p2 start is outside of p1 range.
						//
						// p2DataPartEnd: this is how many bytes prior to p1EntryStart is replaced by p2Data
						int p2DataPartEnd = p1EntryAppliedStart - p2EntryStart;
						if (p2EntryEnd < p1EntryAppliedEnd) {
							// SKETCH: I: copy p2, strip p1 to start from p2EntryEnd, next i (p2)
							insideP1entry = true;
							// replace whole p1 (extended to the left by (p2 \ p1) front bytes)
							r.add(p1EntryStart - p2DataPartEnd, p1EntryEnd, p2Data);
							p1EntryStart = p1EntryEnd;
							// see how much of p1 is left for insertion
							int p1DataPartEnd = p2EntryEnd - p1EntryAppliedStart; // #1
							// Similar, although incorrect: p1DataPartEnd == p2Data.length - p2DataPartEnd; // #2
							// #1(p2EntryStart + p2DataLen) - p1EntryAppliedStart
							// #2 p2DataLen - (p1EntryAppliedStart - p2EntryStart)
							// but this works only in assumption that p2EntryEnd-p2EntryStart == p2Data.length
							//
							// p1EntryAppliedStart <= p2EntryEnd < p1EntryAppliedStart + p1EntryLen
							// -p1EntryAppliedStart (to compare against p1DataPartEnd)  ==> 	0 <= p1DataPartEnd < p1EntryLen
							assert p1DataPartEnd < p1EntryLen;
							assert p1DataPartEnd >= 0;
							p1EntryLen -= p1DataPartEnd;
							p1Data = subarray(p1Data, p1DataPartEnd, p1Data.length);

							// p1TotalAppliedDelta XXX
							p1TotalAppliedDelta += (p1EntryDelta - p1EntryLen);
							continue L0; // next p2;
						} else {
							// p2EntryEnd >= p1EntryAppliedEnd
							// SKETCH IV: skip (rest of) p1 completely, continue the same unless  found p1 with start or end past p2EntryEnd.
							insideP1entry = false;
							// p1 consumed
							p1++;
							insideP2entry = true;
							// extend to the left of p1 by p2 \ p1 front bytes
							p2EntrySavedStart = p1EntryStart - p2DataPartEnd;
							// fall-through to get p1TotalAppliedDelta incremented
						}
					}
				}
				p1TotalAppliedDelta += p1EntryDelta;
			} // while (p1 < p1Max)
			{
				// no more p1 entries, shall close p2 (if it's handled, code above jumps directly to L0)
				// regardless of whether insideP2 is .t
				int s = p2EntrySavedStart != -1 ? p2EntrySavedStart : p2EntryStart - p1TotalAppliedDelta;
				// p2EntrySavedStart != -1 when we started p2 entry processing, but not completed
				// if we handled last p1 entry but didn't start with p2 entry processing, it's -1 and regular p1 delta shall be used
				r.add(s, p2EntryEnd - p1TotalAppliedDelta, p2Data);
			}
		}
		if (p1 < p1Max && insideP1entry) {
			r.add(p1EntryStart, p1EntryEnd, p1Data);
			p1++;
		}
		while (p1 < p1Max) {
			r.add(this, p1);
			p1++;
		};
		return r;
	}

	/**
	 * Combine consecutive regions into one.
	 * XXX NOW only combines two subsequent regions, seems enough for quick test
	 * @return <code>this</code> or new instance of consecutive regions found
	 */
	public Patch normalize() {
		Patch rv = null;
		for (int i = 1, x = data.size(); i < x; i++) {
			if (starts.get(i) == ends.get(i-1)) {
				if (rv == null) {
					rv = new Patch();
					rv.copyOf(this, 0, i-1);
//				} else if (ends.get(i-1) == rv.ends.get(rv.ends.size()-1)) {
//					// "JUST IN CASE" code, i++ below prevents us from getting here
//					// if the last region is the one already merged,
//					// ignore this occurrence (otherwise data(i-1) would get copied again) 
//					continue;
				}
				byte[] d1 = data.get(i-1);
				byte[] d = data.get(i);
				byte[] nd;
				if (d1.length > 0 && d.length > 0) {
					nd = new byte[d1.length + d.length];
					System.arraycopy(d1, 0, nd, 0, d1.length);
					System.arraycopy(d, 0, nd, d1.length, d.length);
				} else {
					nd = d1.length == 0 ? d : d1 ;
				}
				rv.add(starts.get(i-1), ends.get(i), nd);
				i++; // skip i-th element (effectively means we detect only pairs)
				// without this ++, element(i-1) is added to rv once "else" below is hit on the next step
			} else {
				if (rv != null) {
					rv.add(this, i-1);
				}
			}
		}
		if (rv == null) {
			return this;
		} else {
			int last = count() - 1;
			if (starts.get(last) != ends.get(last-1)) {
				rv.add(this, last);
			}
		}
		return rv;
	}
	
	private void copyOf(Patch another, int fromIndex, int upToIndex) {
		while(fromIndex < upToIndex) {
			add(another, fromIndex++);
		}
	}

	public class PatchDataSource implements DataSerializer.DataSource {

		public void serialize(DataSerializer out) throws IOException {
			Patch.this.serialize(out);
		}

		public int serializeLength() {
			return Patch.this.serializedLength();
		}
	}
}