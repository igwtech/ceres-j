package server.gameserver.state;

/**
 * Observable phases of a Neocron 2 client session, derived from
 * retail pcap analysis (see {@code zone_cross_2phase_handshake},
 * {@code server-lacks-client-state-machine} memories).
 *
 * <p>This enum is the type that {@link ClientStateMachine} advances
 * through. Phase 1 (the initial landing — task #239) is purely
 * observational: the FSM records transitions but does not gate any
 * server-side behaviour. Once we have pcap-replay validation that
 * the transitions match retail's, Phase 2 will start gating the
 * portal-cross / zone-cross commit chain on the ACKs the FSM sees.
 *
 * <p>Why pre-define states the server doesn't yet emit transitions
 * for: each state is anchored on a specific observable C→S or S→C
 * event documented in protocol memory. Listing them all here is the
 * contract test suites and instrumentation code consume. Don't add
 * states without a corresponding pcap-attested transition trigger.
 */
public enum ClientState {

    /** Session opened (TCP accept) but no login-burst has begun. */
    PRE_LOGIN,

    /** Server has emitted the world-entry burst (CharInfo, StartPos,
     *  TimeSync, … via {@code WorldEntryEvent}) and is waiting for
     *  the client to settle into normal gameplay. */
    WORLDENTRY_BURST,

    /** Client is in-world receiving movement / heartbeat traffic. */
    IN_WORLD,

    /** Portal-cross has begun: server has emitted TCP {@code 0x83/0x0d}
     *  ("loading UI begin") and is waiting for the client to enter
     *  its loading-screen state before sending the {@link
     *  ClientState#CROSS_PENDING_LOCATION}-advancing {@code 0x83/0x0c}. */
    CROSS_PENDING_LOAD,

    /** Server has emitted TCP {@code 0x83/0x0c Location} (destination
     *  BSP). Waiting for the client to acknowledge via its reliable
     *  ACK channel — either {@code 0x03/0x08} or by transitioning to
     *  {@link ClientState#CROSS_RECONNECTING} on TCP teardown. */
    CROSS_PENDING_LOCATION,

    /** Client tore down the old session; awaiting fresh TCP connect
     *  with a {@code 0x83/0x01 ResumeAuth}. */
    CROSS_RECONNECTING,

    /** HP=0 on the server side; client is displaying the death overlay
     *  + genrep selection. */
    DEAD_RESPAWNING,

    /** Session terminating. Terminal state. */
    LOGOUT;

    /** True iff the player has emitted a cross-related state-advance
     *  packet but the cross has not yet completed. */
    public static boolean isCrossPending(ClientState s) {
        return s == CROSS_PENDING_LOAD
            || s == CROSS_PENDING_LOCATION
            || s == CROSS_RECONNECTING;
    }
}
