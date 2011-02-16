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
package org.tmatesoft.hg.internal;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.tmatesoft.hg.util.ByteChannel;

/**
 *
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class FilterByteChannel implements ByteChannel {
	private final Filter[] filters;
	private final ByteChannel delegate;
	
	public FilterByteChannel(ByteChannel delegateChannel, Collection<Filter> filtersToApply) {
		if (delegateChannel == null || filtersToApply == null) {
			throw new IllegalArgumentException();
		}
		delegate = delegateChannel;
		filters = filtersToApply.toArray(new Filter[filtersToApply.size()]);
	}

	public int write(ByteBuffer buffer) throws Exception {
		final int srcPos = buffer.position();
		ByteBuffer processed = buffer;
		for (Filter f : filters) {
			// each next filter consumes not more than previous
			// hence total consumed equals position shift in the original buffer
			processed = f.filter(processed);
		}
		delegate.write(processed);
		return buffer.position() - srcPos; // consumed as much from original buffer
	}

}
