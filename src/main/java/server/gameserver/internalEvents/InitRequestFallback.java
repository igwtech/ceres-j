package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.tools.Out;
import server.tools.Timer;

/**
 * Starts the login world entry if the client never sends its raw
 * {@code 0x2a} init request.
 *
 * <p>Retail pulls the world: after the UDP handshake the server sends
 * nothing on {@code 0x13} until the client has answered the UDPAlive and
 * sent {@code 0x2a [account LE32][token]} + {@code 0x0c}; the first
 * reliable follows ~0.2 s later (retail_b862_1: {@code 0x2a} 59 ms after
 * the {@code 0x04}, world at +0.26 s). The client only sends {@code 0x2a}
 * while it has not received its world yet, so it never did with Ceres
 * pushing the world straight after the handshake. See
 * {@link server.gameserver.packets.client_udp.RequestPositionUpdate}.
 *
 * <p>This timer only covers a client that doesn't send {@code 0x2a}, so
 * the login can't hang.
 */
public class InitRequestFallback extends DummyEvent {

    /** Retail clients send {@code 0x2a} within ~60 ms of the UDPAlive. */
    public static final long TIMEOUT_MS = 3000;

    public InitRequestFallback() {
        eventTime = Timer.getRealtime() + TIMEOUT_MS;
    }

    @Override
    public void execute(Player pl) {
        if (!pl.consumeAwaitingInitRequest()) return;
        Out.writeln(Out.Warning, "InitRequestFallback: no 0x2a init request from "
                + pl.getAccount().getUsername() + " after " + TIMEOUT_MS
                + " ms, starting world entry anyway");
        pl.addEvent(new WorldEntryEvent());
    }
}
