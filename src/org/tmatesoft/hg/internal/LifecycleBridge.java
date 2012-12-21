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

import org.tmatesoft.hg.util.CancelSupport;
import org.tmatesoft.hg.util.CancelledException;
import org.tmatesoft.hg.util.ProgressSupport;

/**
 * Bridge low-level life-cycle ({@link Lifecycle}) API with high-level one ({@link ProgressSupport} and {@link CancelSupport}).
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class LifecycleBridge implements Lifecycle {
	private final ProgressSupport progressHelper;
	private final CancelSupport cancelSupport;
	// may be null unless #start() is invoked
	private Callback receiver;
	private CancelledException cancellation;

	
	public LifecycleBridge(ProgressSupport progress, CancelSupport cancel) {
		progressHelper = progress;
		cancelSupport = cancel;
	}

	public void start(int count, Callback callback, Object token) {
		receiver = callback;
		if (progressHelper != null) {
			progressHelper.start(count);
		}
	}

	public void finish(Object token) {
		if (progressHelper != null) {
			progressHelper.done();
		}
		receiver = null;
	}

	// XXX SHALL work without start/finish sequence because
	// HgLogCommand invokes ChangesetTransformer#next directly (i.e. not from
	// inside a library's #range() or similar) to process changesets in unnatural order.
	public void nextStep() {
		if (progressHelper != null) {
			progressHelper.worked(1);
		}
		if (cancelSupport == null) {
			return;
		}
		try { 
			cancelSupport.checkCancelled();
		} catch (CancelledException ex) {
			if (receiver != null) {
				receiver.stop();
			}
			cancellation = ex;
		}
	}

	public void stop() {
		if (receiver != null) {
			receiver.stop();
		}
	}
	
	/**
	 * @return <code>true</code> iff {@link CancelledException} was thrown and catched. Forced stop doesn't count
	 */
	public boolean isCancelled() {
		return cancellation != null;
	}
	
	public CancelledException getCancelOrigin() {
		return cancellation;
	}
}