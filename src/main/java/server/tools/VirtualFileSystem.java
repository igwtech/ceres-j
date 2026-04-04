package server.tools;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.InflaterInputStream;

public class VirtualFileSystem {

	public static InputStream getFileInputStream(String filename) {
		File f;
		
		String path = Config.getProperty("NC2ClientPath");
		if (path.charAt(path.length()-1) != File.separatorChar)
			path += File.separator;
		
		//first try decompressed file
		String convertedFilename = filename.replace('\\', File.separatorChar);
		f = new File(path + convertedFilename);
		if (f.canRead()) {
			try {
				return new FileInputStream(f);
			} catch (FileNotFoundException e) {
				// File not found despite canRead(); fall through to try compressed
			}
		}
		
		//second try compressed single file
		int splitpoint = convertedFilename.lastIndexOf(File.separator) +1;
		String pathOfFilename = convertedFilename.substring(0,splitpoint);
		String filenameAlone = convertedFilename.substring(splitpoint);
		f = new File(path + pathOfFilename + "pak_" + filenameAlone);
		if (f.canRead()) {
			try {
				FileInputStream fis = new FileInputStream(f);
				fis.skip(16);
				return new InflaterInputStream(fis);
			} catch (IOException e) {
				// Failed to read compressed single file; fall through to try archive
			}
		}

		//third try compressed archive
		
		String firstFolder = filename.substring(0, filename.indexOf("\\"));
		String restOfFilename = filename.substring(filename.indexOf("\\")+1);
		f = new File(path + firstFolder + ".pak");
		if (f.canRead()) {
			try {
				int startPosition = -1;
				int compressedSize = 0;
				FileInputStream fis = new FileInputStream(f);
				fis.skip(4);
				int entries = readInt(fis);
				for (int i = 0; i < entries; i++) {
					fis.skip(4);
					startPosition = readInt(fis);
					compressedSize = readInt(fis);
					fis.skip(4);
					byte[] name = new byte[readInt(fis) -1];
					fis.read(name);
					fis.skip(1);
					String filename2 = new String(name);
					if (filename2.equals(restOfFilename))
						break;
				}
				if (startPosition != -1) {
					fis = new FileInputStream(f);
					fis.skip(startPosition);
					byte[] inputData = new byte[compressedSize];
					fis.read(inputData);
					return new InflaterInputStream(new ByteArrayInputStream(inputData));
					// this wont work for some sound files, which arent compressed, but we dont need them
				}
			} catch (IOException e) {
				// Failed to read from compressed archive
			}

		}
		//give up
		return null;
	}
	
	private static int readInt(FileInputStream fis) throws IOException {
		return fis.read() | fis.read() << 8 | fis.read() << 16 | fis.read() << 24;
	}
}
