--[[
  Neocron Evolution 2.5 Wireshark Lua dissector
  ==============================================

  Reverse-engineered protocol of the retail NCE 2.5.x client and the
  Ceres-J server emulator.

  Install:
    Linux:    ~/.local/lib/wireshark/plugins/neocron2.lua
    macOS:    ~/.config/wireshark/plugins/neocron2.lua
    Windows:  %APPDATA%\Wireshark\plugins\neocron2.lua
  Then Analyze -> Reload Lua Plugins (Ctrl+Shift+L) or restart Wireshark.

  Coverage:
    - TCP 12000 (gameserver)
        FE-framed PDUs, opcode dispatch, full field parsing for
        Auth (0x8480), AuthB (0x8301), AuthAck (0x8381),
        UDPServerData (0x8305), Location (0x830c), CharList (0x8385),
        Gamedata (0x873a), GameinfoReady (0x830d), HandshakeA/B/C,
        ClientKicked (0x8303), CustomChat (0x8317).
    - UDP 5000-5999 (per-session game traffic)
        Per-packet LFSR-CFB decryption of BOTH directions
        (per WireEncrypt.java / FUN_00560090 / FUN_0055ff30).
        Plaintext fall-through for legacy/unencrypted captures.
        Top-level: 0x01 Handshake, 0x03 SyncUDP, 0x04 UDPAlive,
                   0x08 Abort, 0x13 Gamedata.
        Gamedata (0x13) sub-packet chain with 2-byte LE length prefix.
        Sub-packet types: 0x02 wrapper, 0x03 reliable wrapper,
                          0x0b CPing, 0x0c TimeSync, 0x1f game ops,
                          0x20 movement, 0x25 info ops, 0x2a request pos.
        Reliable (0x03) sub-types: 0x07 Multipart (with chain_key,
        discriminator, total_size header), 0x08 ZoningEnd, 0x0d TimeSync,
        0x1b Group1B, 0x1f GamePackets, 0x22-0x31 info/world packets,
        0x2c StartPos, 0x2d NPCData, 0x2e Weather, 0x2f UpdateModel.
        WorldInfo (0x28) NPC fields: world ID, type ID, position,
        script_name, model_name.

  Out of scope:
    - CharInfo multipart cross-datagram reassembly (each fragment is
      annotated with its chain_key/discriminator/total_size header)
    - Editable / injectable fields

  Source-of-truth references:
    - docs/PROTOCOL.md
    - docs/PACKET_REFERENCE.md
    - src/main/java/server/networktools/WireEncrypt.java
    - src/main/java/server/networktools/PacketBuilderUDP13.java
    - src/main/java/server/gameserver/packets/GamePacketReaderUDP.java
]]

local bit = require("bit")

local nc2_tcp = Proto("nc2_tcp", "Neocron 2 TCP")
local nc2_udp = Proto("nc2_udp", "Neocron 2 UDP")

-- ============================================================
-- Opcode name tables
-- ============================================================

local TCP_OPCODES = {
  [0x8000] = "HandshakeB (C->S)",
  [0x8001] = "HandshakeA (S->C)",
  [0x8003] = "HandshakeC (S->C)",
  [0x8301] = "AuthB (C->S)",
  [0x8303] = "ClientKicked (S->C)",
  [0x8305] = "UDPServerData (S->C)",
  [0x830c] = "Location (S->C)",
  [0x830d] = "GameinfoReady (S->C)",
  [0x8317] = "CustomChat (C<->S)",
  [0x8318] = "ChatFailed (S->C)",
  [0x8381] = "AuthAck (S->C)",
  [0x8385] = "CharList (S->C)",
  [0x8386] = "RequestSuccess/Failed (S->C)",
  [0x838f] = "TCP Keepalive (S->C)",
  [0x8480] = "Auth (C->S)",
  [0x8482] = "CharOp (C->S)",
  [0x8737] = "GetGamedata (C->S)",
  [0x873a] = "Gamedata (S->C)",
  [0x873c] = "GetUDPConnection (C->S)",
  [0x7b00] = "GetPatchVersion (C->S)",
  [0x3702] = "PatchVersion (S->C)",
}

-- 0x8482 sub-opcodes (CharOp byte 2)
local TCP_CHAROP = {
  [0x03] = "DeleteCharacter",
  [0x05] = "CheckCharacterName",
  [0x06] = "GetCharList",
  [0x07] = "CreateCharacter",
}

-- Top-level UDP first byte (after decryption)
local UDP_TOP_OPCODES = {
  [0x01] = "HandshakeUDP",
  [0x03] = "SyncUDP",
  [0x04] = "UDPAlive",
  [0x08] = "AbortSession",
  [0x13] = "Gamedata",
}

-- 0x13 gamedata sub-packet first byte
local UDP_SUB_OPCODES = {
  [0x02] = "Wrapper-0x02 (counter+inner)",
  [0x03] = "Reliable (counter+sub-type)",
  [0x0b] = "CPing/SPing",
  [0x0c] = "GetTimeSync",
  [0x1f] = "Game ops (0x1f family)",
  [0x20] = "Movement",
  [0x25] = "Info ops (0x25 family)",
  [0x2a] = "RequestPositionUpdate",
}

-- 0x03 reliable sub-types
local UDP_RELIABLE_SUBTYPES = {
  [0x01] = "Resend",
  [0x07] = "Multipart",
  [0x08] = "ZoningEnd",
  [0x0d] = "TimeSync",
  [0x1b] = "Group1B (NPC/world)",
  [0x1f] = "GamePackets",
  [0x22] = "InfoRequest",
  [0x23] = "InfoResponse",
  [0x25] = "PlayerInfo (Long)",
  [0x26] = "RemoveWorldItem",
  [0x27] = "RequestWorldInfo",
  [0x28] = "WorldInfo",
  [0x2b] = "CityCom",
  [0x2c] = "StartPosResponse",
  [0x2d] = "NPCData",
  [0x2e] = "Weather",
  [0x2f] = "UpdateModel",
  [0x30] = "ShortPlayerInfo / PoolStatusBroadcast",
  [0x31] = "RequestShortPlayerInfo",
  [0x33] = "ChatList",
  [0x50] = "PoolUpdate (delta)",
}

-- 0x1f game sub-opcodes (after 0x1f wrapper)
local UDP_1F_OPCODES = {
  [0x01] = "Shoot",
  [0x02] = "Jump",
  [0x16] = "Death",
  [0x17] = "Use",
  [0x18] = "NPCScript",
  [0x19] = "Dialog",
  [0x1a] = "NPCResponse",
  [0x1b] = "LocalChat",
  [0x1e] = "ItemMove",
  [0x1f] = "SlotUse",
  [0x22] = "ExitChair",
  [0x27] = "CloseConnection",
  [0x29] = "HackSuccess",
  [0x2c] = "HackFail",
  [0x2e] = "Outfitter",
  [0x2f] = "GenRep",
  [0x30] = "PoolStatusBroadcast",
  [0x33] = "ChatList",
  [0x38] = "WorldAccess",
  [0x3b] = "OtherChat",
  [0x3d] = "QuickCommand",
  [0x3e] = "TradeSetting",
  [0x40] = "JoinTeam",
  [0x4c] = "ChangeChannels",
  [0x4e] = "PlayerTrade",
  [0x50] = "PoolUpdate (delta)",
}

-- 0x25 info sub-opcodes
local UDP_25_OPCODES = {
  [0x01] = "StartProcessor",
  [0x03] = "StopProcessor",
  [0x04] = "IncreaseSubskill / CashUpdate?",
  [0x06] = "Skillboost",
  [0x07] = "UseBooster",
  [0x0b] = "LevelUp",
  [0x0c] = "SwitchWeaponMod",
  [0x11] = "UpdateMoney",
  [0x13] = "Confirmation",
  [0x14] = "MoveInventoryItem",
  [0x16] = "StartReload",
  [0x17] = "CombineItems",
  [0x18] = "RPOSCommand",
  [0x19] = "MainSkills",
  [0x1f] = "SynapticImpairment / Soullight",
  [0x25] = "StopReload",
}

-- Pool type byte inside PoolUpdate (0x1f -> 0x50)
local POOL_TYPES = {
  [0x04] = "HP",
  [0x05] = "PSI",
  [0x06] = "STA",
}

-- Weather IDs in 0x03 -> 0x2e Weather packet
local WEATHER_IDS = {
  [0] = "Clear",
  [1] = "Rain",
  [2] = "Fog",
  [3] = "Storm",
}

-- Top-level wire bytes that flag a successfully-decrypted plaintext.
local KNOWN_HEADERS = { 0x01, 0x03, 0x04, 0x08, 0x13 }
local KNOWN_HEADER_SET = {}
for _, v in ipairs(KNOWN_HEADERS) do KNOWN_HEADER_SET[v] = true end

-- ============================================================
-- ProtoField declarations
-- ============================================================

-- TCP outer
local f_tcp_magic   = ProtoField.uint8 ("nc2_tcp.magic",   "Magic (0xFE)", base.HEX)
local f_tcp_len     = ProtoField.uint16("nc2_tcp.len",     "Payload length", base.DEC)
local f_tcp_opcode  = ProtoField.uint16("nc2_tcp.opcode",  "Opcode", base.HEX, TCP_OPCODES)
local f_tcp_opname  = ProtoField.string("nc2_tcp.opname",  "Op name")
local f_tcp_raw     = ProtoField.bytes ("nc2_tcp.raw",     "Raw payload")

-- Auth / AuthB
local f_auth_key      = ProtoField.uint8 ("nc2_tcp.auth.key",     "Encryption key", base.HEX)
local f_auth_userlen  = ProtoField.uint16("nc2_tcp.auth.userlen", "Username length", base.DEC)
local f_auth_passlen  = ProtoField.uint16("nc2_tcp.auth.passlen", "Password length field", base.DEC)
local f_auth_user     = ProtoField.string("nc2_tcp.auth.user",    "Username")
local f_auth_passblob = ProtoField.bytes ("nc2_tcp.auth.passblob","Encrypted password")
local f_auth_spot     = ProtoField.uint32("nc2_tcp.auth.spot",    "Character spot", base.DEC)
local f_auth_clport   = ProtoField.uint32("nc2_tcp.auth.clport",  "Client port", base.DEC)

-- AuthAck
local f_aa_account = ProtoField.uint32("nc2_tcp.aa.account", "Account ID", base.DEC)
local f_aa_session = ProtoField.bytes ("nc2_tcp.aa.session", "Session token")

-- UDPServerData
local f_usd_account = ProtoField.uint32("nc2_tcp.usd.account", "Account ID", base.DEC)
local f_usd_char    = ProtoField.uint32("nc2_tcp.usd.char",    "Character ID", base.DEC)
local f_usd_ip      = ProtoField.ipv4  ("nc2_tcp.usd.ip",      "Server IP")
local f_usd_port    = ProtoField.uint16("nc2_tcp.usd.port",    "UDP port", base.DEC)
local f_usd_flags   = ProtoField.uint32("nc2_tcp.usd.flags",   "Flags", base.HEX)
local f_usd_sid     = ProtoField.bytes ("nc2_tcp.usd.sid",     "Session ID (wire, 127-x)")
local f_usd_sid_orig= ProtoField.string("nc2_tcp.usd.sid_orig","Session ID (original)")

-- Location
local f_loc_id    = ProtoField.uint32("nc2_tcp.loc.id",    "Zone ID", base.DEC)
local f_loc_unk1  = ProtoField.uint32("nc2_tcp.loc.unk1",  "Unknown 1", base.HEX)
local f_loc_unk2  = ProtoField.uint32("nc2_tcp.loc.unk2",  "Unknown 2", base.HEX)
local f_loc_world = ProtoField.string("nc2_tcp.loc.world", "World file")

-- CharList
local f_cl_count      = ProtoField.uint16("nc2_tcp.cl.count",      "Slot count", base.DEC)
local f_cl_structsize = ProtoField.uint16("nc2_tcp.cl.structsize", "Slot struct size", base.DEC)
local f_cl_char_id    = ProtoField.uint32("nc2_tcp.cl.char_id",    "Character ID", base.DEC)
local f_cl_char_name  = ProtoField.string("nc2_tcp.cl.char_name",  "Character name")

-- ClientKicked
local f_kick_reason   = ProtoField.uint16("nc2_tcp.kick.reason", "Reason code", base.DEC)
local f_kick_text     = ProtoField.string("nc2_tcp.kick.text",   "Reason text")

-- CustomChat
local f_chat_text = ProtoField.string("nc2_tcp.chat.text", "Chat text")

-- UDP common
local f_udp_dir      = ProtoField.string("nc2_udp.dir",      "Direction")
local f_udp_enc      = ProtoField.bool  ("nc2_udp.enc",      "Encrypted")
local f_udp_seed     = ProtoField.uint16("nc2_udp.seed",     "Cipher seed (LE16)", base.HEX)
local f_udp_enc_len  = ProtoField.uint16("nc2_udp.enc_len",  "Encrypted payload length", base.DEC)
local f_udp_opcode   = ProtoField.uint8 ("nc2_udp.opcode",   "Opcode", base.HEX, UDP_TOP_OPCODES)
local f_udp_opname   = ProtoField.string("nc2_udp.opname",   "Op name")
local f_udp_decoded  = ProtoField.string("nc2_udp.decoded",  "Decoded plaintext (hex)")
local f_udp_raw      = ProtoField.bytes ("nc2_udp.raw",      "Raw payload")

-- UDPAlive (0x04)
local f_al_mapid    = ProtoField.uint8 ("nc2_udp.alive.mapid",  "Map ID", base.DEC)
local f_al_iface    = ProtoField.uint8 ("nc2_udp.alive.iface",  "Interface ID", base.DEC)
local f_al_negkey   = ProtoField.int16 ("nc2_udp.alive.negkey", "-Session key", base.DEC)
local f_al_port     = ProtoField.uint16("nc2_udp.alive.port",   "Session port", base.DEC)

-- Handshake (0x01)
local f_hs_session  = ProtoField.bytes ("nc2_udp.hs.session", "Session data")
local f_hs_iface    = ProtoField.uint8 ("nc2_udp.hs.iface",   "Interface ID", base.DEC)

-- Gamedata (0x13) outer
local f_gd_counter1 = ProtoField.uint16("nc2_udp.gd.counter",         "Counter", base.DEC)
local f_gd_counter2 = ProtoField.uint16("nc2_udp.gd.counter_session", "Counter+sessionKey", base.HEX)
local f_gd_sub_len  = ProtoField.uint16("nc2_udp.gd.sub_len",         "Sub-packet length (LE16)", base.DEC)
local f_gd_sub_op   = ProtoField.uint8 ("nc2_udp.gd.sub_op",          "Sub-opcode", base.HEX, UDP_SUB_OPCODES)
local f_gd_sub_name = ProtoField.string("nc2_udp.gd.sub_name",        "Sub-packet")
local f_gd_sub_raw  = ProtoField.bytes ("nc2_udp.gd.sub_raw",         "Sub-payload")

-- Reliable (0x03) wrapper
local f_rel_seq      = ProtoField.uint16("nc2_udp.rel.seq",     "Reliable sequence", base.DEC)
local f_rel_subtype  = ProtoField.uint8 ("nc2_udp.rel.subtype", "Reliable sub-type", base.HEX, UDP_RELIABLE_SUBTYPES)
local f_rel_subname  = ProtoField.string("nc2_udp.rel.subname", "Reliable sub-name")

-- 0x02 wrapper
local f_w02_seq     = ProtoField.uint16("nc2_udp.w02.seq",     "0x02 sequence", base.DEC)
local f_w02_subtype = ProtoField.uint8 ("nc2_udp.w02.subtype", "0x02 sub-type", base.HEX, UDP_RELIABLE_SUBTYPES)

-- Multipart (0x03 -> 0x07)
local f_mp_chainkey  = ProtoField.uint8 ("nc2_udp.mp.chainkey",  "Chain key", base.HEX)
local f_mp_pad0      = ProtoField.uint8 ("nc2_udp.mp.pad0",      "Pad (0x00)", base.HEX)
local f_mp_disc      = ProtoField.uint8 ("nc2_udp.mp.disc",      "Discriminator", base.HEX)
local f_mp_total     = ProtoField.uint32("nc2_udp.mp.total",     "Total reassembled size (LE32)", base.DEC)
local f_mp_fragment  = ProtoField.bytes ("nc2_udp.mp.fragment",  "Fragment payload")

-- ZoningEnd (0x03 -> 0x08)
local f_ze_mapid  = ProtoField.uint16("nc2_udp.ze.mapid",  "Map ID (LE16)", base.DEC)
local f_ze_status = ProtoField.uint8 ("nc2_udp.ze.status", "Status (0=ok)", base.HEX)

-- Weather (0x03 -> 0x2e)
local f_we_mapid     = ProtoField.uint16("nc2_udp.weather.mapid",     "Map ID", base.DEC)
local f_we_id        = ProtoField.uint8 ("nc2_udp.weather.id",        "Weather ID", base.DEC, WEATHER_IDS)
local f_we_intensity = ProtoField.uint8 ("nc2_udp.weather.intensity", "Intensity", base.DEC)
local f_we_duration  = ProtoField.uint32("nc2_udp.weather.duration",  "Duration (s)", base.DEC)

-- WorldInfo (0x03 -> 0x28)
local f_wi_const    = ProtoField.uint16("nc2_udp.wi.const",    "Constant 0x0001", base.HEX)
local f_wi_objid    = ProtoField.uint16("nc2_udp.wi.objid",    "World object ID (LE16)", base.DEC)
local f_wi_pad1     = ProtoField.uint16("nc2_udp.wi.pad1",     "Padding", base.HEX)
local f_wi_instref  = ProtoField.uint32("nc2_udp.wi.instref",  "World instance ref (LE32)", base.HEX)
local f_wi_typeid   = ProtoField.uint16("nc2_udp.wi.typeid",   "NPC type ID (LE16)", base.DEC)
local f_wi_y        = ProtoField.uint16("nc2_udp.wi.y",        "Y position", base.DEC)
local f_wi_z        = ProtoField.uint16("nc2_udp.wi.z",        "Z position", base.DEC)
local f_wi_x        = ProtoField.uint16("nc2_udp.wi.x",        "X position", base.DEC)
local f_wi_subsec   = ProtoField.uint8 ("nc2_udp.wi.subsec",   "Zone sub-sector", base.HEX)
local f_wi_combat   = ProtoField.uint8 ("nc2_udp.wi.combat",   "Combat class", base.DEC)
local f_wi_script   = ProtoField.string("nc2_udp.wi.script",   "Script name")
local f_wi_model    = ProtoField.string("nc2_udp.wi.model",    "Model name")

-- 0x1f game ops
local f_g1f_op   = ProtoField.uint8("nc2_udp.g1f.op",   "0x1f sub-op", base.HEX, UDP_1F_OPCODES)
local f_g1f_name = ProtoField.string("nc2_udp.g1f.name", "0x1f name")

-- PoolUpdate (0x1f -> 0x50)
local f_pu_delta = ProtoField.int32 ("nc2_udp.pool.delta", "Pool delta (signed LE32)", base.DEC)
local f_pu_type  = ProtoField.uint8 ("nc2_udp.pool.type",  "Pool type", base.HEX, POOL_TYPES)
local f_pu_max   = ProtoField.uint16("nc2_udp.pool.max",   "Max (LE16)", base.DEC)

-- PoolStatusBroadcast (0x1f -> 0x30) — 14B snapshot
local f_ps_hp     = ProtoField.uint16("nc2_udp.pool.hp",     "HP", base.DEC)
local f_ps_psi    = ProtoField.uint16("nc2_udp.pool.psi",    "PSI", base.DEC)
local f_ps_sta    = ProtoField.uint16("nc2_udp.pool.sta",    "STA", base.DEC)
local f_ps_maxhp1 = ProtoField.uint16("nc2_udp.pool.maxhp1", "MaxHP (1)", base.DEC)
local f_ps_maxhp2 = ProtoField.uint16("nc2_udp.pool.maxhp2", "MaxHP (2)", base.DEC)

-- 0x25 info ops
local f_g25_op   = ProtoField.uint8 ("nc2_udp.g25.op",   "0x25 sub-op", base.HEX, UDP_25_OPCODES)
local f_g25_name = ProtoField.string("nc2_udp.g25.name", "0x25 name")

-- Movement (0x20)
local f_mv_x     = ProtoField.float("nc2_udp.move.x",   "X")
local f_mv_y     = ProtoField.float("nc2_udp.move.y",   "Y")
local f_mv_z     = ProtoField.float("nc2_udp.move.z",   "Z")
local f_mv_yaw   = ProtoField.float("nc2_udp.move.yaw", "Yaw")
local f_mv_raw   = ProtoField.bytes("nc2_udp.move.raw", "Movement payload")

nc2_tcp.fields = {
  f_tcp_magic, f_tcp_len, f_tcp_opcode, f_tcp_opname, f_tcp_raw,
  f_auth_key, f_auth_userlen, f_auth_passlen, f_auth_user, f_auth_passblob,
  f_auth_spot, f_auth_clport,
  f_aa_account, f_aa_session,
  f_usd_account, f_usd_char, f_usd_ip, f_usd_port, f_usd_flags, f_usd_sid, f_usd_sid_orig,
  f_loc_id, f_loc_unk1, f_loc_unk2, f_loc_world,
  f_cl_count, f_cl_structsize, f_cl_char_id, f_cl_char_name,
  f_kick_reason, f_kick_text,
  f_chat_text,
}

nc2_udp.fields = {
  f_udp_dir, f_udp_enc, f_udp_seed, f_udp_enc_len,
  f_udp_opcode, f_udp_opname, f_udp_decoded, f_udp_raw,
  f_al_mapid, f_al_iface, f_al_negkey, f_al_port,
  f_hs_session, f_hs_iface,
  f_gd_counter1, f_gd_counter2, f_gd_sub_len, f_gd_sub_op, f_gd_sub_name, f_gd_sub_raw,
  f_rel_seq, f_rel_subtype, f_rel_subname,
  f_w02_seq, f_w02_subtype,
  f_mp_chainkey, f_mp_pad0, f_mp_disc, f_mp_total, f_mp_fragment,
  f_ze_mapid, f_ze_status,
  f_we_mapid, f_we_id, f_we_intensity, f_we_duration,
  f_wi_const, f_wi_objid, f_wi_pad1, f_wi_instref, f_wi_typeid,
  f_wi_y, f_wi_z, f_wi_x, f_wi_subsec, f_wi_combat, f_wi_script, f_wi_model,
  f_g1f_op, f_g1f_name,
  f_pu_delta, f_pu_type, f_pu_max,
  f_ps_hp, f_ps_psi, f_ps_sta, f_ps_maxhp1, f_ps_maxhp2,
  f_g25_op, f_g25_name,
  f_mv_x, f_mv_y, f_mv_z, f_mv_yaw, f_mv_raw,
}

-- Expert info
local ef_malformed = ProtoExpert.new("nc2_udp.malformed", "Malformed sub-packet",
  expert.group.MALFORMED, expert.severity.ERROR)
local ef_decrypt_fail = ProtoExpert.new("nc2_udp.decrypt_fail", "Decryption failed",
  expert.group.DECRYPTION, expert.severity.WARN)
nc2_udp.experts = { ef_malformed, ef_decrypt_fail }

-- ============================================================
-- LFSR-CFB cipher (port of WireEncrypt.java)
-- ============================================================

-- 16-bit LFSR PRNG. Returns the generated key byte and updates state.
-- state is a 1-element table {state[1]=u16}.
local function lfsr_byte(state, input)
  local s = bit.band(state[1], 0xffff)
  local out = 0
  for b = 0, 7 do
    local hi = bit.band(bit.rshift(s, 8), 0xff)
    local lo = bit.band(s, 0xff)
    local data_bit = bit.band(bit.rshift(input, b), 1)
    local fb = bit.band(
      bit.bxor(bit.rshift(hi, 6), bit.rshift(hi, 5), bit.rshift(hi, 3), lo, data_bit),
      1)
    s = bit.band(bit.bor(bit.lshift(s, 1), fb), 0xffff)
    out = bit.bor(out, bit.lshift(fb, 7 - b))
  end
  state[1] = s
  return bit.band(out, 0xff)
end

-- Decrypt a wire packet. Returns a Lua array of plaintext bytes (1-indexed)
-- and the recovered seed/length, or nil on failure.
local function lfsr_decrypt(tvb)
  local n = tvb:len()
  if n < 4 then return nil end
  local b0 = tvb(0, 1):uint()
  local b1 = tvb(1, 1):uint()
  local b2 = tvb(2, 1):uint()
  local b3 = tvb(3, 1):uint()

  local seed = bit.bor(b0, bit.lshift(b1, 8))
  local state = { seed }

  local key = lfsr_byte(state, b1)
  local lo  = bit.band(bit.bxor(key, b2), 0xff)
  key = lfsr_byte(state, b2)
  local hi  = bit.band(bit.bxor(key, b3), 0xff)
  local data_len = bit.bor(bit.lshift(hi, 8), lo)

  if data_len < 0 or data_len > n - 4 then return nil end

  local plain = {}
  local prev = b3
  for i = 0, data_len - 1 do
    local cb = tvb(4 + i, 1):uint()
    key = lfsr_byte(state, prev)
    plain[i + 1] = bit.band(bit.bxor(key, cb), 0xff)
    prev = cb
  end
  return plain, seed, data_len
end

-- ============================================================
-- Decoded-byte access helpers
-- ============================================================

local function dec_u8(dec, off) return dec[off + 1] end
local function dec_u16le(dec, off)
  return bit.bor(dec[off + 1], bit.lshift(dec[off + 2], 8))
end
local function dec_u32le(dec, off)
  return dec[off + 1]
    + dec[off + 2] * 0x100
    + dec[off + 3] * 0x10000
    + dec[off + 4] * 0x1000000
end
local function dec_i32le(dec, off)
  local v = dec_u32le(dec, off)
  if v >= 0x80000000 then v = v - 0x100000000 end
  return v
end

-- Read a NUL-terminated string from a decoded byte array. Returns
-- (string, byte_count_including_nul). Bounded by max.
local function dec_cstring(dec, off, max)
  local i = 0
  while i < max do
    if dec[off + 1 + i] == 0 then
      local s = ""
      for j = 0, i - 1 do
        s = s .. string.char(dec[off + 1 + j])
      end
      return s, i + 1
    end
    i = i + 1
  end
  local s = ""
  for j = 0, max - 1 do
    s = s .. string.char(dec[off + 1 + j])
  end
  return s, max
end

-- Render decoded plaintext bytes as a single hex-string for display.
-- We deliberately avoid `ByteArray:tvb()` here: synthetic Tvbs handed to
-- `tree:add()` have triggered crashes on some Wireshark 4.x builds.
local function dec_to_hex(dec, max)
  local n = math.min(#dec, max or #dec)
  local out = {}
  for i = 1, n do out[i] = string.format("%02x", dec[i]) end
  if n < #dec then out[n + 1] = "..." end
  return table.concat(out, " ")
end

-- For tree:add ranges we still want to point at the original (encrypted)
-- tvb so that selecting a field highlights the right wire bytes. We build a
-- "view" object that exposes plaintext-byte values keyed by plaintext
-- offset, and a function that maps plaintext offset -> encrypted-tvb range.
local function make_view(tvb, dec, plain_base_in_wire)
  -- plain_base_in_wire = 4 when decrypting (skip cipher header)
  -- plain_base_in_wire = 0 when the buffer is plaintext already.
  return {
    bytes = dec,
    range = function(self, off, len)
      return tvb(plain_base_in_wire + off, len)
    end,
    len = function(self)
      return #self.bytes
    end,
    u8 = function(self, off) return dec_u8(self.bytes, off) end,
    u16le = function(self, off) return dec_u16le(self.bytes, off) end,
    u32le = function(self, off) return dec_u32le(self.bytes, off) end,
    i32le = function(self, off) return dec_i32le(self.bytes, off) end,
    cstring = function(self, off, max) return dec_cstring(self.bytes, off, max) end,
  }
end

-- Build a "view" from an already-plaintext tvb (no cipher).
local function make_plain_view(tvb)
  local dec = {}
  for i = 0, tvb:len() - 1 do dec[i + 1] = tvb(i, 1):uint() end
  return make_view(tvb, dec, 0)
end

-- ============================================================
-- TCP dissectors
-- ============================================================

-- Read a NUL-terminated string from a tvb. Returns (string, length_including_nul).
local function tvb_cstring(tvb, off, max)
  local i = 0
  while i < max do
    if tvb(off + i, 1):uint() == 0 then
      return tvb(off, i):string(), i + 1
    end
    i = i + 1
  end
  return tvb(off, max):string(), max
end

local function diss_tcp_auth(tvb, tree)
  -- 0x84 0x80 | key(1) | unknown(30) | userlen(u16 LE) | passlen(u16 LE) | user | pass
  if tvb:len() < 37 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add(f_auth_key, tvb(2, 1))
  tree:add_le(f_auth_userlen, tvb(33, 2))
  tree:add_le(f_auth_passlen, tvb(35, 2))
  local userlen = tvb(33, 2):le_uint()
  local passlen = tvb(35, 2):le_uint()
  if userlen > 0 and 37 + userlen <= tvb:len() then
    local s = tvb_cstring(tvb, 37, userlen)
    tree:add(f_auth_user, tvb(37, userlen), s)
  end
  local pass_off = 37 + userlen
  if passlen > 0 and pass_off + passlen <= tvb:len() then
    tree:add(f_auth_passblob, tvb(pass_off, passlen))
  end
end

local function diss_tcp_authb(tvb, tree)
  -- 0x83 0x01 | unk(4) | clport(4) | key(1) | unk(7) | spot(u32 LE)
  --        | passlen(u16 LE) | userlen(u16 LE) | user | pass
  if tvb:len() < 26 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_auth_clport, tvb(6, 4))
  tree:add(f_auth_key, tvb(10, 1))
  tree:add_le(f_auth_spot, tvb(18, 4))
  tree:add_le(f_auth_passlen, tvb(22, 2))
  tree:add_le(f_auth_userlen, tvb(24, 2))
  local userlen = tvb(24, 2):le_uint()
  local passlen = tvb(22, 2):le_uint()
  if userlen > 0 and 26 + userlen <= tvb:len() then
    local s = tvb_cstring(tvb, 26, userlen)
    tree:add(f_auth_user, tvb(26, userlen), s)
  end
  local pass_off = 26 + userlen
  if passlen > 0 and pass_off + passlen <= tvb:len() then
    tree:add(f_auth_passblob, tvb(pass_off, passlen))
  end
end

local function diss_tcp_authack(tvb, tree)
  -- 0x83 0x81 | account(u32 LE) | session(8 bytes)
  if tvb:len() < 14 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_aa_account, tvb(2, 4))
  tree:add(f_aa_session, tvb(6, 8))
end

local function diss_tcp_udpserverdata(tvb, tree)
  -- 0x83 0x05 | acct(u32 LE) | char(u32 LE) | ip(4) | port(u16 LE)
  --        | flags(u32 LE) | sid(8 bytes, transformed 127-x)
  if tvb:len() < 28 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_usd_account, tvb(2, 4))
  tree:add_le(f_usd_char, tvb(6, 4))
  tree:add(f_usd_ip, tvb(10, 4))
  tree:add_le(f_usd_port, tvb(14, 2))
  tree:add_le(f_usd_flags, tvb(16, 4))
  tree:add(f_usd_sid, tvb(20, 8))
  local hex = {}
  for i = 0, 7 do
    hex[i + 1] = string.format("%02x", bit.band(127 - tvb(20 + i, 1):uint(), 0xff))
  end
  tree:add(f_usd_sid_orig, tvb(20, 8), table.concat(hex, " "))
end

local function diss_tcp_location(tvb, tree)
  -- 0x83 0x0c | id(u32 LE) | unk1(u32 LE) | unk2(u32 LE) | world(cstring)
  if tvb:len() < 14 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_loc_id, tvb(2, 4))
  tree:add_le(f_loc_unk1, tvb(6, 4))
  tree:add_le(f_loc_unk2, tvb(10, 4))
  if tvb:len() > 14 then
    local name = tvb_cstring(tvb, 14, tvb:len() - 14)
    tree:add(f_loc_world, tvb(14, tvb:len() - 14), name)
  end
end

local function diss_tcp_charlist(tvb, tree)
  -- 0x83 0x85 | 00 00 | count(u16 LE) | structsize(u16 LE) | [count * struct]
  if tvb:len() < 8 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_cl_count, tvb(4, 2))
  tree:add_le(f_cl_structsize, tvb(6, 2))
  local count = tvb(4, 2):le_uint()
  local ssize = tvb(6, 2):le_uint()
  local off = 8
  for i = 0, count - 1 do
    if off + ssize > tvb:len() then break end
    local sub = tree:add(tvb(off, ssize), string.format("Slot %d", i))
    sub:add_le(f_cl_char_id, tvb(off, 4))
    -- Name lives at the tail of the struct, NUL-terminated. Layout from
    -- server_tcp/CharList.java: fixed prelude of 41 bytes, then name, NUL.
    if ssize > 41 then
      local name_off = off + 41
      local max = math.min(ssize - 41, tvb:len() - name_off)
      local s, n = tvb_cstring(tvb, name_off, max)
      if n > 0 then sub:add(f_cl_char_name, tvb(name_off, n), s) end
    end
    off = off + ssize
  end
end

local function diss_tcp_kicked(tvb, tree)
  if tvb:len() < 4 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_kick_reason, tvb(2, 2))
  if tvb:len() > 4 then
    local s = tvb_cstring(tvb, 4, tvb:len() - 4)
    tree:add(f_kick_text, tvb(4, tvb:len() - 4), s)
  end
end

local function diss_tcp_chat(tvb, tree)
  tree:add(f_tcp_opcode, tvb(0, 2))
  if tvb:len() > 2 then
    local s = tvb_cstring(tvb, 2, tvb:len() - 2)
    tree:add(f_chat_text, tvb(2, tvb:len() - 2), s)
  end
end

local TCP_DISPATCH = {
  [0x8480] = diss_tcp_auth,
  [0x8301] = diss_tcp_authb,
  [0x8381] = diss_tcp_authack,
  [0x8305] = diss_tcp_udpserverdata,
  [0x830c] = diss_tcp_location,
  [0x8385] = diss_tcp_charlist,
  [0x8303] = diss_tcp_kicked,
  [0x8317] = diss_tcp_chat,
}

-- ============================================================
-- UDP sub-packet dissectors
-- ============================================================

-- Reusable: render a NUL-terminated string from a view, advancing the
-- caller's offset cursor. Returns the new cursor and the string.
local function add_view_cstring(tree, view, field, off)
  local rem = view:len() - off
  if rem <= 0 then return off, "" end
  local s, n = view:cstring(off, rem)
  tree:add(field, view:range(off, n), s)
  return off + n, s
end

local function diss_zoning_end(view, tree)
  -- inner already at sub-type 0x08. View covers the bytes starting at the
  -- 0x03 reliable header? In our caller we pass the view starting at the
  -- outer 0x13 frame to keep field offsets consistent with PROTOCOL.md.
  -- ZoningEnd documented inside-frame layout (offsets relative to view):
  --   0x09=0x08, 0x0A=mapid LE16, 0x0C=status u8.
  if view:len() < 13 then return end
  tree:add_le(f_ze_mapid, view:range(0x0A, 2), view:u16le(0x0A))
  tree:add(f_ze_status, view:range(0x0C, 1), view:u8(0x0C))
end

local function diss_weather(view, tree)
  -- 0x09=0x2e, 0x0A=mapid u16, 0x0C=id u8, 0x0D=intensity u8, 0x0E=duration u32.
  if view:len() < 0x12 then return end
  tree:add_le(f_we_mapid,     view:range(0x0A, 2), view:u16le(0x0A))
  tree:add(   f_we_id,        view:range(0x0C, 1), view:u8(0x0C))
  tree:add(   f_we_intensity, view:range(0x0D, 1), view:u8(0x0D))
  tree:add_le(f_we_duration,  view:range(0x0E, 4), view:u32le(0x0E))
end

-- WorldInfo (0x03 -> 0x28) inner layout (offsets relative to inner payload
-- which starts at the byte AFTER the 0x28 sub-type byte).
local function diss_worldinfo_inner(view, base, tree)
  -- view is the sub-packet view, base is the offset of the inner payload.
  -- inner[0..1]   = 0x00 0x01 const
  -- inner[2..3]   = world object id LE16
  -- inner[4..5]   = pad
  -- inner[6..9]   = world instance ref LE32
  -- inner[10..11] = NPC type id LE16
  -- inner[12..13] = Y LE16
  -- inner[14..15] = Z LE16
  -- inner[16..17] = X LE16
  -- inner[18]     = pad
  -- inner[19]     = variable
  -- inner[20]     = zone sub-sector
  -- inner[21..23] = pad
  -- inner[24..28] = stats (5B)
  -- inner[29]     = combat class
  -- inner[30..34] = pad (5B zero)
  -- inner[35..]   = script_name\0 model_name\0
  local need = base + 36
  if view:len() < need then return end
  tree:add_le(f_wi_const,   view:range(base + 0,  2), view:u16le(base + 0))
  tree:add_le(f_wi_objid,   view:range(base + 2,  2), view:u16le(base + 2))
  tree:add_le(f_wi_pad1,    view:range(base + 4,  2), view:u16le(base + 4))
  tree:add_le(f_wi_instref, view:range(base + 6,  4), view:u32le(base + 6))
  tree:add_le(f_wi_typeid,  view:range(base + 10, 2), view:u16le(base + 10))
  tree:add_le(f_wi_y,       view:range(base + 12, 2), view:u16le(base + 12))
  tree:add_le(f_wi_z,       view:range(base + 14, 2), view:u16le(base + 14))
  tree:add_le(f_wi_x,       view:range(base + 16, 2), view:u16le(base + 16))
  tree:add(   f_wi_subsec,  view:range(base + 20, 1), view:u8(base + 20))
  tree:add(   f_wi_combat,  view:range(base + 29, 1), view:u8(base + 29))
  local cur = base + 35
  cur = add_view_cstring(tree, view, f_wi_script, cur)
  add_view_cstring(tree, view, f_wi_model, cur)
end

-- Multipart (0x03 -> 0x07) per-fragment header.
-- After the 0x07 sub-type byte, fragments carry:
--   [chain_key 1B][0x00 1B][discriminator 1B][total_size LE4][fragment data...]
local function diss_multipart(view, base, tree)
  if view:len() < base + 7 then return end
  tree:add(   f_mp_chainkey, view:range(base + 0, 1), view:u8(base + 0))
  tree:add(   f_mp_pad0,     view:range(base + 1, 1), view:u8(base + 1))
  tree:add(   f_mp_disc,     view:range(base + 2, 1), view:u8(base + 2))
  tree:add_le(f_mp_total,    view:range(base + 3, 4), view:u32le(base + 3))
  local frag_off = base + 7
  local frag_len = view:len() - frag_off
  if frag_len > 0 then
    tree:add(f_mp_fragment, view:range(frag_off, frag_len))
  end
end

-- 0x1f -> 0x30 PoolStatusBroadcast (14 B from 0x1f wrapper start)
-- Layout: 1f 01 00 30 [HP LE2] [PSI LE2] [STA LE2] [maxHP LE2] [maxHP LE2]
local function diss_pool_status(view, base, tree)
  if view:len() < base + 14 then return end
  tree:add_le(f_ps_hp,     view:range(base + 4,  2), view:u16le(base + 4))
  tree:add_le(f_ps_psi,    view:range(base + 6,  2), view:u16le(base + 6))
  tree:add_le(f_ps_sta,    view:range(base + 8,  2), view:u16le(base + 8))
  tree:add_le(f_ps_maxhp1, view:range(base + 10, 2), view:u16le(base + 10))
  tree:add_le(f_ps_maxhp2, view:range(base + 12, 2), view:u16le(base + 12))
end

-- 0x1f -> 0x50 PoolUpdate (16 B)
-- Layout: 1f 01 00 50 [delta LE4 signed] 00 00 00 [pool_type] [max LE2] 00 00
local function diss_pool_update(view, base, tree)
  if view:len() < base + 16 then return end
  tree:add_le(f_pu_delta, view:range(base + 4,  4), view:i32le(base + 4))
  tree:add(   f_pu_type,  view:range(base + 11, 1), view:u8(base + 11))
  tree:add_le(f_pu_max,   view:range(base + 12, 2), view:u16le(base + 12))
end

-- Recognise 0x1f game ops and break out the more interesting sub-types.
-- The 0x1f sub-packet (within a 0x13 chain) is laid out like:
--   [0x1f] [seq u16 LE] [op u8] [...]
-- For 0x30/0x50 pool packets the layout is just [0x1f][01 00][op][...].
local function diss_1f(view, tree)
  if view:len() < 4 then return end
  local op = view:u8(3)
  tree:add(   f_g1f_op,   view:range(3, 1), op)
  tree:add(   f_g1f_name, view:range(3, 1), UDP_1F_OPCODES[op] or "Unknown")
  if op == 0x30 then
    diss_pool_status(view, 0, tree)
  elseif op == 0x50 then
    diss_pool_update(view, 0, tree)
  end
end

local function diss_25(view, tree)
  if view:len() < 4 then return end
  local op = view:u8(3)
  tree:add(f_g25_op,   view:range(3, 1), op)
  tree:add(f_g25_name, view:range(3, 1), UDP_25_OPCODES[op] or "Unknown")
end

-- 0x20 movement: best-effort float field display.
local function diss_movement(view, tree)
  -- The exact movement layout varies; render the payload as bytes,
  -- and if there's enough room, show three floats.
  local n = view:len()
  if n >= 1 + 12 then
    local function readf32_le(off)
      -- Reconstruct an LE32 and reinterpret as float via Tvb wrapper.
      -- Simpler: just expose the raw bytes; precise float decode is
      -- left to manual inspection.
    end
  end
  if n > 1 then
    tree:add(f_mv_raw, view:range(1, n - 1))
  end
end

-- Dispatch a 0x03 reliable wrapper. View covers bytes starting at the
-- 0x03 sub-type byte. Layout: [0x03][seq LE16][sub-type][...]
local function diss_reliable(view, sub_tree)
  if view:len() < 4 then return end
  sub_tree:add_le(f_rel_seq, view:range(1, 2), view:u16le(1))
  local rsub = view:u8(3)
  sub_tree:add(   f_rel_subtype, view:range(3, 1), rsub)
  sub_tree:add(   f_rel_subname, view:range(3, 1), UDP_RELIABLE_SUBTYPES[rsub] or "Unknown")
  sub_tree:append_text(string.format(" -> 0x%02x %s",
    rsub, UDP_RELIABLE_SUBTYPES[rsub] or "Unknown"))

  -- Offsets into `view` for the documented inner layouts. The PROTOCOL.md
  -- uses absolute offsets that include the outer 0x13 + counters; here
  -- the sub-packet view starts at the 0x03 byte, so the inner sub-type
  -- byte is at offset 3, and inner payload starts at offset 4.
  if rsub == 0x07 then
    diss_multipart(view, 4, sub_tree)
  elseif rsub == 0x08 then
    -- ZoningEnd: in PROTOCOL.md the documented layout uses outer offsets;
    -- relative to the sub-view: 0x03=type, 0x04=mapid LE2, 0x06=status u8.
    if view:len() >= 7 then
      sub_tree:add_le(f_ze_mapid,  view:range(4, 2), view:u16le(4))
      sub_tree:add(   f_ze_status, view:range(6, 1), view:u8(6))
    end
  elseif rsub == 0x2e then
    -- Weather: 0x03=type, 0x04=mapid LE2, 0x06=id u8, 0x07=intensity u8, 0x08=duration LE4.
    if view:len() >= 12 then
      sub_tree:add_le(f_we_mapid,     view:range(4, 2), view:u16le(4))
      sub_tree:add(   f_we_id,        view:range(6, 1), view:u8(6))
      sub_tree:add(   f_we_intensity, view:range(7, 1), view:u8(7))
      sub_tree:add_le(f_we_duration,  view:range(8, 4), view:u32le(8))
    end
  elseif rsub == 0x28 then
    diss_worldinfo_inner(view, 4, sub_tree)
  end
end

-- 0x02 wrapper: similar shape to 0x03 reliable.
local function diss_w02(view, sub_tree)
  if view:len() < 4 then return end
  sub_tree:add_le(f_w02_seq,     view:range(1, 2), view:u16le(1))
  local rsub = view:u8(3)
  sub_tree:add(   f_w02_subtype, view:range(3, 1), rsub)
  sub_tree:append_text(string.format(" -> 0x%02x %s",
    rsub, UDP_RELIABLE_SUBTYPES[rsub] or "Unknown"))
end

-- ============================================================
-- UDP top-level dispatchers
-- ============================================================

local function diss_udp_alive(view, tree)
  if view:len() < 7 then return end
  tree:add(f_al_mapid, view:range(1, 1), view:u8(1))
  tree:add(f_al_iface, view:range(2, 1), view:u8(2))
  local sk = view:u16le(3)
  if sk >= 0x8000 then sk = sk - 0x10000 end
  tree:add(f_al_negkey, view:range(3, 2), sk)
  tree:add(f_al_port,   view:range(5, 2), view:u16le(5))
end

local function diss_udp_handshake(view, tree)
  if view:len() < 14 then return end
  tree:add(f_hs_session, view:range(1, 8))
  tree:add(f_hs_iface,   view:range(9, 1), view:u8(9))
end

-- Walk the 0x13 sub-packet chain. Format (retail/current Ceres-J):
--   0x13 | counter LE16 | counter+sessionkey LE16 | { sub_len LE16, data }*
-- The chain ends when sub_len == 0 or buffer is exhausted.
local function diss_udp_gamedata(view, tree)
  if view:len() < 5 then return end
  tree:add_le(f_gd_counter1, view:range(1, 2), view:u16le(1))
  tree:add_le(f_gd_counter2, view:range(3, 2), view:u16le(3))

  local off = 5
  local idx = 0
  while off + 2 <= view:len() do
    local sub_len = view:u16le(off)
    if sub_len == 0 then break end
    if off + 2 + sub_len > view:len() then
      tree:add_proto_expert_info(ef_malformed,
        "Sub-packet length overruns frame at offset " .. off)
      break
    end

    local sub_op = view:u8(off + 2)
    local sub_name = UDP_SUB_OPCODES[sub_op] or string.format("Unknown 0x%02x", sub_op)
    local sub_tree = tree:add(view:range(off, 2 + sub_len),
      string.format("Sub-packet #%d (%s, %d bytes)", idx, sub_name, sub_len))
    sub_tree:add_le(f_gd_sub_len,  view:range(off, 2), sub_len)
    sub_tree:add(   f_gd_sub_op,   view:range(off + 2, 1), sub_op)
    sub_tree:add(   f_gd_sub_name, view:range(off + 2, 1), sub_name)

    -- View into just this sub-packet's bytes (starting at the sub-op byte,
    -- so offset 0 = sub_op).
    local sub_view = {
      bytes = {},
      _parent = view,
      _base = off + 2,
      _len = sub_len,
      len = function(self) return self._len end,
      range = function(self, o, l) return self._parent:range(self._base + o, l) end,
      u8 = function(self, o) return self._parent:u8(self._base + o) end,
      u16le = function(self, o) return self._parent:u16le(self._base + o) end,
      u32le = function(self, o) return self._parent:u32le(self._base + o) end,
      i32le = function(self, o) return self._parent:i32le(self._base + o) end,
      cstring = function(self, o, m)
        return self._parent:cstring(self._base + o, math.min(m, self._len - o))
      end,
    }

    if sub_op == 0x03 then
      diss_reliable(sub_view, sub_tree)
    elseif sub_op == 0x02 then
      diss_w02(sub_view, sub_tree)
    elseif sub_op == 0x1f then
      diss_1f(sub_view, sub_tree)
    elseif sub_op == 0x25 then
      diss_25(sub_view, sub_tree)
    elseif sub_op == 0x20 then
      diss_movement(sub_view, sub_tree)
    end

    off = off + 2 + sub_len
    idx = idx + 1
  end
end

-- ============================================================
-- TCP dissector entry
-- ============================================================

local function tcp_get_pdu_length(tvb, pinfo, offset)
  if tvb:len() - offset < 3 then
    return DESEGMENT_ONE_MORE_SEGMENT
  end
  if tvb(offset, 1):uint() ~= 0xfe then
    return 1
  end
  return 3 + tvb(offset + 1, 2):le_uint()
end

local function tcp_dissect_pdu(tvb, pinfo, tree)
  pinfo.cols.protocol = "NC2 TCP"
  local root = tree:add(nc2_tcp, tvb(), "Neocron 2 TCP")
  root:add(f_tcp_magic, tvb(0, 1))
  root:add_le(f_tcp_len, tvb(1, 2))

  local payload_len = tvb(1, 2):le_uint()
  if payload_len == 0 or tvb:len() < 3 + payload_len then return end
  local payload = tvb(3, payload_len)
  if payload:len() < 2 then
    root:add(f_tcp_raw, payload)
    pinfo.cols.info = "NC2 TCP (short)"
    return
  end

  local b0 = payload(0, 1):uint()
  local b1 = payload(1, 1):uint()
  local op = bit.bor(bit.lshift(b0, 8), b1)
  local opname = TCP_OPCODES[op] or string.format("Unknown 0x%04x", op)
  root:add(f_tcp_opname, payload(0, 2), opname)

  if op == 0x8482 and payload:len() >= 3 then
    local sub = payload(2, 1):uint()
    local subname = TCP_CHAROP[sub] or string.format("sub 0x%02x", sub)
    opname = opname .. " / " .. subname
  end

  local parser = TCP_DISPATCH[op]
  if parser then
    parser(payload, root)
  else
    root:add(f_tcp_opcode, payload(0, 2))
    if payload:len() > 2 then root:add(f_tcp_raw, payload(2, payload:len() - 2)) end
  end
  pinfo.cols.info = opname
end

function nc2_tcp.dissector(tvb, pinfo, tree)
  dissect_tcp_pdus(tvb, tree, 3, tcp_get_pdu_length, tcp_dissect_pdu, true, pinfo)
end

-- ============================================================
-- UDP dissector entry
-- ============================================================

function nc2_udp.dissector(tvb, pinfo, tree)
  pinfo.cols.protocol = "NC2 UDP"
  local root = tree:add(nc2_udp, tvb(), "Neocron 2 UDP")
  if tvb:len() < 1 then return end

  local sport = pinfo.src_port
  local dport = pinfo.dst_port
  local server_is_dst = (dport >= 5000 and dport <= 5999)
  local server_is_src = (sport >= 5000 and sport <= 5999)
  local dir
  if server_is_dst and not server_is_src then dir = "C->S"
  elseif server_is_src and not server_is_dst then dir = "S->C"
  else dir = "?" end
  root:add(f_udp_dir, tvb(0, 1), dir)

  -- Try LFSR-CFB decryption. Modern NCE 2.5 encrypts both directions; if
  -- the recovered plaintext byte 0 isn't a known opcode, fall back to
  -- treating the wire as plaintext (legacy / pre-cipher captures).
  local view
  local encrypted = false
  if tvb:len() >= 4 then
    local plain, seed, enc_len = lfsr_decrypt(tvb)
    if plain and KNOWN_HEADER_SET[plain[1]] then
      encrypted = true
      root:add(f_udp_enc, tvb(0, 1), true)
      root:add_le(f_udp_seed, tvb(0, 2), seed)
      root:add_le(f_udp_enc_len, tvb(2, 2), enc_len)
      view = make_view(tvb, plain, 4)
      -- Decoded plaintext as a hex string (cap at 256 bytes to keep the
      -- UI snappy for large gamedata frames).
      root:add(f_udp_decoded, tvb(4, tvb:len() - 4), dec_to_hex(plain, 256))
    end
  end
  if not view then
    -- Plaintext fall-through. If even byte 0 isn't a known opcode, label
    -- it as unknown but still render the bytes.
    root:add(f_udp_enc, tvb(0, 1), false)
    if not KNOWN_HEADER_SET[tvb(0, 1):uint()] then
      root:add_proto_expert_info(ef_decrypt_fail,
        "Decryption failed and byte 0 is not a known plaintext opcode")
    end
    view = make_plain_view(tvb)
  end

  local op = view:u8(0)
  root:add(f_udp_opcode, view:range(0, 1), op)
  local opname = UDP_TOP_OPCODES[op] or string.format("Unknown 0x%02x", op)
  root:add(f_udp_opname, view:range(0, 1), opname)

  if op == 0x01 then
    diss_udp_handshake(view, root)
  elseif op == 0x04 then
    diss_udp_alive(view, root)
  elseif op == 0x13 then
    diss_udp_gamedata(view, root)
  elseif op == 0x03 or op == 0x08 then
    if view:len() > 1 then root:add(f_udp_raw, view:range(1, view:len() - 1)) end
  else
    if view:len() > 0 then root:add(f_udp_raw, view:range(0, view:len())) end
  end

  pinfo.cols.info = string.format("%s %s%s (%d B%s)",
    dir, opname,
    (op == 0x13 and view:len() >= 5)
      and string.format(" cnt=%d", view:u16le(1)) or "",
    tvb:len(),
    encrypted and ", enc" or "")
end

-- ============================================================
-- Registration
-- ============================================================

do
  local tcp_table = DissectorTable.get("tcp.port")
  tcp_table:add(12000, nc2_tcp)
  tcp_table:add(7000, nc2_tcp)  -- info server (same FE framing)

  local udp_table = DissectorTable.get("udp.port")
  for p = 5000, 5999 do
    udp_table:add(p, nc2_udp)
  end
end
