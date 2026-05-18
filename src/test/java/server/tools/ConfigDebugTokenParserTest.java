package server.tools;

import static org.junit.Assert.*;

import java.lang.reflect.Field;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link Config#applyDebugTokens(String)} — the
 * parser that converts the {@code Debug = ...} ceres.cfg directive
 * into per-flag booleans. Critically, this verifies that the new
 * {@code subPackets} token flips its dedicated flag (otherwise the
 * production GamePacketReaderUDP gate stays silent forever) and
 * that legacy tokens still work alongside it.
 */
public class ConfigDebugTokenParserTest {

    private boolean savedUnk, savedSnd, savedEv, savedRcv, savedSub, savedWire;

    @Before
    public void capture() throws Exception {
        savedUnk = read("debugUnknownPackets");
        savedSnd = read("debugSendingPackets");
        savedEv  = read("debugEvents");
        savedRcv = read("debugReceivedPackets");
        savedSub = read("debugSubPackets");
        savedWire = read("debugWire");
        // Force a clean slate so we measure the *parser's* effect.
        write("debugUnknownPackets", false);
        write("debugSendingPackets", false);
        write("debugEvents",         false);
        write("debugReceivedPackets",false);
        write("debugSubPackets",     false);
        write("debugWire",           false);
    }

    @After
    public void restore() throws Exception {
        write("debugUnknownPackets",  savedUnk);
        write("debugSendingPackets",  savedSnd);
        write("debugEvents",          savedEv);
        write("debugReceivedPackets", savedRcv);
        write("debugSubPackets",      savedSub);
        write("debugWire",            savedWire);
    }

    private static boolean read(String name) throws Exception {
        Field f = Config.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getBoolean(null);
    }

    private static void write(String name, boolean v) throws Exception {
        Field f = Config.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setBoolean(null, v);
    }

    @Test
    public void subPacketsTokenFlipsItsFlag() throws Exception {
        Config.applyDebugTokens("subPackets");
        assertTrue("subPackets must enable debugSubPackets",
                read("debugSubPackets"));
        // Other flags must NOT bleed.
        assertFalse(read("debugUnknownPackets"));
        assertFalse(read("debugSendingPackets"));
        assertFalse(read("debugEvents"));
        assertFalse(read("debugReceivedPackets"));
    }

    @Test
    public void wireTokenFlipsItsFlagAndIsOffByDefault() throws Exception {
        // Default (no token) leaves it off.
        Config.applyDebugTokens("unknownPackets");
        assertFalse("wire must stay off without its token",
                read("debugWire"));
        // Its own token enables it without bleeding into others.
        Config.applyDebugTokens("wire");
        assertTrue("wire token must enable debugWire",
                read("debugWire"));
        assertFalse(read("debugSendingPackets"));
        assertFalse(read("debugSubPackets"));
    }

    @Test
    public void caseInsensitive() throws Exception {
        Config.applyDebugTokens("SuBpAcKeTs");
        assertTrue(read("debugSubPackets"));
    }

    @Test
    public void leadingTrailingWhitespaceIsTrimmed() throws Exception {
        Config.applyDebugTokens("  subPackets  ");
        assertTrue(read("debugSubPackets"));
    }

    @Test
    public void multipleTokensAllFlip() throws Exception {
        Config.applyDebugTokens(
                "unknownPackets,events,subPackets,receivedPackets");
        assertTrue(read("debugUnknownPackets"));
        assertTrue(read("debugEvents"));
        assertTrue(read("debugSubPackets"));
        assertTrue(read("debugReceivedPackets"));
        assertFalse(read("debugSendingPackets"));
    }

    @Test
    public void unknownTokensAreIgnored() throws Exception {
        Config.applyDebugTokens("notARealFlag,subPackets,definitelyNot");
        assertTrue(read("debugSubPackets"));
        // Confirm none of the other flags got accidentally enabled.
        assertFalse(read("debugUnknownPackets"));
        assertFalse(read("debugSendingPackets"));
        assertFalse(read("debugEvents"));
        assertFalse(read("debugReceivedPackets"));
    }

    @Test
    public void emptyStringIsNoOp() throws Exception {
        Config.applyDebugTokens("");
        assertFalse(read("debugSubPackets"));
        assertFalse(read("debugUnknownPackets"));
    }

    @Test
    public void nullIsTolerated() throws Exception {
        // Real-world: ceres.cfg without a `Debug =` line returns
        // null from getProperty. Must not NPE.
        Config.applyDebugTokens(null);
        assertFalse(read("debugSubPackets"));
    }
}
