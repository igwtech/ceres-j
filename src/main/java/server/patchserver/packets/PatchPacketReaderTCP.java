package server.patchserver.packets;

import java.io.IOException;
import java.io.InputStream;

import server.interfaces.PatchEvent;
import server.patchserver.PatchServerConnection;
import server.patchserver.packets.client_tcp.PatchHandshakeB;
import server.patchserver.packets.client_tcp.PatchRequestVersion;
import server.patchserver.packets.client_tcp.UnknownClientTCPPacket;

public final class PatchPacketReaderTCP {

	public static void readPacket(InputStream is, PatchServerConnection psc) throws IOException {
		if (is.read() != 0xfe) {
			throw new IOException();
		}
		int size = is.read() | (is.read() << 8);
		if (size < 1) {
			throw new IOException();
		}
		byte[] readbuffer = new byte[size];
		if (size != is.read(readbuffer, 0, size)) {
			throw new IOException();
		}
		psc.addEvent(decode(readbuffer));
	}

	private static PatchEvent decode(byte[] readbuffer) throws IOException {
		PatchPacketDecoderTCP packet = new PatchPacketDecoderTCP(readbuffer);
		switch (packet.read()) {
		case 0x7b:
			return new PatchRequestVersion(readbuffer);
		case 0x80:
			switch (packet.read()) {
			case 0x00:
				switch (packet.read()) {
				case 0x6c:
					return new PatchHandshakeB(readbuffer);
				default:
					return new UnknownClientTCPPacket(readbuffer);
				}
			default:
				return new UnknownClientTCPPacket(readbuffer);
			}
		default :
			return new UnknownClientTCPPacket(readbuffer);
		}
	}
}

/*
 * ===================from patch server :======================

*
 * private byte STARTPATCH[] = { (byte) 0xfe, (byte) 0x0a, (byte) 0x00, (byte)
 * 0x38, (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte)
 * 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
 * 
 * private byte FILEERROR[] = { (byte) 0xfe, (byte) 0x06, (byte) 0x00, (byte)
 * 0x3d, (byte) 0x02, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00 };
 * 
 * //char STARTFILE[]={0xfe, 0x0a, 0x00, 0x3b, 0x02, 0x00, 0x00, 0x00, 0x00,
 * 0x00, 0x00, 0x00, 0x00};
 *

 * 				case  0x7c:
	if (size != 10) {
		break loop;
	}
	patchFile = getPatch(readbuffer[6] & 255);
	if (!patchFile.canRead()) {
		FILEERROR[7] = readbuffer[4];
		FILEERROR[8] = readbuffer[5];
		nos.write(FILEERROR);
	} else {
		int filesize = (int) patchFile.length();
		STARTPATCH[7] = readbuffer[4];
		STARTPATCH[8] = readbuffer[5];
		STARTPATCH[9] = (byte) filesize;
		STARTPATCH[10] = (byte) (filesize >> 8);
		STARTPATCH[11] = (byte) (filesize >> 16);
		STARTPATCH[12] = (byte) (filesize >> 24);
		nos.write(STARTPATCH);
	}
	break;
case 0x7d:
	if (size != 14) {
		break loop;
	}
	patchFile = getPatch(readbuffer[6] & 255);
	int startingPosition = readbuffer[10]
			| (readbuffer[11] << 8) | (readbuffer[12] << 16) //error!
			| (readbuffer[13] << 24);
	int endPosition = (int) patchFile.length();
	if (endPosition - startingPosition > 4000) {
		endPosition = startingPosition + 4000;
	}
	int dataLength = endPosition - startingPosition;
	
	byte[] sendBuffer = new byte[dataLength+13];
	sendBuffer[0]=(byte)0xfe;
	sendBuffer[1]=(byte)(dataLength+10);
	sendBuffer[2]=(byte) ((dataLength+10)>>8);
	sendBuffer[3]=0x39;
	sendBuffer[4]=0x02;
	sendBuffer[5]=0x00;
	sendBuffer[6]=0x00;
	sendBuffer[7]=readbuffer[4];
	sendBuffer[8]=readbuffer[5];
	sendBuffer[9]=(byte)(dataLength);
	sendBuffer[10]=(byte)(dataLength>>8);
	sendBuffer[11]=0x00;
	sendBuffer[12]=0x00;
	
	FileInputStream fis = new FileInputStream(patchFile);
	fis.skip(startingPosition);
	fis.read(sendBuffer, 13, dataLength);
	
	nos.write(sendBuffer);
	break;
	
private File getPatch(int version) {
String number = "000000" + version;
number = number.substring(number.length()-6);
return new File("Patches" + File.separator + "cp" + number + ".pat");
}
*/
