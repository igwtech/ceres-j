package server.tools;

import java.io.IOException;
import java.io.ByteArrayOutputStream;

/**
 *  This class extends ByteArrayOutputStream to make life easier.
 * @author Chris
 *
 */
public class ExtendedByteArrayOutputStream extends ByteArrayOutputStream{
	
	/**
	 * writes the whole int i to the Stream, automatically shifts => array's size
	 * will increase by 4
	 * @param i
	 */
	public void writeInt(int i) {
		write(i);
		write(i >> 8);
		write(i >> 16);
		write(i >> 24);
	}

	/**
	 * writes the whole short i to the Stream, automatically shifts => array's size
	 * will increase by 2
	 * @param i
	 */
	public void writeShort(int i) {
		write(i);
		write(i >> 8);
	}

	/**
	 * writes the whole float f to the Stream, will convert the float to int by using
	 * Float.floatToIntBits(float)automatically shifts 
	 * => array's size will increase by 4
	 * @param f
	 */
	public void writeFloat(float f) {
		writeInt(Float.floatToIntBits(f));
	}

	/**
	 * writes the array to the stream
	 * @param data
	 */
	public void write(byte[] data) {
		try {
			super.write(data);
		} catch (IOException e) {
			// ByteArrayOutputStream.write(byte[]) should not throw
		}
	}

}
