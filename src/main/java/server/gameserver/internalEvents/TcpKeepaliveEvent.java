package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.gameserver.packets.server_tcp.TcpKeepalive;
import server.tools.Timer;

/**
 * Sends TCP keepalive (0x83 0x8f) every ~10 seconds, matching retail.
 *
 * <p>Started from {@link server.gameserver.internalEvents.WorldEntryEvent}
 * after the initial burst. Retail sends this every ~10.8 s on the TCP
 * connection for the duration of the session.
 */
public class TcpKeepaliveEvent extends DummyEvent {

    public static final long INTERVAL_MS = 10000;

    public TcpKeepaliveEvent() {
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin()) return;

        try {
            pl.send(new TcpKeepalive());
        } catch (Exception e) {
            // TCP connection may be closed; stop rescheduling
            return;
        }

        pl.addEvent(new TcpKeepaliveEvent());
    }
}
