package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.CashUpdate;
import server.gameserver.packets.server_udp.ForcedZoning;
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
 * <p><b>Task #191:</b> the earlier revision sent the dead
 * {@code 0x03/0x07} disc-{@code 0x02}/{@code 0x01} CHARSYS multipart
 * ({@code CharsysOnly}). That path maps to client UI event {@code 0xa8}
 * — a no-op QUERY that NEVER parses the buffer, so the HUD never moved
 * and the probe produced false negatives. It now exercises only the
 * proven client-applying carriers, exactly the ones the user-visible GM
 * commands use:
 * <pre>
 *   t+0s   LocalChat banner: "ResourceProbe begin"
 *   t+2s   sethp 25  -> PoolUpdate(POOL_HP, delta, max) + PoolStatusBroadcast
 *   t+5s   setpsi 25 -> PoolUpdate(POOL_PSI, delta, max) + PoolStatusBroadcast
 *   t+8s   setsta 25 -> PoolUpdate(POOL_STA, delta, max) + PoolStatusBroadcast
 *   t+11s  setsl 0   -> SoullightUpdate(0)
 *   t+14s  setsl 100 -> SoullightUpdate(100)
 *   t+17s  setcash 99999 -> CashUpdate (retail-verified 0x1f/0x25 0x13)
 *   t+20s  setsub HLT 175 -> ForcedZoning (CharInfo redelivery)
 *   t+23s  heal -> PoolUpdate(HP/PSI/STA, +full) + PoolStatusBroadcast
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
                banner(pl, "ResourceProbe begin (verified applying carriers)");
                Out.writeln(Out.Info, "ResourceProbe step 0: begin");
                break;
            case 1: {
                // HP -> 25 via the retail-verified signed-delta PoolUpdate
                // (0x1f 01 00 50) + status snapshot — the same lever
                // .sethp uses, which the client actually applies.
                int target = 25;
                int delta = target - pc.getHealth();
                pc.setHealth(target);
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_HP, delta, pc.getMaxHealth()));
                pl.send(new PoolStatusBroadcast(pl));
                banner(pl, "HP -> 25 (PoolUpdate delta=" + delta + ")");
                Out.writeln(Out.Info, "ResourceProbe step 1: HP PoolUpdate");
                break;
            }
            case 2: {
                int target = 25;
                int delta = target - pc.getPsi();
                pc.setPsi(target);
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_PSI, delta, pc.getMaxPsi()));
                pl.send(new PoolStatusBroadcast(pl));
                banner(pl, "PSI -> 25 (PoolUpdate delta=" + delta + ")");
                Out.writeln(Out.Info, "ResourceProbe step 2: PSI PoolUpdate");
                break;
            }
            case 3: {
                int target = 25;
                int delta = target - pc.getStamina();
                pc.setStamina(target);
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_STA, delta, pc.getMaxStamina()));
                pl.send(new PoolStatusBroadcast(pl));
                banner(pl, "STA -> 25 (PoolUpdate delta=" + delta + ")");
                Out.writeln(Out.Info, "ResourceProbe step 3: STA PoolUpdate");
                break;
            }
            case 4:
                pl.send(new SoullightUpdate(pl, 0.0f));
                banner(pl, "Soullight -> 0.0 (0x02/0x1f 0x25 0x1f)");
                Out.writeln(Out.Info, "ResourceProbe step 4: SoullightUpdate 0");
                break;
            case 5:
                pl.send(new SoullightUpdate(pl, 100.0f));
                banner(pl, "Soullight -> 100.0");
                Out.writeln(Out.Info, "ResourceProbe step 5: SoullightUpdate 100");
                break;
            case 6: {
                int cash = 99999;
                pc.setCash(cash);
                pl.send(new CashUpdate(pl, cash));
                banner(pl, "Cash -> 99999 (verified CashUpdate carrier)");
                Out.writeln(Out.Info, "ResourceProbe step 6: CashUpdate");
                break;
            }
            case 7:
                pc.setSubskillLVL(PlayerCharacter.SUBSKILL_HLT, 175);
                pl.send(new ForcedZoning(pl, pl.getMapID()));
                banner(pl, "HLT subskill -> 175 (ForcedZoning CharInfo redelivery)");
                Out.writeln(Out.Info, "ResourceProbe step 7: ForcedZoning");
                break;
            case 8: {
                int hpDelta  = pc.getMaxHealth()  - pc.getHealth();
                int psiDelta = pc.getMaxPsi()     - pc.getPsi();
                int staDelta = pc.getMaxStamina() - pc.getStamina();
                pc.setHealth(pc.getMaxHealth());
                pc.setPsi(pc.getMaxPsi());
                pc.setStamina(pc.getMaxStamina());
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_HP,  hpDelta,  pc.getMaxHealth()));
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_PSI, psiDelta, pc.getMaxPsi()));
                pl.send(new PoolUpdate(pl, PoolUpdate.POOL_STA, staDelta, pc.getMaxStamina()));
                pl.send(new PoolStatusBroadcast(pl));
                banner(pl, "heal -> full (PoolUpdate ×3 + status)");
                Out.writeln(Out.Info, "ResourceProbe step 8: heal");
                break;
            }
            case 9:
                banner(pl, "ResourceProbe end");
                Out.writeln(Out.Info, "ResourceProbe step 9: done");
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
