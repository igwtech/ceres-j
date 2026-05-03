package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.CashUpdateProbe;
import server.gameserver.packets.server_udp.CharsysOnly;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.gameserver.packets.server_udp.PoolStatusBroadcast;
import server.gameserver.packets.server_udp.PoolUpdate;
import server.gameserver.packets.server_udp.SoullightUpdate;
import server.tools.Out;
import server.tools.Timer;

/**
 * One-shot reverse-engineering harness — automatically nudges every
 * tracked resource (HLT/STA/PSI/Soullight/Cash) at known timestamps so
 * we can correlate server send-order with HUD response in a screencast.
 *
 * <p>Triggered by appending {@code new ResourceProbeEvent()} to the
 * player's event queue right after world-entry completes. The sequence
 * (with delays) is:
 * <pre>
 *   t+0s   LocalChat banner: "ResourceProbe begin"
 *   t+2s   sethp 25  -> PoolUpdate(POOL_HP, -75, max)+ PoolStatusBroadcast
 *   t+5s   setpsi 25 -> PoolUpdate(POOL_PSI, -75, max)+ PoolStatusBroadcast
 *   t+8s   setsta 25 -> PoolUpdate(POOL_STA, -75, max)+ PoolStatusBroadcast
 *   t+11s  setsl 0   -> SoullightUpdate(0)
 *   t+14s  setsl 100 -> SoullightUpdate(100)
 *   t+17s  setcash 99999 (probe sub=0x04 on 0x02 wrapper)
 *   t+20s  setcash 0     (probe sub=0x04 on 0x03 wrapper)
 *   t+23s  heal -> PoolUpdate(POOL_HP/PSI/STA, +full)+ PoolStatusBroadcast
 *   t+26s  banner: "ResourceProbe end"
 * </pre>
 */
public class ResourceProbeEvent extends DummyEvent {

    public static final long INITIAL_DELAY_MS = 2000;

    private final int step;

    public ResourceProbeEvent() { this(0, INITIAL_DELAY_MS); }

    private ResourceProbeEvent(int step, long delayMs) {
        this.step = step;
        this.eventTime = Timer.getRealtime() + delayMs;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || !pl.isloggedin() || pl.getCharacter() == null) return;
        PlayerCharacter pc = pl.getCharacter();
        long delay = 3000;
        boolean done = false;

        try {
            switch (step) {
            case 0:
                banner(pl, "ResourceProbe begin (CharsysOnly multipart, disc=0x02)");
                Out.writeln(Out.Info, "ResourceProbe step 0: begin");
                break;
            case 1: {
                // Test A: disc=0x01 (CharInfo path) with 72-byte filler so
                // event 0x13ef fires with our [section 2][section 8] body.
                // The 0x13ef event is OUTSIDE the bit-0 guard so subsequent
                // sends should still trigger it.
                byte[] sec2 = CharsysOnly.poolsOnly(pl, 1234, 9999, 50, 200, 75, 250, 0);
                byte[] sec8 = CharsysOnly.cashOnly(pl, 1234567);
                byte[] payload = CharsysOnly.prependCharInfoFiller(
                        CharsysOnly.concat(sec2, sec8));
                pl.send(new CharsysOnly(pl, payload, CharsysOnly.DISC_CHARINFO));
                banner(pl, "disc=0x01+filler: HP=1234/9999 cash=1234567");
                Out.writeln(Out.Info, "ResourceProbe step 1: disc=0x01 + filler sent ("
                        + payload.length + " bytes)");
                break;
            }
            case 2: {
                // Test B: disc=0x02 (CharsysInfo path) — fires event 0xa8.
                byte[] sec2 = CharsysOnly.poolsOnly(pl, 5, 50, 5, 50, 5, 50, 50);
                byte[] sec8 = CharsysOnly.cashOnly(pl, 7777777);
                byte[] payload = CharsysOnly.concat(sec2, sec8);
                pl.send(new CharsysOnly(pl, payload, CharsysOnly.DISC_CHARSYS));
                banner(pl, "disc=0x02: HP=5/50 cash=7777777");
                Out.writeln(Out.Info, "ResourceProbe step 2: disc=0x02 sent ("
                        + payload.length + " bytes)");
                break;
            }
            case 3: {
                // Test C: disc=0x01 + filler with extreme values to confirm
                // visibility if HUD updates.
                byte[] sec2 = CharsysOnly.poolsOnly(pl, 999, 999, 999, 999, 999, 999, 100);
                byte[] sec8 = CharsysOnly.cashOnly(pl, 88888888);
                byte[] payload = CharsysOnly.prependCharInfoFiller(
                        CharsysOnly.concat(sec2, sec8));
                pl.send(new CharsysOnly(pl, payload, CharsysOnly.DISC_CHARINFO));
                banner(pl, "disc=0x01+filler: HP=999 SL=100 cash=88888888");
                Out.writeln(Out.Info, "ResourceProbe step 3: extreme values sent");
                break;
            }
            case 4:
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_HP, -50, 100));
                banner(pl, "(control) PoolUpdate delta=-50");
                Out.writeln(Out.Info, "ResourceProbe step 4: control PoolUpdate");
                break;
            case 5:
                pl.send(new SoullightUpdate(pl, 50.0f));
                banner(pl, "(control) SoullightUpdate=50.0");
                Out.writeln(Out.Info, "ResourceProbe step 5: control SoullightUpdate");
                break;
            case 6:
                banner(pl, "ResourceProbe end");
                Out.writeln(Out.Info, "ResourceProbe step 6: done");
                done = true;
                break;
            default:
                done = true;
            }
        } catch (Exception e) {
            Out.writeln(Out.Error, "ResourceProbe step " + step + " failed: " + e.getMessage());
        }

        if (!done) {
            pl.addEvent(new ResourceProbeEvent(step + 1, delay));
        }
    }

    private static void banner(Player pl, String msg) {
        try {
            pl.send(new LocalChatMessage(pl, "[Probe] " + msg, 2));
        } catch (Exception e) { /* ignore */ }
    }
}
