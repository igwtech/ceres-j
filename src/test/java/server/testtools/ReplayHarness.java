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
import server.interfaces.ServerUDPPacket;

/**
 * Test fixture that simulates the server's C→S decoder and
 * captures the S→C packets it emits. The skeleton plus the
 * session-level pcap-replay tooling described in
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
 * <p>Pcap-driven session replay (multi-step recordings, S→C
 * diff against retail) is wired through the
 * {@link server.testtools.PcapReplay} loader.
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
    private final CapturingUDPConnection udp;
    private final List<DriveResult> history = new ArrayList<>();

    public ReplayHarness() {
        // newPlayerWithZone() so Movement / sendPlayersinZone /
        // sendNPCsinZone don't NPE during replay. Tests that need
        // a zone-null fixture construct their Player directly via
        // PacketTestFixture.newPlayer() — not through this harness.
        this.player = PacketTestFixture.newPlayerWithZone();
        this.tcp = new CapturingTCPConnection();
        this.player.setTcpConnection(tcp);
        // Replace the random-keyed UDP connection with a capturing
        // one so S→C UDP datagrams are observable byte-for-byte.
        this.udp = CapturingUDPConnection.replaceOn(player);
    }

    /** The Player driving this session. Tests can mutate its
     *  state (mapId, character fields) before calling drive(). */
    public Player player() { return player; }

    /** Bytes captured so far on the TCP capture (cumulative across
     *  all drive() calls). */
    public List<ServerTCPPacket> tcpEmitted() {
        return tcp.received();
    }

    /** UDP packets captured so far (cumulative). Each entry is a
     *  raw plaintext datagram that the server tried to send. */
    public List<ServerUDPPacket> udpEmitted() {
        return udp.received();
    }

    /** All raw S→C bytes the harness has seen, in order, across
     *  TCP and UDP. Useful for byte-level diffing against retail. */
    public List<byte[]> emittedRawBytes() {
        return udp.rawBytes();
    }

    /**
     * Drive a single 0x13 sub-packet through the decoder + event
     * dispatch. Returns the decoded event so callers can inspect
     * it (or null if recognition fell through).
     */
    public DriveResult drive(byte[] subPacket) {
        GameServerEvent ev = decodeSub13(subPacket);
        int tcpBefore = tcp.received().size();
        int udpBefore = udp.received().size();
        if (ev != null) {
            ev.execute(player);
        }
        drainImminentEvents();
        DriveResult r = new DriveResult(subPacket, ev,
                tcp.received().subList(tcpBefore,
                        tcp.received().size()),
                udp.received().subList(udpBefore,
                        udp.received().size()),
                udp.rawBytes().subList(udpBefore,
                        udp.rawBytes().size()));
        history.add(r);
        return r;
    }

    /**
     * Drive a single RAW UDP datagram through the decoder. Use
     * this for non-0x13 outer-frame packets — handshake (0x01),
     * abort (0x08), reliable-channel raw (0x03 outer), and the
     * orphan opcodes the agent decoded (0x00/06/07/0d/0f/11/31).
     *
     * <p>{@link #drive(byte[])} only handles inner sub-packets
     * within an outer 0x13 frame; without {@code driveRaw} the
     * harness skips raw C→S and never emits the corresponding
     * S→C, breaking the index-paired comparison against retail's
     * S→C queue.
     */
    public DriveResult driveRaw(byte[] datagramBytes) {
        GameServerEvent ev = decodeRaw(datagramBytes);
        int tcpBefore = tcp.received().size();
        int udpBefore = udp.received().size();
        if (ev != null) {
            ev.execute(player);
        }
        drainImminentEvents();
        DriveResult r = new DriveResult(datagramBytes, ev,
                tcp.received().subList(tcpBefore,
                        tcp.received().size()),
                udp.received().subList(udpBefore,
                        udp.received().size()),
                udp.rawBytes().subList(udpBefore,
                        udp.rawBytes().size()));
        history.add(r);
        return r;
    }

    /**
     * Drain Player.eventList of events that would fire within
     * the next 250 ms. Mimics the production
     * {@code Player.run()} loop without spinning.
     *
     * <p>Why 250 ms: handshake replies are scheduled
     * {@code now + 100 ms} via {@link
     * server.gameserver.packets.client_udp.HandshakeUDP}; reliable
     * acks similarly. Heartbeats reschedule themselves
     * {@code now + 500..1100 ms} into the future so they will
     * <em>not</em> drain — preventing a runaway loop where a
     * heartbeat fires, reschedules itself, fires again, etc.
     */
    private void drainImminentEvents() {
        // Reflective access to private eventList — no public
        // drain hook on Player today.
        try {
            java.lang.reflect.Field f =
                    server.gameserver.Player.class
                            .getDeclaredField("eventList");
            f.setAccessible(true);
            server.tools.PriorityList queue =
                    (server.tools.PriorityList) f.get(player);
            long deadline = server.tools.Timer.getRealtime() + 250;
            // Hard cap on iterations so a misbehaving event that
            // re-queues itself with eventTime <= deadline can't
            // hang the harness.
            for (int i = 0; i < 64; i++) {
                if (queue.isEmpty()) break;
                server.interfaces.GameServerEvent first =
                        (server.interfaces.GameServerEvent)
                                queue.getFirst();
                if (first == null) break;
                if (first.getEventTime() > deadline) break;
                queue.removeFirst();
                first.execute(player);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(
                    "ReplayHarness: failed to drain eventList", e);
        }
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

    /** Reflective wrapper around {@code decode(DatagramPacket,
     *  Player)} for raw outer-frame UDP packets. */
    private GameServerEvent decodeRaw(byte[] datagramBytes) {
        try {
            java.net.DatagramPacket dp = new java.net.DatagramPacket(
                    datagramBytes, datagramBytes.length);
            // Wire the source so HandshakeUDP can read it during
            // setInterfaceId / port-binding.
            dp.setAddress(java.net.InetAddress.getByName("127.0.0.1"));
            dp.setPort(51769);
            Method m = Class.forName(
                    "server.gameserver.packets.GamePacketReaderUDP")
                    .getDeclaredMethod("decode",
                            java.net.DatagramPacket.class,
                            server.gameserver.Player.class);
            m.setAccessible(true);
            return (GameServerEvent) m.invoke(null, dp, player);
        } catch (Exception e) {
            throw new RuntimeException(
                    "ReplayHarness: failed to dispatch decode(raw)", e);
        }
    }

    /** Result of a single drive() call. */
    public static final class DriveResult {
        public final byte[] inputBytes;
        public final GameServerEvent decoded;
        public final List<ServerTCPPacket> tcpEmittedThisStep;
        public final List<ServerUDPPacket> udpEmittedThisStep;
        public final List<byte[]> udpRawBytesThisStep;

        DriveResult(byte[] in, GameServerEvent ev,
                     List<ServerTCPPacket> tcpEmitted,
                     List<ServerUDPPacket> udpEmitted,
                     List<byte[]> udpRaw) {
            this.inputBytes = in;
            this.decoded = ev;
            this.tcpEmittedThisStep = new ArrayList<>(tcpEmitted);
            this.udpEmittedThisStep = new ArrayList<>(udpEmitted);
            this.udpRawBytesThisStep = new ArrayList<>(udpRaw);
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
