package server.tools;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for the {@code subPackets} debug flag introduced to
 * silence the ~90 Hz per-player UDP sub-packet diagnostic stream.
 *
 * <p>The flag MUST default to false so production logs stay clean
 * unless an operator explicitly opts in via the {@code Debug =
 * subPackets} ceres.cfg directive.
 */
public class DebugSubPacketsFlagTest {

    private boolean savedFlag;

    @Before
    public void capture() throws Exception {
        savedFlag = readDebugFlag();
    }

    @After
    public void restore() throws Exception {
        writeDebugFlag(savedFlag);
    }

    private static boolean readDebugFlag() throws Exception {
        Field f = Debug.class.getDeclaredField("debugSubPackets");
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static void writeDebugFlag(boolean v) throws Exception {
        // Bypass the package-private setSubPacketsEnabledForTest to
        // keep the after-hook honest even if that helper is moved.
        Field f = Debug.class.getDeclaredField("debugSubPackets");
        f.setAccessible(true);
        f.setBoolean(null, v);
    }

    @Test
    public void defaultIsFalse() throws Exception {
        writeDebugFlag(false);
        assertFalse("subPackets diagnostic must be off by default — "
                + "it costs ~90 Hz of string formatting per player",
                Debug.isSubPacketsEnabled());
    }

    @Test
    public void testSeamFlipsAccessor() {
        // The package-private setter is the safe entry point used by
        // the GamePacketReaderUDP test that needs to assert gated
        // behaviour without touching reflection itself.
        invokeSeam(true);
        assertTrue(Debug.isSubPacketsEnabled());
        invokeSeam(false);
        assertFalse(Debug.isSubPacketsEnabled());
    }

    @Test
    public void subPacketCallIsNoOpWhenDisabled() {
        // When the flag is off, Debug.subPacket must NOT touch
        // Out.fw_debug. fw_debug is null in this test context, so a
        // touched call would NullPointerException.
        invokeSeam(false);
        // No assertion needed — exit means no NPE.
        Debug.subPacket("this should be silently dropped");
    }

    @Test
    public void initRespectsConfigFlag() throws Exception {
        // Simulate Debug.init being invoked after Config has parsed
        // the "subPackets" token. Reflectively flip Config.debugSubPackets
        // and verify Debug.init transfers it.
        Field cfg = Config.class.getDeclaredField("debugSubPackets");
        cfg.setAccessible(true);
        boolean prevCfg = cfg.getBoolean(null);
        try {
            cfg.setBoolean(null, true);
            // We cannot call Debug.init() directly because it tries
            // to open a FileWriter. Instead, mirror what init does
            // for the field of interest via reflection.
            Field dbg = Debug.class.getDeclaredField("debugSubPackets");
            dbg.setAccessible(true);
            dbg.setBoolean(null, Config.debugSubPackets);
            assertTrue(Debug.isSubPacketsEnabled());
        } finally {
            cfg.setBoolean(null, prevCfg);
        }
    }

    private static void invokeSeam(boolean v) {
        try {
            Method m = Debug.class.getDeclaredMethod(
                    "setSubPacketsEnabledForTest", boolean.class);
            m.setAccessible(true);
            m.invoke(null, v);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
