/**
 * Copyright (c) 2010 Artem Tikhomirov 
 */
package com.tmate.hgkit.ll;

import java.io.DataInput;
import java.io.IOException;

/**
 * @author artem
 *
 */
public class RevlogIndexStreamAccess {

	private final RevlogStream stream;

	// takes RevlogStream. RevlogStream delegates calls for data to this accessor, which in turn refers back to RevlogStream to get
	// correct [Input|Data]Stream according to revlog version (Revlogv0 or RevlogNG)

	public RevlogIndexStreamAccess(RevlogStream stream) {
		this.stream = stream;
		// TODO Auto-generated constructor stub
	}

	
	void readRevlogV0Record() throws IOException {
		DataInput di = null; //FIXME stream.getIndexStream();
		int offset = di.readInt();
		int compressedLen = di.readInt();
		int baseRevision = di.readInt();
		int linkRevision = di.readInt();
//		int r = (((buf[0] & 0xff) << 24) | ((buf[1] & 0xff) << 16) | ((buf[2] & 0xff) << 8) | (buf[3] & 0xff));
		byte[] buf = new byte[20];
		di.readFully(buf, 0, 20);
		Object nodeidOwn = buf.clone();
		// XXX nodeid as an Object with hash/equals?
		di.readFully(buf, 0, 20);
		Object nodeidParent1 = buf.clone();
		di.readFully(buf, 0, 20);
		Object nodeidParent2 = buf.clone();
	}
	
	// another subclass?
	void readRevlogNGRecord() throws IOException {
		DataInput di = null; //FIXME stream.getIndexStream();
		long l = di.readLong();
		long offset = l >>> 16;
		int flags = (int) (l & 0X0FFFF);
		int compressedLen = di.readInt();
		int actualLen = di.readInt();
		int baseRevision = di.readInt();
		int linkRevision = di.readInt();
		int parent1Revision = di.readInt();
		int parent2Revision = di.readInt();
		byte[] buf = new byte[32];
		di.readFully(buf, 0, 20+12);
		Object nodeid = buf/*[0..20]*/;
		
	}
}
