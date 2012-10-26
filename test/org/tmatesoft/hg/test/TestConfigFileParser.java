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
package org.tmatesoft.hg.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;
import org.tmatesoft.hg.internal.ConfigFileParser;

/**
 * 
 * @author Artem Tikhomirov
 * @author TMate Software Ltd.
 */
public class TestConfigFileParser {

	@Test
	public void testParseOnly() throws IOException {
		doTest("".getBytes(), new byte[0]);
		// line comments
		byte[] inp = " # line comment \n; and another one".getBytes();
		doTest(inp, inp);
		// comments inside sections
		inp = "[section1]\nkey1 = value\n # line comment\n[section2]\nkey2 = ;just presence\n".getBytes();
		doTest(inp, inp);
		// empty value
		inp = "[section1]\nkey1 = \n".getBytes();
		doTest(inp, inp);
		// multiline values
		inp = "[section1]\nkey1 = a,\n  b,\n  c\nkey2=\n  xyz\n".getBytes();
		doTest(inp, inp);
		// entry without EOL
		inp = "[section1]\nkey1 = value".getBytes();
		doTest(inp, inp);
		// empty section
		inp = "[section1]\nkey1 = value\n[section2]\n".getBytes();
		doTest(inp, inp);
	}
	
	@Test
	public void testLookup() throws IOException {
		byte[] inp = "[section1]\nkey1 = a,\n  b,\n  c\nkey2=\n  xyz\n\n[section2]\nkey3=\n".getBytes();
		doTest(inp, inp, new Inspector() {
			
			public void visit(ConfigFileParser p) {
				Assert.assertTrue(p.exists("section1", "key1"));
				Assert.assertTrue(p.exists("section1", "key2"));
				Assert.assertFalse(p.exists("section1", "key3"));
				
				Assert.assertTrue(p.exists("section2", "key3"));
				Assert.assertFalse(p.exists("section2", "key1"));
				Assert.assertFalse(p.exists("section2", "key2"));
			}
		});
	}

	@Test
	public void testAddChangeEntries() throws IOException {
		byte[] inp = "\n; line comment1\n[sect-a]\nkey1 = value1\n\n[sect-b]\nkey2=value2\n".getBytes();
		byte[] exp = "\n; line comment1\n[sect-a]\nkey1 = value1\nkey3 = value3\n\n[sect-b]\nkey2=valueX\n".getBytes();
		doTest(inp, exp, new Inspector() {
			
			public void visit(ConfigFileParser p) {
				Assert.assertTrue(p.exists("sect-b", "key2"));
				p.add("sect-a", "key3", "value3");
				p.change("sect-b", "key2", "valueX");
			}
		});
	}

	@Test
	public void testAdditionTwoSectionsSameName() throws IOException {
		byte[] inp = "[sect-a]\nkey1=value1\n\n[sect-b]\nkey2=\n\n[sect-a]\nkey3=value3\n".getBytes();
		byte[] exp = "[sect-a]\nkey1=value1\nkey4 = value4\n\n[sect-b]\nkey2=\n\n[sect-a]\nkey3=value3\n".getBytes();
		doTest(inp, exp, new Inspector() {
			
			public void visit(ConfigFileParser p) {
				p.add("sect-a", "key4", "value4");
			}
		});
	}
	
	@Test
	public void testDeleteTwoSubsequentKeys() throws IOException{
		byte[] inp = "# line comment1\n\n[sect-a]\nkey1=value1\nkey2=value2\n#line comment2\nkey3=value3\n".getBytes();
		byte[] exp = "# line comment1\n\n[sect-a]\n\n\n#line comment2\nkey3=value3\n".getBytes();
		doTest(inp, exp, new Inspector() {
			
			public void visit(ConfigFileParser p) {
				p.delete("sect-a", "key1");
				p.delete("sect-a", "key2");
			}
		});
	}
	
	@Test
	public void testDeleteLastKeyInSection() throws IOException {
		String text1 = "[sect-a]\nkey0 = value 0\n%skey1=value1\n%s[sect-b]\nkey3=value3\n";
		String text2 = "[sect-a]\nkey0 = value 0\n%s\n%s[sect-b]\nkey3=value3\n";
		withTwoCommentsDeleteKey1(text1, text2);
	}

	@Test
	public void testDeleteFirstKeyInSection() throws IOException {
		String text1 = "[sect-a]\n%skey1=value1\n%skey2 = value 2\n[sect-b]\nkey3=value3\n";
		String text2 = "[sect-a]\n%s\n%skey2 = value 2\n[sect-b]\nkey3=value3\n";
		withTwoCommentsDeleteKey1(text1, text2);
	}
	
	@Test
	public void testDeleteOnlyKeyInSection() throws IOException {
		String text1 = "[sect-a]\n%skey1=value1\n%s[sect-b]\nkey3=value3\n";
		String text2 = "[sect-a]\n%s\n%s[sect-b]\nkey3=value3\n";
		withTwoCommentsDeleteKey1(text1, text2);
	}
	
	@Test
	public void testAddNewSection() throws IOException {
		byte[] inp = "[sect-a]\nkey1=value1\n".getBytes();
		byte[] exp = "[sect-a]\nkey1=value1\n\n[sect-b]\nkey2 = value2\n".getBytes();
		doTest(inp, exp, new Inspector() {
			
			public void visit(ConfigFileParser p) {
				p.add("sect-b", "key2", "value2");
			}
		});
	}
	
	private void withTwoCommentsDeleteKey1(String text1, String text2) throws IOException {
		String comment = "# line comment\n";
		Inspector insp = new Inspector() {
			
			public void visit(ConfigFileParser p) {
				p.delete("sect-a", "key1");
			}
		};

		byte[] inp = String.format(text1, "", "").getBytes();
		byte[] exp = String.format(text2, "", "").getBytes();
		doTest(inp, exp, insp);
		inp = String.format(text1, comment, "").getBytes();
		exp = String.format(text2, comment, "").getBytes();
		doTest(inp, exp, insp);
		inp = String.format(text1, "", comment).getBytes();
		exp = String.format(text2, "", comment).getBytes();
		doTest(inp, exp, insp);
		inp = String.format(text1, comment, comment).getBytes();
		exp = String.format(text2, comment, comment).getBytes();
		doTest(inp, exp, insp);
	}

	@Test
	public void testAddEntryToEmptySection() throws IOException {
		String text1 = "[sect-a]\n%s[sect-b]\nkey3=value3\n";
		String text2 = "[sect-a]\n%skey1 = value1\n[sect-b]\nkey3=value3\n";
		String comment = "# line comment2\n";
		Inspector insp = new Inspector() {
			
			public void visit(ConfigFileParser p) {
				p.add("sect-a", "key1", "value1");
			}
		};

		byte[] inp = String.format(text1, "").getBytes();
		byte[] exp = String.format(text2, "").getBytes();
		doTest(inp, exp, insp);
		inp = String.format(text1, comment).getBytes();
		exp = String.format(text2, comment).getBytes();
		doTest(inp, exp, insp);
	}
	

	private void doTest(byte[] input, byte[] expected) throws IOException {
		doTest(input, expected, null);
	}

	private void doTest(byte[] input, byte[] expected, Inspector insp) throws IOException {
		ConfigFileParser p = new ConfigFileParser();
		p.parse(new ByteArrayInputStream(input));
		if (insp != null) {
			insp.visit(p);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);
		p.update(out);
		byte[] result = out.toByteArray();
		Assert.assertArrayEquals(expected, result);
	}

	interface Inspector {
		void visit(ConfigFileParser p);
	}
}
