package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.CharInfo;
import server.tools.Out;
import server.tools.Timer;

/**
 * Handles the client's {@code 0x13 -> 0x03 -> 0x24} "ready for world
 * state" trigger by RE-SENDING the CharInfo packet only.
 *
 * <p><b>Why this exists (the "skills render as garbage" delivery fix,
 * 2026-06-01).</b> {@code WorldEntryEvent} sends CharInfo at the very
 * start of the login burst (retail-faithful ordering). But on Ceres's
 * Docker-bridge &harr; Wine-client transport the client's UDP receive
 * path is not live for the first ~1.27&nbsp;s of the session — a live
 * Frida trace ({@code tools/frida_re/runs/pos_ceres_20260601_012822},
 * {@code _013146}) shows the client's FIRST received reliable packet is
 * seq=6: every reliable seq 1..5 (exactly the CharInfo multipart — 5
 * fragments for Krafteo's 992-byte CharInfo) is dropped in that dead
 * zone, NAK'd a few times, the server re-sends the same fragments, they
 * are dropped again, the client gives up. With no CharInfo the F1 skill
 * screen renders garbage (a bogus "Ceres Wisdom" slot + a negative rank)
 * and the toolbelt greys out (the client's skill-requirement check runs
 * on uninitialised CHARSYS data). Every datagram the client DID receive
 * in those traces was a normal small reliable packet at seq&nbsp;&ge;&nbsp;6.
 *
 * <p>The client's {@code 0x24} "ready for world state" reliable is sent
 * only AFTER its recv path is live (it is itself a response to the
 * initial burst). Re-sending CharInfo here therefore lands it in the
 * live window — the same seq range as all the packets the client
 * demonstrably receives — and the multipart reassembler
 * ({@code FUN_0055c270}) completes it. The CharInfo BYTE ENCODING itself
 * is already retail-correct (Sections&nbsp;3/4 verified against
 * {@code /tmp/retail_equip.pcap}); this is purely the delivery half.
 *
 * <p>Unlike the old {@code ReadyForWorldState} full zone-population
 * re-burst (disabled because re-sending InfoResponse/ChatList/TimeSync
 * pushed the client's state machine back to "joining session" and
 * triggered a 15&nbsp;s timeout), this handler re-sends ONLY CharInfo —
 * a pure CHARSYS data packet that does not touch the client's
 * world-entry state machine — so it is safe.
 */
public class ResendCharInfoOnReady extends GamePacketDecoderUDP {

    public ResendCharInfoOnReady(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getCharacter() == null) {
            return;
        }
        Out.writeln(Out.Info,
            "ResendCharInfoOnReady: client ready — re-sending CharInfo for "
            + pl.getCharacter().getName());
        // Defer a tick so the incoming 0x24's ACK leaves the socket first.
        pl.addEvent(new ResendEvent());
    }

    static class ResendEvent extends DummyEvent {
        ResendEvent() {
            // Defer past the zone-population flood. The 0x24 trigger arrives
            // while WorldEntryEvent is still streaming ~50 zone-object
            // packets (0x28/0x2d) back-to-back; a CharInfo re-sent into that
            // flood loses fragments to the client's receive-buffer pressure
            // (live trace: the heavy-traffic seq band drops ~half its
            // packets). 700 ms lands the re-send in the quiet window after
            // the flood drains, where reliable packets commit cleanly.
            eventTime = Timer.getRealtime() + 700;
        }

        @Override
        public void execute(Player pl) {
            if (pl == null || pl.getCharacter() == null) {
                return;
            }
            try {
                pl.send(new CharInfo(pl));
            } catch (Exception e) {
                Out.writeln(Out.Error,
                    "ResendCharInfoOnReady: CharInfo re-send failed: "
                    + e.getMessage());
            }
        }
    }
}
