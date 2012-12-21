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

import org.tmatesoft.hg.util.Adaptable;

/**
 * Save callback and delegate to another lifecycle instance, if any
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class LifecycleProxy implements Lifecycle {
	
	private Callback lifecycleCallback;
	private Lifecycle target;
	
	public LifecycleProxy() {
	}

	public LifecycleProxy(Object delegate) {
		init(delegate);
	}
	

	public void start(int count, Callback callback, Object token) {
		lifecycleCallback = callback;
		if (target != null) {
			target.start(count, callback, token);
		}
	}

	public void finish(Object token) {
		if (target != null) {
			target.finish(token);
		}
		lifecycleCallback = null;
	}

	public void init(Object delegate) {
		target = Adaptable.Factory.getAdapter(delegate, Lifecycle.class, null);
	}

	public void stop() {
		assert lifecycleCallback != null;
		if (lifecycleCallback != null) {
			lifecycleCallback.stop();
		}
	}
}