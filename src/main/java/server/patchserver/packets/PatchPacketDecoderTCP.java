package server.patchserver.packets;

import java.io.ByteArrayInputStream;

import server.interfaces.Event;
import server.interfaces.PatchEvent;
import server.patchserver.PatchServerConnection;
import server.tools.Out;

public class PatchPacketDecoderTCP extends ByteArrayInputStream implements PatchEvent{

	public PatchPacketDecoderTCP(byte[] arg0) {
		super(arg0);
	}

// for PatchEvent

	public long getEventTime() {
		return 0;
	}

	public int compareTo(Event o) {
		if (o.getEventTime() == 0) {
			return 0;
		} else {
			return -1;
		}
	}

	public void execute(PatchServerConnection psc) {
		Out.writeln(Out.Error, "Eventexecution Error in Class " + this.getClass().getName());
	}

// for decoding	
	
	public int readShort() {
		return read() | (read() << 8);
	}
/*
	public int readInt() {
		return read() | (read() << 8) | (read() << 16) | (read() << 24);
	}

	public String readCString(int len) {
		String res = new String(buf, pos, len -1);
		pos += len;
		return res;
	}

	public String readEncryptedString(int len, int key) {
		byte[] passwdData = new byte[len];
		for (int i=0; i < len; i++) {
			passwdData[i] = (byte) ((readShort() >> 4) - key);
		}
		return new String(passwdData);
	}
	*/
}


