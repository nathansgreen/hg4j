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

import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/**
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class ReverseIterator<E> implements Iterator<E> {
	private final ListIterator<E> listIterator;
	
	public ReverseIterator(List<E> list) {
		listIterator = list.listIterator(list.size());
	}

	public boolean hasNext() {
		return listIterator.hasPrevious();
	}
	public E next() {
		return listIterator.previous();
	}
	public void remove() {
		listIterator.remove();
	}

	public static <T> Iterable<T> reversed(final List<T> list) {
		return new Iterable<T>() {

			public Iterator<T> iterator() {
				return new ReverseIterator<T>(list);
			}
		};
	}
}