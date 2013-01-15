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

import org.junit.Assert;
import org.junit.Test;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestCheckout {


	@Test
	public void testCleanCheckoutInEmptyDir() {
		Assert.fail("clone without update, checkout, status");
	}

	@Test
	public void testCleanCheckoutInDirtyDir() {
		Assert.fail("Make sure WC is cleared prior to clean checkout");
	}

	@Test
	public void testBranchCheckout() {
		Assert.fail("Make sure branch file is written");
	}
}
