package server.gameserver.packets;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import server.tools.Debug;

/**
 * Functional test for {@link GamePacketReaderUDP#logSubPacketDiagnostic}.
 *
 * <p>The diagnostic block produces ~90 lines/sec/player when active.
 * It MUST stay silent unless the operator opts in via {@code Debug =
 * subPackets}, otherwise the live server log drowns in noise. This
 * test pins that behaviour.
 */
public class GamePacketReaderUDPDiagnosticGateTest {

    private PrintStream savedOut;
    private boolean savedFlag;

    @Before
    public void setUp() throws Exception {
        savedOut = System.out;
        savedFlag = readFlag();
    }

    @After
    public void tearDown() throws Exception {
        System.setOut(savedOut);
        writeFlag(savedFlag);
    }

    private static boolean readFlag() throws Exception {
        Field f = Debug.class.getDeclaredField("debugSubPackets");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static void writeFlag(boolean v) throws Exception {
        Field f = Debug.class.getDeclaredField("debugSubPackets");
        f.setAccessible(true);
        f.setBoolean(null, v);
    }

    private static String captureWith(Runnable body) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream cap = new PrintStream(buf);
        PrintStream prev = System.out;
        System.setOut(cap);
        try {
            body.run();
        } finally {
            System.setOut(prev);
        }
        return buf.toString();
    }

    private static void invoke(byte[] sub, int size) throws Exception {
        Method m = GamePacketReaderUDP.class.getDeclaredMethod(
                "logSubPacketDiagnostic", byte[].class, int.class);
        m.setAccessible(true);
        m.invoke(null, sub, size);
    }

    @Test
    public void disabledFlagProducesNoOutput() throws Exception {
        writeFlag(false);
        // 0x03/0x1f payload — would be one of the chattiest cases
        // when enabled. With flag off it must emit nothing.
        byte[] sub = new byte[]{0x03, 0x42, 0x00, 0x1f, 0x05, 0x00, 0x3d, 0x11};
        String out = captureWith(() -> {
            try { invoke(sub, sub.length); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertEquals("flag off must emit zero bytes",
                "", out);
    }

    @Test
    public void enabledFlagEmitsSizeAndTypeForAnySubPacket() throws Exception {
        writeFlag(true);
        // 0x0b CPing — top-level, no inner sub.
        byte[] sub = new byte[]{0x0b, 0x65, (byte) 0xd0, 0x6d, 0x00};
        String out = captureWith(() -> {
            try { invoke(sub, sub.length); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue("expected size= header, got: " + out,
                out.contains("size=" + sub.length));
        assertTrue("expected type=0x0b, got: " + out,
                out.contains("type=0x0b"));
        // Outer 0x0b is not 0x03 → no inner-sub annotation.
        assertFalse("0x0b must not get sub=0x… annotation",
                out.contains("sub=0x"));
    }

    @Test
    public void enabledFlagDumpsClientMultipartFragmentInFull() throws Exception {
        writeFlag(true);
        // 0x03/0x07 — receipt of one of these C→S means we're in a
        // state retail never enters; full hex is required.
        byte[] sub = new byte[]{0x03, 0x10, 0x00, 0x07, 0x01, 0x02, 0x03};
        String out = captureWith(() -> {
            try { invoke(sub, sub.length); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue("expected CLIENT_MULTIPART_FRAG dump, got: " + out,
                out.contains("CLIENT_MULTIPART_FRAG"));
        // Hex of 0x03 0x10 0x00 0x07 0x01 0x02 0x03 = "03100007010203"
        assertTrue("expected full hex dump, got: " + out,
                out.contains("03100007010203"));
    }

    @Test
    public void enabledFlagDumpsClientGamepkt() throws Exception {
        writeFlag(true);
        byte[] sub = new byte[]{0x03, 0x42, 0x00, 0x1f, 0x05, 0x00, 0x3d, 0x11};
        String out = captureWith(() -> {
            try { invoke(sub, sub.length); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue("expected CLIENT_GAMEPKT line, got: " + out,
                out.contains("CLIENT_GAMEPKT"));
    }

    @Test
    public void enabledFlagDoesNotDumpFragmentForNon07GamepktIs03() throws Exception {
        writeFlag(true);
        // 0x03 outer but 0x1f inner — should produce CLIENT_GAMEPKT
        // line but NOT a CLIENT_MULTIPART_FRAG line.
        byte[] sub = new byte[]{0x03, 0x42, 0x00, 0x1f, 0x00};
        String out = captureWith(() -> {
            try { invoke(sub, sub.length); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertTrue(out.contains("CLIENT_GAMEPKT"));
        assertFalse("0x1f inner must not trigger MULTIPART_FRAG",
                out.contains("CLIENT_MULTIPART_FRAG"));
    }

    @Test
    public void shortSubPacketNeverThrows() throws Exception {
        // Empty + length-1 sub-packets exist on the wire and must
        // not blow up the parser when diagnostics are enabled.
        writeFlag(true);
        invoke(new byte[]{}, 0);
        invoke(new byte[]{0x0b}, 1);
        invoke(null, 0);
        // Test passes if no exception escaped.
    }

    @Test
    public void disabledFlagEarlyReturnDoesNotEvaluateBytes() throws Exception {
        writeFlag(false);
        // Pass null — would NPE on subPacket[0] if the flag check
        // weren't first. This pins the gate's order: flag THEN
        // bytes.
        invoke(null, 0);
        // Test passes if no NPE.
    }
}
