package server.testtools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import server.gameserver.CapturingTCPConnection;
import server.gameserver.Player;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_udp.PacketTestFixture;
import server.interfaces.GameServerEvent;
import server.interfaces.ServerTCPPacket;

/**
 * Test fixture that simulates the server's C→S decoder and
 * captures the S→C TCP packets it emits. The skeleton for the
 * eventual full session-replay tooling described in
 * {@code IMPLEMENTATION_PLAN.md} Phase 10 (Hardening).
 *
 * <h3>Today's scope</h3>
 *
 * <p>Single-packet drive: hand it a sub-packet body (the bytes
 * that would arrive after the {@code 0x13} outer frame's
 * length prefix) and it decodes via the production
 * {@code GamePacketReaderUDP.decodesub13} static method and
 * fires the resulting event on a fresh {@link Player}.
 *
 * <p>Session-level replay (multi-step recordings, S→C diff,
 * pcap-driven inputs) builds on this primitive in follow-up
 * commits.
 *
 * <h3>Why a static {@code decodesub13} reflective dispatch?</h3>
 *
 * <p>{@code decodesub13} is package-private in
 * {@code server.gameserver.packets} — exposing it as
 * test-public would be a public-API change. Reflection here
 * keeps the production class clean while still letting the
 * harness exercise the real decoder path.
 */
public final class ReplayHarness {

    private final Player player;
    private final CapturingTCPConnection tcp;
    private final List<DriveResult> history = new ArrayList<>();

    public ReplayHarness() {
        this.player = PacketTestFixture.newPlayer();
        this.tcp = new CapturingTCPConnection();
        this.player.setTcpConnection(tcp);
    }

    /** The Player driving this session. Tests can mutate its
     *  state (mapId, character fields) before calling drive(). */
    public Player player() { return player; }

    /** Bytes captured so far on the TCP capture (cumulative across
     *  all drive() calls). */
    public List<ServerTCPPacket> tcpEmitted() {
        return tcp.received();
    }

    /**
     * Drive a single 0x13 sub-packet through the decoder + event
     * dispatch. Returns the decoded event so callers can inspect
     * it (or null if recognition fell through).
     */
    public DriveResult drive(byte[] subPacket) {
        GameServerEvent ev = decodeSub13(subPacket);
        int tcpBefore = tcp.received().size();
        if (ev != null) {
            ev.execute(player);
        }
        int tcpEmitted = tcp.received().size() - tcpBefore;
        DriveResult r = new DriveResult(subPacket, ev,
                tcp.received().subList(tcpBefore,
                        tcp.received().size()));
        history.add(r);
        return r;
    }

    /** Drive multiple sub-packets in order, returning the final
     *  step's result. Useful for sequence-level assertions. */
    public DriveResult driveAll(byte[]... subPackets) {
        DriveResult last = null;
        for (byte[] sp : subPackets) {
            last = drive(sp);
        }
        return last;
    }

    /** All drive() invocations in order. */
    public List<DriveResult> history() {
        return Collections.unmodifiableList(history);
    }

    private static GameServerEvent decodeSub13(byte[] subPacket) {
        try {
            Method m = Class.forName(
                    "server.gameserver.packets.GamePacketReaderUDP")
                    .getDeclaredMethod("decodesub13", byte[].class);
            m.setAccessible(true);
            return (GameServerEvent) m.invoke(null, (Object) subPacket);
        } catch (Exception e) {
            throw new RuntimeException(
                    "ReplayHarness: failed to dispatch decodesub13", e);
        }
    }

    /** Result of a single drive() call. */
    public static final class DriveResult {
        public final byte[] inputBytes;
        public final GameServerEvent decoded;
        public final List<ServerTCPPacket> tcpEmittedThisStep;

        DriveResult(byte[] in, GameServerEvent ev,
                     List<ServerTCPPacket> emitted) {
            this.inputBytes = in;
            this.decoded = ev;
            this.tcpEmittedThisStep = new ArrayList<>(emitted);
        }

        /** Was the input recognised by the decoder (event != null)? */
        public boolean wasRecognised() { return decoded != null; }

        /** Was the decoded event a fall-through-Unknown? */
        public boolean wasUnknown() {
            return decoded != null
                && (decoded instanceof GamePacketDecoderUDP);
            // GamePacketDecoderUDP is the parent of UnknownClientUDPPacket
            // — used as a fall-through marker by the decoder.
        }

        /** True iff this step emitted exactly the given TCP
         *  packet types in order. */
        public boolean emittedTcpSequence(Class<?>... expected) {
            if (tcpEmittedThisStep.size() != expected.length) {
                return false;
            }
            for (int i = 0; i < expected.length; i++) {
                if (!expected[i].isInstance(tcpEmittedThisStep.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }
}
