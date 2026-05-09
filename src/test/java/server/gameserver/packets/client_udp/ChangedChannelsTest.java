package server.gameserver.packets.client_udp;

import static org.junit.Assert.*;

import org.junit.Test;

import server.gameserver.ChatManager;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.PacketTestFixture;

/**
 * Functional test for {@link ChangedChannels} — the C→S
 * {@code 0x03/0x1f sub=0x4c} packet sent when a player toggles
 * their listening chat channels in the client UI.
 *
 * <p>The handler reads the channel bitmask after a 7-byte
 * skip and routes through {@link ChatManager#changedListening}
 * which adds the player to the matching channel listener list.
 */
public class ChangedChannelsTest {

    /** Build a 11-byte ChangedChannels body: skip 7 bytes
     *  then read channels LE32. */
    private static byte[] buildBody(int channels) {
        byte[] b = new byte[11];
        // The handler skips bytes 0..6 unconditionally, so any
        // values are fine. Use illustrative wire bytes.
        b[0]  = 0x03;
        b[1]  = 0x42;
        b[2]  = 0x00;
        b[3]  = 0x1f;
        b[4]  = 0x05;
        b[5]  = 0x00;
        b[6]  = 0x4c;       // sub-tag ChangedChannels
        b[7]  = (byte) (channels        & 0xff);
        b[8]  = (byte) ((channels >> 8 ) & 0xff);
        b[9]  = (byte) ((channels >> 16) & 0xff);
        b[10] = (byte) ((channels >> 24) & 0xff);
        return b;
    }

    @Test
    public void executeUpdatesPlayerChannels() {
        // From channels=0 → ZONE (1). The handler routes through
        // ChatManager which calls pl.setChannels(channels).
        Player pl = PacketTestFixture.newPlayer();
        // Fixture player starts at channels=0 by default.
        assertEquals(0, pl.getChannels());

        new ChangedChannels(buildBody(ChatManager.ZONE)).execute(pl);

        assertEquals("channels must be set to ZONE",
                ChatManager.ZONE, pl.getChannels());
    }

    @Test
    public void executeAddsBitmaskCombinations() {
        // ZONE | FRACTION | ALLY = 1 | 2 | 4 = 7
        Player pl = PacketTestFixture.newPlayer();
        int mask = ChatManager.ZONE
                | ChatManager.FRACTION
                | ChatManager.ALLY;

        new ChangedChannels(buildBody(mask)).execute(pl);

        assertEquals(mask, pl.getChannels());
    }

    @Test
    public void multipleInvocationsExerciseToggleLogic() {
        // From 0 → ZONE → ZONE|FRACTION → ZONE.
        // The first call hits the "channels == 0" first-sync
        // path; subsequent calls hit the toggle/diff path.
        Player pl = PacketTestFixture.newPlayer();

        new ChangedChannels(buildBody(ChatManager.ZONE)).execute(pl);
        assertEquals(ChatManager.ZONE, pl.getChannels());

        // Add FRACTION
        new ChangedChannels(buildBody(ChatManager.ZONE
                | ChatManager.FRACTION)).execute(pl);
        // Drop FRACTION
        new ChangedChannels(buildBody(ChatManager.ZONE)).execute(pl);
        // Pass = no exception escaped through the toggle path.
    }

    @Test
    public void zeroChannelsLeavesPlayerUnchangedOnFirstSync() {
        // The first-sync branch only applies when getChannels() == 0;
        // if the input is also 0, nothing in any listener list
        // changes and the field remains 0.
        Player pl = PacketTestFixture.newPlayer();
        new ChangedChannels(buildBody(0)).execute(pl);
        assertEquals(0, pl.getChannels());
    }

    @Test
    public void bytesAtSkipOffsetAreIgnored() {
        // The handler skips 7 bytes regardless of content. Verify
        // that varying the skipped bytes doesn't change the result.
        Player pl = PacketTestFixture.newPlayer();
        byte[] withGarbage = buildBody(ChatManager.ZONE);
        for (int i = 0; i < 7; i++) {
            withGarbage[i] = (byte) 0xFF;
        }
        new ChangedChannels(withGarbage).execute(pl);
        assertEquals("garbage in skipped prefix must not affect "
                + "result", ChatManager.ZONE, pl.getChannels());
    }
}
