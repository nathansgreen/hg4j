/*
 * Copyright (c) 2012 TMate Software Ltd
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
import java.util.List;
import java.util.Map;

import org.tmatesoft.hg.util.Adaptable;

/**
 * Implementation of {@link Adaptable} that allows to add ("plug")
 * adapters as needed
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class AdapterPlug implements Adaptable {
	private final Map<Class<?>, Object> adapters = new HashMap<Class<?>, Object>();
	private final List<Class<?>> adapterUses = new ArrayList<Class<?>>();
	
	public <T> void attachAdapter(Class<T> adapterClass, T instance) {
		adapters.put(adapterClass, instance);
	}

	public <T> T getAdapter(Class<T> adapterClass) {
		Object instance = adapters.get(adapterClass);
		if (instance != null) {
			adapterUses.add(adapterClass);
			return adapterClass.cast(instance);
		}
		return null;
	}
	
	public int getAdapterUse(Class<?> adapterClass) {
		int uses = 0;
		for (Class<?> c : adapterUses) {
			if (c == adapterClass) {
				uses++;
			}
		}
		return uses;
	}
}