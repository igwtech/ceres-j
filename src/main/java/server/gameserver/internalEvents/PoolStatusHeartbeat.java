package server.gameserver.internalEvents;

import server.gameserver.Player;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.tools.Out;
import server.tools.Timer;

/**
 * Periodic raw {@code 0x1f} pool status broadcast.
 *
 * <p>Retail sends 54 per 60-second session (~0.9/s = one every ~1.1 s).
 * The server tells the client its current HP/PSI/STA values are valid.
 * Without this, the client may consider its character state stale.
 */
public class PoolStatusHeartbeat extends DummyEvent {

    public static final long INTERVAL_MS = 1100;

    public PoolStatusHeartbeat() {
        this.eventTime = Timer.getRealtime() + INTERVAL_MS;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin() || pl.getUdpConnection() == null
                || pl.getCharacter() == null) {
            return;
        }

        try {
            pl.send(new PoolStatusBroadcast(pl));
        } catch (Exception e) {
            Out.writeln(Out.Error,
                "PoolStatusHeartbeat: send failed: " + e.getMessage());
        }

        pl.addEvent(new PoolStatusHeartbeat());
    }
}
