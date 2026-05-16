package server.gameserver.packets.client_udp;

import server.gameserver.Player;
import server.gameserver.internalEvents.DummyEvent;
import server.gameserver.packets.GamePacketDecoderUDP;
import server.gameserver.packets.server_tcp.Location;
import server.gameserver.packets.server_tcp.Packet830D;
import server.gameserver.packets.server_udp.UDPAlive;
import server.tools.Out;
import server.tools.Timer;

/**
 * Client "ready to load the new zone" packet (reliable
 * {@code 0x03/0x22} sub {@code 0x03}). This is phase 2 of the
 * retail zone-cross handshake — see {@link Zoning1} for the full
 * flow.
 *
 * <h3>Retail response (decoded 2026-05-14, RETAIL_PLAZA_CROSSZONE,
 * 6 crossings)</h3>
 *
 * <pre>
 *   C-&gt;S  Zoning2  0x03/0x22/0x03
 *   S-&gt;C  TCP 0x83/0x0d GameinfoReady   (~190ms after Zoning2)
 *   S-&gt;C  TCP 0x83/0x0c Location(path)
 *   C-&gt;S  0x03/0x08 ReliableAck
 *   S-&gt;C  UDP 0x04 UDPAlive             (~15ms after Location)
 *   server UDP wrapper RESETS: counter-&gt;1, NEW sessionkey
 * </pre>
 *
 * <p>The session reset is the crucial step that was missing
 * before: the client restarts its reliable counter from 1 with a
 * new session key the moment the cross completes, and the server
 * MUST mirror that or the reliable layer desyncs and the client
 * hangs on the "Synchronizing" overlay. The reset is done here,
 * right before the UDPAlive is built, so the UDPAlive's
 * {@code -sessionkey} field carries the new key for the client to
 * adopt.
 */
public class Zoning2 extends GamePacketDecoderUDP {

    public Zoning2(byte[] subPacket) {
        super(subPacket);
    }

    @Override
    public void execute(Player pl) {
        // TCP zone-swap pair: GameinfoReady then Location (carries
        // the destination BSP path resolved from MISC_LOCATION,
        // which Zoning1 already set).
        if (pl.getTcpConnection() != null) {
            pl.send(new Packet830D());
            pl.send(new Location(pl));
        } else {
            Out.writeln(Out.Warning,
                "Zoning2: no TCP connection for "
                + (pl.getCharacter() == null ? "?"
                        : pl.getCharacter().getName())
                + " — zone swap dropped");
            return;
        }
        // UDPAlive + UDP-session reset, ~20 ms later so the TCP
        // Location frame settles on the client first (matches the
        // ~15 ms retail gap).
        pl.addEvent(new Zoning2Answer());
    }

    /**
     * Delayed step: regenerate the UDP session (counter→0, fresh
     * session key, cleared retransmit ring) THEN emit the UDPAlive
     * carrying that new key. Order is mandatory — UDPAlive reads
     * the session key at construction time.
     */
    static class Zoning2Answer extends DummyEvent {
        public Zoning2Answer() {
            eventTime = Timer.getRealtime() + 20;
        }

        @Override
        public void execute(Player pl) {
            if (pl == null || pl.getUdpConnection() == null) {
                return;
            }
            short newKey = pl.getUdpConnection()
                    .resetSessionForZoneCross();
            Out.writeln(Out.Info,
                "Zoning2: UDP session reset for "
                + (pl.getCharacter() == null ? "?"
                        : pl.getCharacter().getName())
                + " — counter=0 newSessionKey=0x"
                + Integer.toHexString(newKey & 0xFFFF));
            pl.send(new UDPAlive(pl));
        }
    }
}
