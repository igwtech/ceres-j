package server.gameserver.packets.server_udp;

import java.nio.charset.StandardCharsets;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * WWORLDMGR <strong>Type-0x1E entity-SPAWN</strong> for a scripted NPC
 * (the actual {@code SCRIPTEDPLAYER} create — reliable
 * {@code 0x13 -> 0x03 -> 0x1e}).
 *
 * <h3>Why a new packet (vs the 0x28-only approach)</h3>
 *
 * <p>Authoritative client model, disassembled from
 * {@code neocronclient.exe} and documented in
 * {@code docs/protocol/RE_state_sync.md} §1, §1.1, §1.2 (raw decompiles
 * {@code docs/re_state_sync_dump3.txt}, {@code docs/re_state_sync_dump5.txt},
 * {@code docs/re_state_sync_dump6.txt}):
 *
 * <ul>
 *   <li>The WWORLDMGR dispatcher {@code FUN_00541f20} @ {@code 00541f20}
 *       switches on the application message's first body byte (the
 *       "Message Type"). <b>{@code case '('} (Type {@code 0x28})</b> is
 *       <em>only a WorldInfo update / spawn-<u>request</u> trigger</em>:
 *       if the entity does not yet exist it sends a C&rarr;S
 *       {@code 0x1d} request and returns &mdash; it <b>does not create
 *       the entity from a {@code 0x28}</b> (RE_state_sync.md §4.2,
 *       dump.txt {@code case '('} block).</li>
 *   <li><b>{@code case '\x1e'} (Type {@code 0x1E})</b> is the entity
 *       create: it calls
 *       {@code FUN_00540ab0(class@+1, id@+3, time, len-7, body+7)}
 *       (dump.txt {@code case '\x1e'}; {@code FUN_00540ab0} @
 *       {@code 00540ab0}, dump6.txt). {@code FUN_00540ab0} passes
 *       category {@code 3} into the WA factory registry
 *       {@code FUN_004d8a50}&rarr;{@code FUN_00567e50}; with
 *       <b>WA class {@code 0x0100}</b> and category {@code 3} the
 *       factory invokes the SCRIPTEDPLAYER <em>raw byte-stream</em>
 *       ctor {@code FUN_00699fd0} @ {@code 00699fd0}
 *       (dump5.txt {@code if (param_3 == 0x100) ... FUN_00699fd0}).</li>
 * </ul>
 *
 * <p>So a scripted NPC is only instantiated by a Type-0x1E message
 * carrying the SCRIPTEDPLAYER byte stream. {@link WorldNPCInfo}'s
 * {@code 0x03/0x28} remains the post-create WorldInfo refresh / the
 * spawn-request the client emits when it sees a {@code 0x28} for an
 * absent entity; it is kept unchanged. This class adds the missing
 * create message.
 *
 * <p>The retail capture
 * {@code strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap} (server
 * 157.90.195.74) was machine-decoded with {@code tools/npc-lifecycle.py}
 * for the scripted-city-NPC class (entities 266 "WSK" / 299 "WCOP" /
 * 325 "PATROL_COPBOT6"). It contains ONLY {@code 0x03/0x28} refreshes
 * for these NPCs &mdash; no literal {@code 0x1e} &mdash; because the
 * capture begins with the player <em>sitting next to already-spawned</em>
 * NPCs (their original Type-0x1E create fired before capture start).
 * The {@code 0x28} body carried in that pcap is therefore the
 * authoritative source for the shared <b>identity + position + script
 * fields</b> (id LE32, class {@code 0x0100}, X/Y/Z LE16, ASCIIZ
 * script_name then ASCIIZ orientation token) that the Type-0x1E
 * SCRIPTEDPLAYER stream also carries; those bytes are cross-checked in
 * {@link ScriptedPlayerSpawnByteIdentityTest} against both
 * RE_state_sync.md §1.2 and the pcap-decoded {@code 0x28} samples.
 *
 * <h3>Wire layout (every field traced — no speculation)</h3>
 *
 * <p>Outer: reliable {@link PacketBuilderUDP1303}
 * ({@code 0x13 [ctr LE2][ctr+sk LE2] [subLen LE2] 0x03 [seq LE2]}),
 * then the application body:
 *
 * <pre>
 *   [0]      1e                     WWORLDMGR Message Type 0x1E
 *                                   (FUN_00541f20 case '\x1e')
 *   [1-2]    00 01  (= 0x0100 LE16) WA class id -> FUN_00540ab0 param_1
 *                                   -> FUN_00567e50 param_3 == 0x100
 *                                   -> raw-stream ctor FUN_00699fd0
 *                                   (RE_state_sync.md §1.1; dump5.txt)
 *   [3-6]    entity_id LE32         FUN_005412d0 lookup key; ALSO the
 *                                   stream's [0] id (FUN_00541f20
 *                                   *(undefined4*)(pcVar2+3))
 *   [7+]     SCRIPTEDPLAYER stream  param_3 of FUN_00699fd0,
 *                                   len = (total body) - 7
 * </pre>
 *
 * <p>SCRIPTEDPLAYER stream (offsets relative to body+7; every offset
 * and width is the literal access in {@code FUN_00699fd0}, dump3.txt
 * lines cited; identical to RE_state_sync.md §1.2):
 *
 * <pre>
 *   stream[0x00]  LE32  world/entity id   in_ECX[0x1ec]=*param_3   (l.487)
 *   stream[0x04]  LE16  class/type id     in_ECX[0x163]            (l.488)
 *   stream[0x06]  LE16  pos X    ┐
 *   stream[0x08]  LE16  pos Y    ├ FUN_0054e210(*(p+6),*(p+2),*(p+10)) (l.491)
 *   stream[0x0a]  LE16  pos Z    ┘
 *   stream[0x0c]  1B    waypoint count    *(byte*)(param_3+3)      (l.558)
 *   stream[0x0d]  LE32  HP float          (float)*(int*)(p+0xd)    (l.499)
 *   stream[0x11]  10B   skill/attr array  loop p+i+0x11, i=0..9    (l.516)
 *   stream[0x1b]  LE16  flags             in_ECX[0x1e6]            (l.494)
 *   stream[0x1d]  ASCIIZ script_name      pcVar1=param_3+0x1d      (l.485)
 *   then          ASCIIZ orientation token (walk past NUL)         (l.528)
 *   then          waypoint-count × 6B (x,y,z LE16) FUN_0054e210    (l.570)
 * </pre>
 *
 * <h3>Field value sources</h3>
 * <ul>
 *   <li><b>entity id</b> (body[3..6] and stream[0]): the NPC world
 *       object id {@link NPC#getMapID()} — the same id
 *       {@link WorldNPCInfo} writes at its {@code 0x28} [3..4] and the
 *       id the {@code 0x2d} ping carries; the client keys its
 *       world-actor table on it.</li>
 *   <li><b>WA class</b> (body[1..2]) = constant {@code 0x0100}: the
 *       only value that routes {@code FUN_00567e50} to the SCRIPTEDPLAYER
 *       raw-stream ctor (dump5.txt {@code if (param_3 == 0x100)}). Not
 *       NPC-derived; it is the protocol selector for "this is a
 *       scripted player". Documented constant.</li>
 *   <li><b>stream class/type id</b> (stream[4]): {@link NPC#getType()}
 *       (the npc.def class), matching the {@code class_id} field
 *       {@link WorldNPCInfo} writes at its {@code 0x28} [11..12] (same
 *       pcap-decoded position: WSK {@code 4a0b}, PATROL {@code e227}).</li>
 *   <li><b>X/Y/Z</b> (stream[6/8/10]): {@link NPC#getXpos()} /
 *       {@code getYpos()} / {@code getZpos()} as LE16, the same triple
 *       order {@link WorldNPCInfo} writes (Y,Z,X at its {@code 0x28}
 *       [13..18]); here the {@code FUN_0054e210(x,y,z)} arg order in
 *       {@code FUN_00699fd0} is X@+6, Y@+8 ({@code param_3+2}),
 *       Z@+10.</li>
 *   <li><b>HP</b> (stream[0xd], IEEE-754 LE float): {@link NPC#getHP()}.
 *       {@code FUN_00699fd0} reads it as a {@code float} and clamps the
 *       live pool toward it (dump3.txt l.499-505). Documented: NPC HP
 *       as float.</li>
 *   <li><b>skill/attr array</b> (stream[0x11..0x1a], 10B): no per-NPC
 *       source exists in {@code npc_spawns}; {@code FUN_00699fd0} treats
 *       each byte {@code b} as a stat ceiling {@code b*4.0} only when it
 *       exceeds the existing skill (l.519-521), so all-zero is the
 *       benign "no skill override" instance. Documented default:
 *       all-zero (no unverified values invented).</li>
 *   <li><b>flags</b> (stream[0x1b..0x1c] LE16): no per-NPC source;
 *       written to {@code in_ECX[0x1e6]} with no further branch in the
 *       ctor. Documented default: {@code 0x0000}.</li>
 *   <li><b>script_name</b> (stream[0x1d] ASCIIZ): the resolved AI
 *       script (curated {@code npc_spawns} script, or the
 *       {@code world_npcs.actor_name} bridged in by #178), falling back
 *       to the display name if unset &mdash; identical resolution and
 *       fallback to {@link WorldNPCInfo#writeBody} so the two emitters
 *       cannot drift. An empty/wrong token =&gt;
 *       {@code "SCRIPTEDPLAYER : Script spawn failed"} (dump3.txt
 *       l.549) and the NPC never instantiates.</li>
 *   <li><b>orientation token</b> (ASCIIZ after script_name): the NPC
 *       angle as signed decimal ASCII, identical to the second token
 *       {@link WorldNPCInfo#writeBody} emits.</li>
 *   <li><b>waypoint count</b> (stream[0xc]) = {@code 0}, and no
 *       waypoint records follow: {@code npc_spawns} carries no patrol
 *       path; {@code FUN_00699fd0} explicitly null-guards a zero count
 *       (l.560 {@code if (bVar2 == 0)}). Documented default: 0
 *       waypoints (stationary).</li>
 * </ul>
 */
public class ScriptedPlayerSpawn extends PacketBuilderUDP1303 {

    /**
     * WA class id that routes the WWORLDMGR Type-0x1E WA-factory walk
     * ({@code FUN_00540ab0}&rarr;{@code FUN_004d8a50}&rarr;{@code
     * FUN_00567e50}) to the SCRIPTEDPLAYER raw byte-stream ctor
     * {@code FUN_00699fd0} (dump5.txt: {@code if (param_3 == 0x100)
     * ... FUN_00699fd0}). Protocol selector constant, not NPC data.
     */
    public static final int WA_CLASS_SCRIPTEDPLAYER = 0x0100;

    /** WWORLDMGR Message Type for an entity SPAWN (FUN_00541f20
     *  {@code case '\x1e'}). */
    public static final int WWORLDMGR_TYPE_SPAWN = 0x1E;

    public ScriptedPlayerSpawn(Player pl, NPC npc) {
        super(pl);
        write(WWORLDMGR_TYPE_SPAWN);
        writeBody(this, npc);
    }

    /**
     * Write the post-{@code 0x1e} body (WA class + entity id + the
     * SCRIPTEDPLAYER stream) for {@code npc} into {@code b}. Offsets in
     * comments are body-relative ({@code 0x1e} is body [0], so the
     * first byte written here is body [1]).
     */
    static void writeBody(PacketBuilderUDP1303 b, NPC npc) {
        int entityId = npc.getMapID();

        // [1-2] WA class id 0x0100 -> SCRIPTEDPLAYER raw-stream ctor.
        b.writeShort(WA_CLASS_SCRIPTEDPLAYER);
        // [3-6] entity id LE32 (FUN_005412d0 lookup key).
        b.writeInt(entityId);

        // ---- SCRIPTEDPLAYER stream (body+7 = FUN_00699fd0 param_3) ----
        // stream[0x00] LE32 world/entity id (in_ECX[0x1ec]=*param_3).
        b.writeInt(entityId);
        // stream[0x04] LE16 class/type id (in_ECX[0x163]); same value
        // WorldNPCInfo writes at its 0x28 [11..12].
        b.writeShort(npc.getType());
        // stream[0x06] LE16 pos X, [0x08] LE16 pos Y, [0x0a] LE16 pos Z
        // — FUN_0054e210(*(p+6) X, *(p+2) Y, *(p+10) Z) (dump3 l.491).
        b.writeShort(npc.getXpos());
        b.writeShort(npc.getYpos());
        b.writeShort(npc.getZpos());
        // stream[0x0c] 1B waypoint count = 0 (no patrol path in
        // npc_spawns; ctor null-guards a zero count, dump3 l.560).
        b.write(0x00);
        // stream[0x0d] LE32 HP as IEEE-754 float (read (float)*(int*)
        // (param_3+0xd) and clamped, dump3 l.499-505).
        b.writeFloat((float) npc.getHP());
        // stream[0x11..0x1a] 10B skill/attr array. No per-NPC source;
        // each byte b becomes stat ceiling b*4 only if it exceeds the
        // existing skill (dump3 l.519-521) — all-zero = no override.
        for (int i = 0; i < 10; i++) {
            b.write(0x00);
        }
        // stream[0x1b..0x1c] LE16 flags. No per-NPC source; written to
        // in_ECX[0x1e6] with no further ctor branch. Default 0x0000.
        b.writeShort(0x0000);
        // stream[0x1d] ASCIIZ script_name. Identical resolution +
        // fallback to WorldNPCInfo.writeBody so the create and the
        // refresh cannot disagree on the script token.
        String script = npc.getScriptName();
        if (script == null || script.isEmpty()) {
            script = npc.getName();
        }
        if (script == null) {
            script = "";
        }
        b.write(script.getBytes(StandardCharsets.US_ASCII));
        b.write(0x00);
        // ASCIIZ orientation token (signed decimal angle), identical to
        // the second token WorldNPCInfo.writeBody emits.
        b.write(Integer.toString(npc.getAngle())
                .getBytes(StandardCharsets.US_ASCII));
        b.write(0x00);
        // waypoint records: none (count == 0 above).
    }
}
