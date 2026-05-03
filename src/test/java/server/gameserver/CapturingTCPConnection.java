package server.gameserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import server.interfaces.ServerTCPPacket;

/**
 * Stub {@link GameServerTCPConnection} for tests: overrides
 * {@link #send(ServerTCPPacket)} to record packets in-memory instead
 * of writing them to a socket. Construct with a {@code null} socket.
 *
 * <p>Tests use this to verify that the chat fan-out (and other future
 * cross-player handlers) emits the expected packets to the right
 * recipient(s).
 */
public final class CapturingTCPConnection extends GameServerTCPConnection {

    private final List<ServerTCPPacket> received = new ArrayList<>();

    public CapturingTCPConnection() {
        super(null);
    }

    @Override
    public void send(ServerTCPPacket packet) {
        received.add(packet);
    }

    /** Snapshot of the packets received by this connection so far. */
    public List<ServerTCPPacket> received() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }

    /** Number of packets received. */
    public int count() { return received.size(); }

    /** Forget all captured packets. */
    public void clear() { received.clear(); }
}
