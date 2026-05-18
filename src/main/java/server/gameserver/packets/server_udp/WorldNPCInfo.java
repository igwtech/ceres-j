package server.gameserver.packets.server_udp;

import java.nio.charset.StandardCharsets;

import server.gameserver.NPC;
import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Reliable {@code 0x03 -> 0x28} WorldInfo packet for a single NPC.
 *
 * <p><strong>Retail-evidenced layout.</strong> Byte-diffed 2026-05-16
 * against the verified retail decode in
 * {@code docs/protocol/packets/udp_s2c_03_28.md} and the three raw
 * {@code 0x03/0x28} samples preserved in
 * {@code docs/protocol/_data/packets.json} (entities 0x0149, 0x013e,
 * 0x0130 from PLAZA-&gt;PEPPER) plus the hand-decoded AUGUSTO "PMAN"
 * (entity 0x0124) reference sample. Offsets below are relative to the
 * full inner body <em>including</em> the {@code 0x28} sub-op, matching
 * the catalog decode:
 *
 * <pre>
 *   [0]      28                     sub-opcode (written by caller)
 *   [1-2]    00 01                  CONSTANT (every retail sample)
 *   [3-4]    world_obj_id LE16      per-NPC (== the 0x1b / 0x2d id)
 *   [5-6]    00 00                  CONSTANT
 *   [7-10]   instance_handle LE32   per-NPC unique, stable across all
 *                                   of this NPC's packets. RETAIL: a
 *                                   server-assigned handle; the three
 *                                   preserved samples carry distinct
 *                                   values (0x78edef93, 0x78edeb27,
 *                                   0x78ee1d76) and the AUGUSTO sample
 *                                   0x379a516a. The pre-#178 constant
 *                                   8958887 (0x0088B3A7) appears in
 *                                   NONE of the retail evidence.
 *   [11-12]  class_id LE16          per-NPC class (0x28-space; e.g.
 *                                   PMAN 0x014f, entity-0149 0x003b).
 *                                   NOTE: this is a different field
 *                                   from raw 0x1b's entity_class_id.
 *   [13-14]  Y LE16
 *   [15-16]  Z LE16
 *   [17-18]  X LE16
 *   [19-35]  17-byte state/attr     RETAIL: a state block whose exact
 *                                   sub-structure is an open question.
 *                                   Machine-decoded from the task #178
 *                                   live retail pcap
 *                                   strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap
 *                                   (server 157.90.195.74) for the
 *                                   EXACT failing scripted-city-NPC
 *                                   class (Type 15 / SCRIPTEDPLAYER):
 *                                   entities 266/299/325 ("WSK"/"WCOP"/
 *                                   "PATROL_COPBOT6") all carry a
 *                                   **17-byte** block here, e.g. WSK
 *                                   00 bc030000 00 0c0b 07 0909 05 0000
 *                                   00 0000; WCOP/PATROL
 *                                   00 cf3d0000 00 bbbb 00 c6c6 0000
 *                                   00 0000. The leading ~11 bytes are
 *                                   per-NPC runtime state with no
 *                                   reproducible derivation (same
 *                                   category as the [7-10] handle); the
 *                                   trailing 6 bytes are zero in every
 *                                   sample. We emit 17 zero bytes — a
 *                                   retail-valid "no-state" instance —
 *                                   inventing no unverified values. The
 *                                   PRE-#178c value was 15 bytes: the
 *                                   doc's hand-transcribed AUGUSTO PMAN
 *                                   (a NON-scripted NPC) showed 15, but
 *                                   the machine-decoded live pcap of the
 *                                   actual failing scripted class is
 *                                   unambiguously 17 across all three
 *                                   samples. 15 left every field from
 *                                   the script name on 2 bytes early, so
 *                                   the client Type-15 parser read a
 *                                   malformed record ("@WWORLDMGR :
 *                                   Corrupted Message Type:15, Size:21"
 *                                   + truncated names like 'ader_pa').
 *   [36+]    script_name\0          ASCII per-NPC AI script token —
 *                                   the world-.dat actorName the client
 *                                   SCRIPTEDPLAYER ctor resolves (e.g.
 *                                   "PMAN", "WSK", "WCOP",
 *                                   "PATROL_COPBOT6"). Proven against
 *                                   the retail live pcap 2026-05-17
 *                                   (task #178): empty/wrong here =>
 *                                   "Unable to find script" => NPC
 *                                   never instantiates.
 *   [+]      orientation\0          ASCII signed decimal (e.g. "1")
 *   [+]      (scripted NPCs only)   retail copbots/walkers append a
 *                                   third NUL-terminated token (a small
 *                                   per-NPC runtime index, e.g. "9" /
 *                                   "0" / "/"); like the [19+] state
 *                                   block it is per-NPC live state with
 *                                   no reproducible derivation, and the
 *                                   byte-pinned AUGUSTO "PMAN" reference
 *                                   has only the two tokens, so we emit
 *                                   the two-token form (retail-valid:
 *                                   PMAN renders with exactly this).
 * </pre>
 *
 * <p>What #178 corrected vs the pre-#178 implementation:
 * <ul>
 *   <li>{@code [7..10]} was hard-coded to the constant {@code 8958887}
 *       for <em>every</em> NPC. The client keys its world-object table
 *       on this handle, so every NPC collided onto one object
 *       reference. Retail proves this field is per-NPC and that the
 *       constant never appears. Now a deterministic per-NPC handle.</li>
 *   <li>The pre-#178 body placed a spurious {@code 0x22} "zone area"
 *       byte and a 16-byte filler between X and the strings, landing
 *       the strings one byte late. Retail has exactly a 15-byte block
 *       there. Now 15 bytes.</li>
 *   <li>The trailing strings were {@code scriptName\0 modelName\0}
 *       written from the wrong source; retail is
 *       {@code script_name\0 orientation\0} where the first token is
 *       the world-.dat NPC actorName. The pre-#178 bulk world_npcs
 *       bridge passed {@code script_name=""} and the body wrote the
 *       display name, so scripted NPCs got an empty/wrong script token
 *       and never instantiated. Now the resolved script name (curated
 *       AI script, or world_npcs.actor_name) is written, falling back
 *       to the display name only when no script is set. Verified
 *       2026-05-17 against the retail live pcap (entities 266/299/325
 *       => "WSK"/"WCOP"/"PATROL_COPBOT6").</li>
 * </ul>
 *
 * <p>An earlier (unvalidated) #178 WIP attempt wrote {@code [18]=00}
 * then a 4-byte {@code maxHP} as a "class_attr" — that was pure
 * speculation (no retail sample shows an HP value there: entity-0149's
 * bytes are {@code 00 0e 01 00 00}, not an HP magnitude) and it left
 * the body two bytes too long, mislocating the strings. Discarded.
 */
public class WorldNPCInfo extends PacketBuilderUDP1303 {

	public WorldNPCInfo(Player pl, NPC npc) {
		super(pl);
		write(0x28);
		writeBody(this, npc);
	}

	/**
	 * Write the post-{@code 0x28} body for {@code npc} into {@code b}.
	 * Shared with {@link ZoneStateCompoundPacket} so the two
	 * {@code 0x03/0x28} emitters cannot drift apart again. Offsets in
	 * comments are doc-relative (i.e. {@code 0x28} is at doc [0], so
	 * the first byte written here is doc [1]).
	 */
	static void writeBody(PacketBuilderUDP1303 b, NPC npc) {
		// [1-2] constant
		b.write(0x00);
		b.write(0x01);
		// [3-4] world-object id
		b.writeShort(npc.getMapID());
		// [5-6] constant
		b.write(0x00);
		b.write(0x00);
		// [7-10] per-NPC unique, stable instance handle
		b.writeInt(npc.getWorldInstanceHandle());
		// [11-12] class id (0x28-space)
		b.writeShort(npc.getType());
		// [13-14] Y
		b.writeShort(npc.getYpos());
		// [15-16] Z
		b.writeShort(npc.getZpos());
		// [17-18] X
		b.writeShort(npc.getXpos());
		// [19-35] 17-byte state/attr block. Length byte-pinned 2026-05-17
		// (task #178c) from the live retail pcap
		// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap for the exact
		// failing scripted-city-NPC class: entities 266/299/325
		// ("WSK"/"WCOP"/"PATROL_COPBOT6") each carry a 17-byte block
		// before the script name (script name starts at doc [36], not
		// [34]). The leading bytes are unreproducible per-NPC runtime
		// state (same category as the [7-10] handle); trailing 6 bytes
		// are zero in every sample. All-zero is a retail-valid no-state
		// instance. Emitting 15 (the pre-#178c value, from the doc's
		// hand-decoded NON-scripted PMAN) shifted every byte from the
		// script name on 2 bytes early -> client logged "@WWORLDMGR :
		// Corrupted Message Type:15, Size:21" and the NPC never spawned.
		for (int i = 0; i < 17; i++) {
			b.write(0x00);
		}
		// [36..] script_name\0 orientation\0
		//
		// The trailing token is the per-NPC AI **script name** the
		// client SCRIPTEDPLAYER ctor reads to look the spawn up in its
		// script-factory map (PROTOCOL.md ~1287; client FUN_0069a580 ->
		// FUN_0081e310). Proven 2026-05-17 against the retail live pcap
		// strace/RETAIL_LIVE_p1p3_sit_npc_20260517.pcap (server
		// 157.90.195.74, p1/p3 sit-near-NPC capture): retail entities
		// 266/299/325 carry "WSK"/"WCOP"/"PATROL_COPBOT6" here — the
		// world-.dat NPC actorName (cf. WorldDatParser.NpcEntry.actorName,
		// e.g. reaktor's "STATIC"). An empty or display-only string makes
		// the client log "Unable to find script" and the NPC never
		// instantiates — the task #178 "NPCs don't render" symptom.
		//
		// Source priority: the resolved script_name (curated npc_spawns
		// AI script, or the world_npcs.actor_name bridged into it); fall
		// back to the display name only if no script is set so a curated
		// spawn missing its script column still emits a non-empty token
		// rather than a zero-length string the client rejects.
		String script = npc.getScriptName();
		if (script == null || script.isEmpty()) {
			script = npc.getName();
		}
		if (script == null) {
			script = "";
		}
		b.write(script.getBytes(StandardCharsets.US_ASCII));
		b.write(0x00);
		b.write(Integer.toString(npc.getAngle())
				.getBytes(StandardCharsets.US_ASCII));
		b.write(0x00);
	}
}
