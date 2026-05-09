package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * Transaction acknowledgement ({@code a0 02}) — 2-byte
 * server→client TCP packet that retail emits after significant
 * gameplay events: trade commits, inventory equip/use, heal /
 * combat actions, zone transitions, GenRep teleport, medbed,
 * chair sit.
 *
 * <h3>Wire format</h3>
 *
 * <p>Two-byte payload {@code a0 02}, framed by
 * {@link PacketBuilderTCP} as {@code fe 02 00 a0 02}.
 *
 * <h3>Semantics</h3>
 *
 * <p>Verified against catalog evidence (224 retail samples
 * across 10/17 captures, top markers POKE_START × 2,
 * HEAL_PVP × 2, TRADE_CASH_CONFIRM × 2). Distinct from
 * {@link SessionReady} (also {@code a0 01}) which is the
 * post-authentication handshake — that one fires only 34 times
 * per session, login-only. {@link InteractionAck} fires
 * 6.6× more often and is event-driven, not state-driven.
 *
 * <p>Retail emits this packet <strong>in pairs</strong> after
 * each significant event. The exact reason for the doubled
 * emission is unknown — both copies may target different
 * subsystems (one for transaction, one for session state).
 * Callers that want full retail parity should send two
 * back-to-back instances.
 *
 * <h3>Currently NOT wired</h3>
 *
 * <p>This class ships as a regression-net-pinned emitter.
 * Wiring it into specific gameplay handlers (Trade, Inventory,
 * Combat, Zoning2, UseItem) is per-handler follow-up work
 * — the priority is low because gameplay functions without
 * it; it primarily affects long-session state synchronisation.
 */
public final class InteractionAck extends PacketBuilderTCP {

    public InteractionAck() {
        super();
        write(0xa0);
        write(0x02);
    }
}
