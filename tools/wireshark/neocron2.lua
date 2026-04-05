--[[
  Neocron 2 Wireshark Lua dissector (Ceres-J / retail)

  Install:
    Linux:    ~/.local/lib/wireshark/plugins/neocron2.lua
    macOS:    ~/.config/wireshark/plugins/neocron2.lua
    Windows:  %APPDATA%\Wireshark\plugins\neocron2.lua
  Then Analyze → Reload Lua Plugins, or restart Wireshark.

  Covers:
    - TCP 12000 (gameserver): fe <uint16 le len> <payload> framing,
      full opcode dispatch matching GamePacketReaderTCP.java
    - UDP 5000-5999: per-session game ports. C->S decrypts the per-packet
      XOR cipher (PacketObfuscator.java). S->C is plaintext (retail's
      ObfuscateStreamBuf class is not used for wire traffic, verified
      against neocronclient.exe DAT_00b05360 = 0x3039).
    - 0x13 gamedata sub-packet chain (PacketBuilderUDP13.java)
    - Field-level parsing for high-traffic packets: Auth, AuthB,
      Location, UDPServerData, CharList, UDPAlive, UpdateModel,
      CharInfo (chunk headers), Movement, PositionUpdate, TimeSync.

  Not covered:
    - CharInfo multi-chunk reassembly across datagrams (v2)
    - Retail S->C stream cipher (doesn't exist)
    - Editable packet fields
]]

local bit = require("bit")

-- ========================================================================
-- Proto declarations
-- ========================================================================

local nc2_tcp = Proto("nc2_tcp", "Neocron 2 TCP")
local nc2_udp = Proto("nc2_udp", "Neocron 2 UDP")

-- ========================================================================
-- Opcode name tables
-- ========================================================================

-- TCP opcodes. Key = (byte0 << 8) | byte1 for two-byte opcodes,
-- or byte0 for single-byte. We prefer the 2-byte form when known.
local TCP_OPCODES = {
  -- Handshake family (0x80)
  [0x8000] = "HandshakeB (C->S)",            -- full: 80 00 78
  [0x8001] = "HandshakeA (S->C)",            -- full: 80 01 66
  [0x8003] = "HandshakeC (S->C)",            -- full: 80 03 68

  -- Auth / session family (0x83)
  [0x8301] = "AuthB (C->S)",
  [0x8303] = "ClientKicked (S->C)",
  [0x8305] = "UDPServerData (S->C)",
  [0x830c] = "Location (S->C)",
  [0x830d] = "Sync (S->C)",
  [0x8317] = "GlobalChat (S->C)",
  [0x8381] = "AuthAck (S->C)",
  [0x8385] = "CharList (S->C)",
  [0x8386] = "RequestSuccess/Failed (S->C)",
  [0x838f] = "UnknownSuccess (S->C)",

  -- Login / char family (0x84)
  [0x8480] = "Auth (C->S)",
  [0x8482] = "CharOp (C->S)",                -- sub-opcode in next byte

  -- Game data family (0x87)
  [0x8737] = "GetGamedata (C->S)",
  [0x873a] = "Gamedata (S->C)",
  [0x873c] = "GetUDPConnection (C->S)",
}

-- 0x84 0x82 sub-opcodes
local TCP_CHAROP = {
  [0x03] = "DeleteCharacter",
  [0x05] = "CheckCharacterName",
  [0x06] = "GetCharList",
  [0x07] = "CreateCharacter",
}

-- Top-level UDP opcodes (C->S is always encrypted, S->C plaintext)
local UDP_TOP_OPCODES = {
  [0x01] = "HandshakeUDP",
  [0x03] = "SyncUDP",
  [0x04] = "UDPAlive",
  [0x08] = "AbortSession",
  [0x13] = "Gamedata (sub-packets)",
}

-- Sub-packets inside a 0x13 gamedata chain. These are the first bytes
-- of the sub-packet payload. Many sub-packets are wrapped in a further
-- "0x03 <counter_lo> <counter_hi>" reliable layer — we peel that here
-- and key on the inner byte.
local UDP_SUB_OPCODES = {
  [0x0b] = "CPing / SPing",
  [0x0c] = "GetTimeSync",
  [0x0d] = "TimeSync (S->C)",
  [0x13] = "SZoning1",
  [0x1b] = "PlayerPositionUpdate / PresentWorldID / OpenDoor",
  [0x1f] = "Chat / ZoningCmd family",
  [0x20] = "Movement / SMovement",
  [0x22] = "CharInfo / Zoning family",
  [0x23] = "PlayerNameByID",
  [0x25] = "LongPlayerInfo",
  [0x27] = "RequestInfoAboutWorldID",
  [0x28] = "WorldNPCInfo",
  [0x2a] = "RequestPositionUpdate",
  [0x2c] = "PositionUpdate",
  [0x2f] = "UpdateModel",
  [0x30] = "ShortPlayerInfo",
  [0x31] = "RequestShortPlayerInfo",
  [0x38] = "ForcedZoning",
}

-- Known plaintext first-byte values for the UDP cipher seed recovery.
local KNOWN_HEADERS = { 0x01, 0x03, 0x04, 0x08, 0x13 }

-- ========================================================================
-- Proto field declarations
-- ========================================================================

local f_tcp_magic      = ProtoField.uint8 ("nc2_tcp.magic",      "Magic",       base.HEX)
local f_tcp_len        = ProtoField.uint16("nc2_tcp.len",        "Payload length", base.DEC)
local f_tcp_opcode     = ProtoField.uint16("nc2_tcp.opcode",     "Opcode",      base.HEX, TCP_OPCODES)
local f_tcp_opname     = ProtoField.string("nc2_tcp.opname",     "Op name")
local f_tcp_raw        = ProtoField.bytes ("nc2_tcp.raw",        "Raw payload")

-- Auth / AuthB
local f_auth_key       = ProtoField.uint8 ("nc2_tcp.auth.key",   "Encryption key", base.HEX)
local f_auth_userlen   = ProtoField.uint16("nc2_tcp.auth.userlen","Username length (bytes)", base.DEC)
local f_auth_passlen   = ProtoField.uint16("nc2_tcp.auth.passlen","Password length field", base.DEC)
local f_auth_user      = ProtoField.string("nc2_tcp.auth.user",  "Username")
local f_auth_spot      = ProtoField.uint32("nc2_tcp.auth.spot",  "Character spot", base.DEC)

-- UDPServerData
local f_usd_account    = ProtoField.uint32("nc2_tcp.usd.account","Account id",  base.DEC)
local f_usd_char       = ProtoField.uint32("nc2_tcp.usd.char",   "Character id",base.DEC)
local f_usd_ip         = ProtoField.ipv4  ("nc2_tcp.usd.ip",     "Server IP")
local f_usd_port       = ProtoField.uint16("nc2_tcp.usd.port",   "Server UDP port (per session)", base.DEC)
local f_usd_flags      = ProtoField.uint32("nc2_tcp.usd.flags",  "Protocol flags", base.HEX)
local f_usd_sid_wire      = ProtoField.bytes ("nc2_tcp.usd.sid_wire",      "Session id (wire, 127-x transformed)")
local f_usd_sid_orig_text = ProtoField.string("nc2_tcp.usd.sid_orig_text", "Session id (original, 127-wire)")

-- Location
local f_loc_id         = ProtoField.uint32("nc2_tcp.loc.id",     "Location id", base.DEC)
local f_loc_field1     = ProtoField.uint32("nc2_tcp.loc.field1", "Reserved 1 (usually 0)", base.HEX)
local f_loc_field2     = ProtoField.uint32("nc2_tcp.loc.field2", "Reserved 2 (usually 0)", base.HEX)
local f_loc_name       = ProtoField.string("nc2_tcp.loc.name",   "World name")

-- CharList
local f_cl_count       = ProtoField.uint16("nc2_tcp.cl.count",   "Character count", base.DEC)
local f_cl_structsize  = ProtoField.uint16("nc2_tcp.cl.structsize","Character struct size", base.DEC)
local f_cl_char_id     = ProtoField.uint32("nc2_tcp.cl.char_id", "Character id", base.DEC)
local f_cl_char_name   = ProtoField.string("nc2_tcp.cl.char_name","Character name")

-- UDP common
local f_udp_dir        = ProtoField.string("nc2_udp.dir",        "Direction")
local f_udp_enc        = ProtoField.bool  ("nc2_udp.enc",        "Encrypted")
local f_udp_seed       = ProtoField.uint8 ("nc2_udp.seed",       "Cipher seed", base.HEX)
local f_udp_opcode     = ProtoField.uint8 ("nc2_udp.opcode",     "Opcode",       base.HEX, UDP_TOP_OPCODES)
local f_udp_opname     = ProtoField.string("nc2_udp.opname",     "Op name")
local f_udp_raw        = ProtoField.bytes ("nc2_udp.raw",        "Decoded payload")

-- UDP 0x13 gamedata outer frame
local f_gd_counter1    = ProtoField.uint16("nc2_udp.gd.counter1","Counter",         base.DEC)
local f_gd_counter2    = ProtoField.uint16("nc2_udp.gd.counter2","Counter+session", base.HEX)
local f_gd_sub_len     = ProtoField.uint8 ("nc2_udp.gd.sub_len", "Sub-packet length", base.DEC)
local f_gd_sub_op      = ProtoField.uint8 ("nc2_udp.gd.sub_op",  "Sub opcode",      base.HEX, UDP_SUB_OPCODES)
local f_gd_sub_name    = ProtoField.string("nc2_udp.gd.sub_name","Sub packet")
local f_gd_sub_raw     = ProtoField.bytes ("nc2_udp.gd.sub_raw", "Sub payload")

-- UDPAlive
local f_al_mapid       = ProtoField.uint8 ("nc2_udp.alive.mapid","Map id", base.DEC)
local f_al_iface       = ProtoField.uint8 ("nc2_udp.alive.iface","Interface id", base.DEC)
local f_al_sesskeyneg  = ProtoField.int16 ("nc2_udp.alive.sesskey","-Session key", base.DEC)
local f_al_port        = ProtoField.uint16("nc2_udp.alive.port", "Session port", base.DEC)

nc2_tcp.fields = {
  f_tcp_magic, f_tcp_len, f_tcp_opcode, f_tcp_opname, f_tcp_raw,
  f_auth_key, f_auth_userlen, f_auth_passlen, f_auth_user, f_auth_spot,
  f_usd_account, f_usd_char, f_usd_ip, f_usd_port, f_usd_flags,
  f_usd_sid_wire, f_usd_sid_orig_text,
  f_loc_id, f_loc_field1, f_loc_field2, f_loc_name,
  f_cl_count, f_cl_structsize, f_cl_char_id, f_cl_char_name,
}
nc2_udp.fields = {
  f_udp_dir, f_udp_enc, f_udp_seed, f_udp_opcode, f_udp_opname, f_udp_raw,
  f_gd_counter1, f_gd_counter2, f_gd_sub_len, f_gd_sub_op, f_gd_sub_name, f_gd_sub_raw,
  f_al_mapid, f_al_iface, f_al_sesskeyneg, f_al_port,
}

-- ========================================================================
-- Helpers
-- ========================================================================

local function tostr_hex(n, width)
  return string.format("%0" .. (width or 2) .. "x", n)
end

local function opcode_name_tcp(b0, b1)
  return TCP_OPCODES[bit.bor(bit.lshift(b0, 8), b1)] or string.format("Unknown (0x%02x 0x%02x)", b0, b1)
end

local function opcode_name_udp_top(b0)
  return UDP_TOP_OPCODES[b0] or string.format("Unknown (0x%02x)", b0)
end

local function opcode_name_udp_sub(b0)
  return UDP_SUB_OPCODES[b0] or string.format("Unknown sub (0x%02x)", b0)
end

-- Read a null-terminated C string starting at offset, returning
-- (string, consumed_bytes_including_null). Bounded by max_len.
local function read_cstring(buf, offset, max_len)
  local i = 0
  while i < max_len do
    if buf(offset + i, 1):uint() == 0 then
      return buf(offset, i):string(), i + 1
    end
    i = i + 1
  end
  return buf(offset, max_len):string(), max_len
end

-- Per-packet XOR decrypt. Returns a plain Lua table of bytes, or nil
-- if no header in KNOWN_HEADERS produces a self-consistent byte 0.
local function try_decrypt(buf)
  if buf:len() < 1 then return nil end
  local b0 = buf(0, 1):uint()
  for _, hdr in ipairs(KNOWN_HEADERS) do
    local seed = bit.band(bit.bxor(b0, hdr), 0xff)
    local out = {}
    for i = 0, buf:len() - 1 do
      local s = (i + 1) * seed
      local k1 = bit.band(bit.rshift(s, 16), 0xff)
      local k2 = bit.band(s, 0xff)
      out[i + 1] = bit.band(bit.bxor(buf(i, 1):uint(), bit.bxor(k1, k2)), 0xff)
    end
    if out[1] == hdr then
      return out, hdr, seed
    end
  end
  return nil
end

-- Lua-table-backed decrypted buffer. Supports the subset of TvbRange we
-- need: len(), :uint() on a 1-byte slice, and :bytes() for the ProtoField.
-- This avoids creating synthetic Tvb objects from ByteArrays, which
-- cause stack corruption when passed to tree:add() in Wireshark 4.x.
local function make_decoded(bytes)
  return {
    _bytes = bytes,
    len = function(self) return #self._bytes end,
    byte = function(self, off) return self._bytes[off + 1] end,
  }
end

-- ========================================================================
-- TCP packet-specific dissectors (called with the PAYLOAD after fe/len)
-- ========================================================================

local function dissect_tcp_auth(tvb, tree)
  -- 0x84 0x80 | key(1) | skip(30) | userlen(u16 LE) | passlen_field(u16 LE) | user | password(encrypted)
  if tvb:len() < 37 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add(f_auth_key, tvb(2, 1))
  -- skip 30 bytes unknown
  tree:add_le(f_auth_userlen, tvb(33, 2))
  tree:add_le(f_auth_passlen, tvb(35, 2))
  local userlen = tvb(33, 2):le_uint()
  if userlen > 0 and 37 + userlen <= tvb:len() then
    local s, _ = read_cstring(tvb, 37, userlen)
    tree:add(f_auth_user, tvb(37, userlen), s)
  end
end

local function dissect_tcp_authb(tvb, tree)
  -- 0x83 0x01 | skip(4 unknown) | skip(4 client port) | key(1) | skip(7) | spot(u32) | passlen(u16) | userlen(u16) | user...
  if tvb:len() < 26 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add(f_auth_key, tvb(10, 1))
  tree:add_le(f_auth_spot, tvb(18, 4))
  tree:add_le(f_auth_passlen, tvb(22, 2))
  tree:add_le(f_auth_userlen, tvb(24, 2))
  local userlen = tvb(24, 2):le_uint()
  if userlen > 0 and 26 + userlen <= tvb:len() then
    local s, _ = read_cstring(tvb, 26, userlen)
    tree:add(f_auth_user, tvb(26, userlen), s)
  end
end

local function dissect_tcp_udpserverdata(tvb, tree)
  -- 0x83 0x05 | acc(u32 LE) | char(u32 LE) | ip(4 bytes) | port(u16 LE) | flags(u32 LE) | sid(8 bytes transformed)
  if tvb:len() < 28 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_usd_account, tvb(2, 4))
  tree:add_le(f_usd_char, tvb(6, 4))
  tree:add(f_usd_ip, tvb(10, 4))
  tree:add_le(f_usd_port, tvb(14, 2))
  tree:add_le(f_usd_flags, tvb(16, 4))
  tree:add(f_usd_sid_wire, tvb(20, 8))
  -- Original sid = (127 - wire_byte) & 0xff. Displayed as a string
  -- summary so we don't need a synthetic ByteArray value.
  local orig_hex = {}
  for i = 0, 7 do
    orig_hex[i + 1] = string.format("%02x", bit.band(127 - tvb(20 + i, 1):uint(), 0xff))
  end
  tree:add(f_usd_sid_orig_text, tvb(20, 8), table.concat(orig_hex, " "))
end

local function dissect_tcp_location(tvb, tree)
  -- 0x83 0x0c | id(u32 LE) | reserved1(u32 LE) | reserved2(u32 LE) | name(cstring)
  if tvb:len() < 14 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_loc_id, tvb(2, 4))
  tree:add_le(f_loc_field1, tvb(6, 4))
  tree:add_le(f_loc_field2, tvb(10, 4))
  if tvb:len() > 14 then
    local name, _ = read_cstring(tvb, 14, tvb:len() - 14)
    tree:add(f_loc_name, tvb(14, tvb:len() - 14), name)
  end
end

local function dissect_tcp_charlist(tvb, tree)
  -- 0x83 0x85 | 00 00 | count(u16 LE) | structsize(u16 LE) | [count * struct]
  if tvb:len() < 8 then return end
  tree:add(f_tcp_opcode, tvb(0, 2))
  tree:add_le(f_cl_count, tvb(4, 2))
  tree:add_le(f_cl_structsize, tvb(6, 2))
  local count = tvb(4, 2):le_uint()
  local ssize = tvb(6, 2):le_uint()
  local base = 8
  for i = 0, count - 1 do
    if base + ssize > tvb:len() then break end
    local sub = tree:add(tvb(base, ssize), string.format("Character slot %d", i))
    -- First 4 bytes = char id (0xffffffff for empty slot)
    sub:add_le(f_cl_char_id, tvb(base, 4))
    -- Name is at the tail of the struct, null-terminated. We don't know the
    -- exact offset without following the full layout; best-effort: look for
    -- the trailing null and back-track.
    -- Struct layout (see server_tcp/CharList.java):
    --   id(4) head(2) torso(2) leg(2) hair(2) beard(2) tex_h(2) tex_t(2) tex_l(2) loc(4) namelen(1) 1 1 1 1 1 prof(4) 0 class/2 class%2 0(4) name... 0
    -- Name starts at offset 4+2*8+4+1+5+4+1+1+1+4 = 4+16+4+1+5+4+1+1+1+4 = 41
    if ssize >= 42 then
      local name_off = base + 41
      local name, n = read_cstring(tvb, name_off, math.min(ssize - 41, tvb:len() - name_off))
      if n > 0 then
        sub:add(f_cl_char_name, tvb(name_off, n), name)
      end
    end
    base = base + ssize
  end
end

-- Dispatch table: (byte0, byte1) -> parser. Nil falls back to raw bytes.
local TCP_DISPATCH = {
  [0x8480] = dissect_tcp_auth,
  [0x8301] = dissect_tcp_authb,
  [0x8305] = dissect_tcp_udpserverdata,
  [0x830c] = dissect_tcp_location,
  [0x8385] = dissect_tcp_charlist,
}

-- ========================================================================
-- UDP packet-specific dissectors
-- ========================================================================

-- Context-aware byte readers: if `dec` (Lua table of decrypted bytes) is
-- non-nil, read from it; otherwise read from the tvb directly. All tree
-- ranges always come from the original tvb so highlighting works.
local function ctx_u8(tvb, dec, off)
  if dec then return dec:byte(off) end
  return tvb(off, 1):uint()
end

local function ctx_u16le(tvb, dec, off)
  if dec then
    local lo = dec:byte(off)
    local hi = dec:byte(off + 1)
    return bit.bor(bit.lshift(hi, 8), lo)
  end
  return tvb(off, 2):le_uint()
end

local function dissect_udp_alive(tvb, dec, tree)
  -- 0x04 | mapid(u8) | iface(u8) | -sesskey(i16 LE) | port(u16 LE) = 7 bytes
  if tvb:len() < 7 then return end
  tree:add(f_al_mapid, tvb(1, 1), ctx_u8(tvb, dec, 1))
  tree:add(f_al_iface, tvb(2, 1), ctx_u8(tvb, dec, 2))
  local sk = ctx_u16le(tvb, dec, 3)
  if sk >= 0x8000 then sk = sk - 0x10000 end
  tree:add(f_al_sesskeyneg, tvb(3, 2), sk)
  tree:add(f_al_port, tvb(5, 2), ctx_u16le(tvb, dec, 5))
end

-- Walk the 0x13 sub-packet chain. Format:
--   0x13 | counter(u16 LE) | counter+sessionkey(u16 LE) | loop { len(u8) data(len bytes) } until len == 0
local function dissect_udp_gamedata(tvb, dec, tree)
  if tvb:len() < 5 then return end
  tree:add(f_gd_counter1, tvb(1, 2), ctx_u16le(tvb, dec, 1))
  tree:add(f_gd_counter2, tvb(3, 2), ctx_u16le(tvb, dec, 3))
  local off = 5
  local idx = 0
  while off < tvb:len() do
    local sub_len = ctx_u8(tvb, dec, off)
    if sub_len == 0 then
      break
    end
    if off + 1 + sub_len > tvb:len() then
      -- Malformed: sub length overruns packet. Label and stop.
      tree:add(tvb(off, tvb:len() - off), "Malformed sub-packet (length overruns frame)")
      break
    end
    local sub_tree = tree:add(tvb(off, 1 + sub_len), string.format("Sub-packet #%d", idx))
    sub_tree:add(f_gd_sub_len, tvb(off, 1), sub_len)
    local sub_op = ctx_u8(tvb, dec, off + 1)
    sub_tree:add(f_gd_sub_op, tvb(off + 1, 1), sub_op)
    sub_tree:add(f_gd_sub_name, tvb(off + 1, 1), opcode_name_udp_sub(sub_op))
    -- Peel the "reliable" 0x03 wrapper: if sub starts with 0x03 followed by
    -- 2 bytes of counter, the real payload opcode is at offset +3.
    if sub_op == 0x03 and sub_len >= 4 then
      local inner_op = ctx_u8(tvb, dec, off + 4)
      sub_tree:append_text(string.format(" [reliable -> 0x%02x %s]", inner_op, opcode_name_udp_sub(inner_op)))
    end
    off = off + 1 + sub_len
    idx = idx + 1
  end
end

-- ========================================================================
-- TCP dissector: use dissect_tcp_pdus() to handle segmented frames.
-- ========================================================================

local function nc2_tcp_get_pdu_length(tvb, pinfo, offset)
  if tvb:len() - offset < 3 then
    return DESEGMENT_ONE_MORE_SEGMENT
  end
  if tvb(offset, 1):uint() ~= 0xfe then
    -- Not our framing, give up on this PDU (caller will try next byte)
    return 1
  end
  local payload_len = tvb(offset + 1, 2):le_uint()
  return 3 + payload_len
end

local function nc2_tcp_dissect_pdu(tvb, pinfo, tree)
  pinfo.cols.protocol = "NC2 TCP"
  local root = tree:add(nc2_tcp, tvb(), "Neocron 2 TCP")
  root:add(f_tcp_magic, tvb(0, 1))
  root:add_le(f_tcp_len, tvb(1, 2))

  local payload_len = tvb(1, 2):le_uint()
  if payload_len == 0 or tvb:len() < 3 + payload_len then
    return
  end
  local payload = tvb(3, payload_len)

  if payload:len() < 2 then
    root:add(f_tcp_raw, payload)
    pinfo.cols.info = "NC2 TCP (short)"
    return
  end

  local b0 = payload(0, 1):uint()
  local b1 = payload(1, 1):uint()
  local op2 = bit.bor(bit.lshift(b0, 8), b1)
  local opname = opcode_name_tcp(b0, b1)

  root:add(f_tcp_opname, payload(0, 2), opname)

  -- Extra context for CharOp sub-opcode
  if op2 == 0x8482 and payload:len() >= 3 then
    local sub = payload(2, 1):uint()
    local subname = TCP_CHAROP[sub] or string.format("sub 0x%02x", sub)
    opname = opname .. " / " .. subname
  end

  local parser = TCP_DISPATCH[op2]
  if parser then
    parser(payload, root)
  else
    root:add(f_tcp_opcode, payload(0, 2))
    if payload:len() > 2 then
      root:add(f_tcp_raw, payload(2, payload:len() - 2))
    end
  end

  pinfo.cols.info = opname
end

function nc2_tcp.dissector(tvb, pinfo, tree)
  dissect_tcp_pdus(tvb, tree, 3, nc2_tcp_get_pdu_length, nc2_tcp_dissect_pdu, true, pinfo)
end

-- ========================================================================
-- UDP dissector
-- ========================================================================

function nc2_udp.dissector(tvb, pinfo, tree)
  pinfo.cols.protocol = "NC2 UDP"
  local root = tree:add(nc2_udp, tvb(), "Neocron 2 UDP")
  if tvb:len() < 1 then return end

  -- Direction heuristic: our server listens on the session port range
  -- (5000-5999). A packet whose destination port is in that range is C->S;
  -- a packet whose source port is in that range is S->C.
  local dport = pinfo.dst_port
  local is_c2s = (dport >= 5000 and dport <= 5999)

  -- Decryption: C->S is always per-packet XOR encrypted in retail and in
  -- Ceres-J C->S. S->C is plaintext. For C->S, run try_decrypt and use
  -- the recovered byte table for value reads; tvb ranges stay pointed at
  -- the original (ciphertext) bytes for display highlighting.
  local dec = nil
  local used_seed = nil
  if is_c2s then
    local bytes, _, seed = try_decrypt(tvb)
    if bytes then
      dec = make_decoded(bytes)
      used_seed = seed
    end
  end

  root:add(f_udp_dir, tvb(0, 1), is_c2s and "C->S" or "S->C")
  root:add(f_udp_enc, tvb(0, 1), dec ~= nil)
  if used_seed ~= nil then
    root:add(f_udp_seed, tvb(0, 1), used_seed)
  end

  local op = ctx_u8(tvb, dec, 0)
  root:add(f_udp_opcode, tvb(0, 1), op)
  root:add(f_udp_opname, tvb(0, 1), opcode_name_udp_top(op))

  if op == 0x04 then
    dissect_udp_alive(tvb, dec, root)
  elseif op == 0x13 then
    dissect_udp_gamedata(tvb, dec, root)
  elseif op == 0x01 or op == 0x03 or op == 0x08 then
    -- Short handshake / sync / abort: show payload as bytes
    if tvb:len() > 1 then
      root:add(f_udp_raw, tvb(1, tvb:len() - 1))
    end
  else
    root:add(f_udp_raw, tvb())
  end

  pinfo.cols.info = string.format("%s %s (%d bytes)",
    is_c2s and "C->S" or "S->C",
    opcode_name_udp_top(op),
    tvb:len())
end

-- ========================================================================
-- Registration
-- ========================================================================

do
  local tcp_table = DissectorTable.get("tcp.port")
  tcp_table:add(12000, nc2_tcp)

  local udp_table = DissectorTable.get("udp.port")
  for p = 5000, 5999 do
    udp_table:add(p, nc2_udp)
  end
end
