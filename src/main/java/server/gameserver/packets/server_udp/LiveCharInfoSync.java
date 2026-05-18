package server.gameserver.packets.server_udp;

import server.gameserver.Player;

/**
 * Runtime live-CHARSYS state-sync helper.
 *
 * <p>This is the <b>only</b> server-side lever that makes a
 * mid-session character-state change (HP/PSI/STA pools, subskill /
 * pool maxima, cash, skills) actually update the client HUD
 * <em>without a zone reload</em>. It is a thin, intent-revealing
 * wrapper that re-emits a full {@link CharInfo} buffer.
 *
 * <h2>Why this works — the Ghidra-pinned wire path (task #194)</h2>
 *
 * <p>{@link CharInfo} extends
 * {@link server.networktools.PacketBuilderUDP130307}, which — for the
 * typical &le;~900&nbsp;B CharInfo body — emits the buffer as the
 * reliable {@code 0x03/0x2c} <b>variant&nbsp;0x02</b> single packet
 * ({@code 02 01 <sections>}). That packet is the client's
 * <b>dedicated single-packet CHARSYS handler</b>, traced byte-for-byte
 * in {@code docs/protocol/RE_state_sync.md} and
 * {@code docs/re_state_sync_dump{7..12}.txt}:
 *
 * <pre>
 *   wire 0x03/0x2c v0x02  (CharInfo single packet)
 *     -> LC message factory  FUN_00840ee0
 *          case 0x11  (wire sub-type byte == 0x12)
 *            -> LC_RESTORECHAR ctor  FUN_0083fde0   (vftable @ 0x00a5d874)
 *            -> slot 2 deserialize   FUN_00842680   (copies [len LE2][payload]
 *                                                     into obj+0x18 / len obj+0x14)
 *     -> LC stream loop      FUN_008420f0   (driven by FULLCHARSYSTEM
 *                                            dispatcher FUN_00803cd0)
 *     -> LC_RESTORECHAR slot 3 (apply)  FUN_00841dc0
 *          -> FUN_008033d0  ("CHARSYS : Buffer loaded, buffer size: %i")
 *               -> FUN_007ef260()
 *               -> FUN_008447d0(buf,len)   CHARSYS TLV section loop
 *                     section 2 -> FUN_00845820  POOLS  -> HUD ceilings
 *                     section 4 -> FUN_00846960  SUBSKILLS
 *                     section 8 -> FUN_00846470  CASH + GRs
 *               -> FUN_0080b8b0()           FullCharsysInfo recompute
 *                                           (re-derives & repaints the HUD)
 * </pre>
 *
 * <p>This is the same {@code FUN_008447d0} + {@code FUN_0080b8b0}
 * pipeline that FULLCHARSYSTEM UI event {@code 0x6e} runs (the
 * "unconditional live parse" of {@code RE_state_sync.md §2.2}); the
 * single-packet network handler reaches it directly, with no UI-event
 * gating and <b>no zone reload</b>.
 *
 * <p><b>Empirical ground truth (user, in-game):</b> the per-entity
 * carriers ({@link PoolUpdate} {@code 0x1f/01/00/50}, standalone
 * {@link CashUpdate} {@code 0x1f/25/13/04}, {@link SoullightUpdate})
 * do <em>not</em> move the local player's HUD; cash only changes after
 * a CharInfo redelivery (observed on zone cross, which re-sends
 * exactly this {@code 0x03/0x2c} CharInfo). The {@code 0x03/0x07}
 * disc-{@code 0x02} multipart path ({@link CharsysOnly}) only fires
 * the no-op query UI event {@code 0xa8} and never parses — see
 * {@code RE_state_sync.md §4.1}.
 *
 * <p>Unlike {@link ForcedZoning}, this does <b>not</b> trigger a
 * splash screen / BSP reload — it is a pure state refresh. The retail
 * client itself re-emits {@code 0x03/0x2c} v0x02 mid-session (37
 * observations across all 17 retail captures, including the
 * {@code IN_WORLD} marker — see {@code docs/protocol/packets/
 * udp_s2c_03_2c.md}); Ceres already does too on
 * {@code RequestPositionUpdate}.
 *
 * <p>Usage: mutate {@link server.database.playerCharacters.PlayerCharacter}
 * first, then {@code pl.send(new LiveCharInfoSync(pl))}. Because
 * {@link CharInfo} reads live {@code PlayerCharacter} state in its
 * constructor, the re-parsed buffer carries the just-mutated values.
 */
public final class LiveCharInfoSync {

    private LiveCharInfoSync() {
    }

    /**
     * Build the live-CHARSYS resync packet for {@code pl}'s current
     * (already-mutated) character state. Returns a {@link CharInfo}
     * so it routes through the Ghidra-pinned {@code 0x03/0x2c}
     * single-packet CHARSYS handler that re-parses + recomputes the
     * HUD with no zone reload.
     */
    public static CharInfo of(Player pl) {
        return new CharInfo(pl);
    }
}
