package server.testtools;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import server.gameserver.GameServerUDPConnection;
import server.gameserver.Player;
import server.interfaces.ServerUDPPacket;

/**
 * Stub {@link GameServerUDPConnection} for tests: overrides
 * {@link #send(ServerUDPPacket)} to capture each outgoing
 * datagram's plaintext bytes (pre-encryption) instead of pushing
 * them to the wire.
 *
 * <p>The harness compares plaintext-against-plaintext, not
 * wire-against-wire, because the LFSR cipher uses a per-packet
 * random seed — every encrypt() emits different bytes for the
 * same input. Plaintext comparison is the right unit.
 *
 * <p>This class is the UDP analogue of
 * {@link server.gameserver.CapturingTCPConnection}.
 */
public final class CapturingUDPConnection extends GameServerUDPConnection {

    private final List<ServerUDPPacket> received = new ArrayList<>();
    private final List<byte[]> rawBytes = new ArrayList<>();

    public CapturingUDPConnection(InetAddress addr, int port, Player pl) {
        super(addr, port, pl);
    }

    @Override
    public void send(ServerUDPPacket packet) {
        received.add(packet);
        DatagramPacket[] dps;
        try {
            dps = packet.getDatagramPackets();
        } catch (RuntimeException e) {
            // Some packet builders construct lazily; failure here
            // means the production path would have NPE'd too —
            // record an empty entry so the index alignment is kept.
            rawBytes.add(new byte[0]);
            return;
        }
        if (dps == null) { rawBytes.add(new byte[0]); return; }
        for (DatagramPacket dp : dps) {
            if (dp == null) continue;
            byte[] copy = new byte[dp.getLength()];
            System.arraycopy(dp.getData(), dp.getOffset(),
                    copy, 0, dp.getLength());
            rawBytes.add(copy);
        }
    }

    /** Snapshot of the ServerUDPPacket objects received so far. */
    public List<ServerUDPPacket> received() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }

    /** Raw plaintext datagram bytes (one entry per DatagramPacket
     *  emitted, in order). */
    public List<byte[]> rawBytes() {
        return Collections.unmodifiableList(new ArrayList<>(rawBytes));
    }

    /** Forget all captured packets. */
    public void clear() {
        received.clear();
        rawBytes.clear();
    }

    /**
     * Replace the {@link Player}'s UDP connection with a capturing
     * stub. Returns the newly-installed capturer for inspection.
     *
     * <p>Use this in test setUp() right after
     * {@code PacketTestFixture.newPlayer()} so any subsequent
     * {@code pl.send(udpPkt)} calls are intercepted.
     */
    public static CapturingUDPConnection replaceOn(Player pl) {
        try {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            CapturingUDPConnection cap =
                    new CapturingUDPConnection(addr, 5000, pl);
            pl.setUdpConnection(cap);
            return cap;
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }
}
