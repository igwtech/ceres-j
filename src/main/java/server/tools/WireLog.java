package server.tools;

/**
 * Parsed in/out wire logger (task #198).
 *
 * <p>Turns a finalized plaintext datagram (post-decrypt for C&rarr;S,
 * pre-encrypt for S&rarr;C) into a single readable multi-line block
 * so protocol debugging no longer needs a pcap round-trip. The 0x13
 * framing is split <em>byte-exactly the same way the client splits
 * it</em> &mdash; the algorithm is a 1:1 port of the oracle in
 * {@code src/test/java/server/networktools/ClientFrameDecoder.java}
 * (which is itself cited to the decompiled client splitter). Keeping
 * the two in lockstep means the log shows the same sub-packet
 * boundaries the client sees.
 *
 * <p><strong>Zero-overhead when off.</strong> Every public entry
 * point is guarded by {@link Debug#isWireEnabled()} <em>before</em>
 * any {@link StringBuilder} / hex / format work happens. When the
 * {@code wire} Debug token is absent (the default) these methods do
 * nothing but a single boolean test.
 *
 * <p>Output goes through the normal {@link Out#debug} path so it
 * shows up in {@code docker logs neocron-server} and the debug log
 * file, tagged {@code Wire}.
 */
public final class WireLog {

    /** Max bytes of a body hex-dumped before truncation. */
    private static final int HEX_CAP = 96;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private WireLog() {}

    // ── public entry points ────────────────────────────────────────

    /**
     * Log a C&rarr;S UDP datagram exactly as the receiver processes
     * it. {@code data[off..off+len]} is the decrypted plaintext
     * datagram (the same bytes {@code GamePacketReaderUDP.readPacket}
     * parses).
     */
    public static void udpIn(String player, byte[] data, int off, int len) {
        if (!Debug.isWireEnabled()) return;
        emit("C->S", "UDP", player, slice(data, off, len));
    }

    /**
     * Log an S&rarr;C UDP datagram. {@code data[off..off+len]} is the
     * finalized plaintext datagram <em>before</em> the per-packet
     * LFSR cipher is applied (i.e. exactly what
     * {@code PacketBuilderUDP*.getDatagramPackets()} produced).
     */
    public static void udpOut(String player, byte[] data, int off, int len) {
        if (!Debug.isWireEnabled()) return;
        emit("S->C", "UDP", player, slice(data, off, len));
    }

    /** Log a C&rarr;S TCP application packet (post {@code fe ll ll}
     *  framing &mdash; {@code body} is the payload). */
    public static void tcpIn(String player, byte[] body, int len) {
        if (!Debug.isWireEnabled()) return;
        emitTcp("C->S", player, slice(body, 0, len));
    }

    /** Log an S&rarr;C TCP application packet. */
    public static void tcpOut(String player, byte[] body, int len) {
        if (!Debug.isWireEnabled()) return;
        emitTcp("S->C", player, slice(body, 0, len));
    }

    // ── formatter (package-private for unit tests) ─────────────────

    /**
     * Build the full parsed block for a datagram. Exposed so unit
     * tests can assert on the string without standing up a socket.
     * Callers on the hot path must gate on
     * {@link Debug#isWireEnabled()} first.
     */
    static String formatUdp(String dir, String player, byte[] dg) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('\n').append("==== ").append(dir)
          .append(" UDP player=").append(player == null ? "?" : player)
          .append(" len=").append(dg.length).append(" ====");
        if (dg.length == 0) {
            sb.append("\n  (empty)");
            return sb.toString();
        }
        int b0 = dg[0] & 0xFF;
        if (b0 != 0x13) {
            // Raw (non-0x13) datagram — handshake / UDPAlive / abort.
            sb.append("\n  raw datagram op=0x").append(hx(b0))
              .append(" (").append(rawUdpName(b0)).append(')')
              .append("\n    ").append(hexDump(dg, 0, dg.length));
            return sb.toString();
        }
        // [0x13][octr LE2][octr+sk LE2] outer header (5 bytes).
        if (dg.length < 5) {
            sb.append("\n  0x13 datagram truncated (<5B header)");
            return sb.toString();
        }
        int octr = u16(dg, 1);
        int octrSk = u16(dg, 3);
        sb.append("\n  0x13 outer: counter=").append(octr)
          .append(" counter+sessionkey=").append(octrSk);
        int i = 5;
        int subIdx = 0;
        while (i + 2 <= dg.length) {
            int subLen = u16(dg, i);
            i += 2;
            if (subLen == 0) {
                break; // client splitter's (datasize)>0 loop guard
            }
            if (i + subLen > dg.length) {
                sb.append("\n  [sub ").append(subIdx)
                  .append("] MALFORMED subLen=").append(subLen)
                  .append(" exceeds remaining ").append(dg.length - i);
                break;
            }
            appendSub(sb, subIdx, dg, i, subLen);
            i += subLen;
            subIdx++;
        }
        if (subIdx == 0) {
            sb.append("\n  (no sub-packets)");
        }
        return sb.toString();
    }

    static String formatTcp(String dir, String player, byte[] body) {
        StringBuilder sb = new StringBuilder(128);
        sb.append('\n').append("==== ").append(dir)
          .append(" TCP player=").append(player == null ? "?" : player)
          .append(" len=").append(body.length).append(" ====");
        if (body.length == 0) {
            sb.append("\n  (empty)");
            return sb.toString();
        }
        int op = body[0] & 0xFF;
        int sub = body.length >= 2 ? body[1] & 0xFF : -1;
        sb.append("\n  opcode=0x").append(hx(op));
        if (sub >= 0) sb.append(String.format("%02x", sub));
        sb.append(" (").append(tcpName(op, sub)).append(')')
          .append("\n    ").append(hexDump(body, 0, body.length));
        return sb.toString();
    }

    // ── internal ───────────────────────────────────────────────────

    private static void emit(String dir, String tp, String player, byte[] dg) {
        Out.debug("Wire", formatUdp(dir, player, dg));
    }

    private static void emitTcp(String dir, String player, byte[] body) {
        Out.debug("Wire", formatTcp(dir, player, body));
    }

    /** One reliable / raw sub-packet inside a 0x13 datagram. */
    private static void appendSub(StringBuilder sb, int idx,
                                  byte[] dg, int start, int subLen) {
        int w = dg[start] & 0xFF;
        sb.append("\n  [sub ").append(idx).append("] subLen=")
          .append(subLen).append(" wrapper=0x").append(hx(w));
        if (w == 0x03 || w == 0x02) {
            if (subLen < 4) {
                sb.append(" (reliable too short for [w][seq][op])");
                return;
            }
            int seq = u16(dg, start + 1);
            int op = dg[start + 3] & 0xFF;
            // Application body = sub[3:] (=[op][data]); size subLen-3.
            int bodyOff = start + 3;
            int bodyLen = subLen - 3;
            sb.append(w == 0x02 ? " (ackchan)" : " (reliable)")
              .append(" seq=").append(seq)
              .append(" op=0x").append(hx(op))
              .append(" (").append(opName(op)).append(')');
            appendOpDetail(sb, op, dg, bodyOff, bodyLen);
        } else {
            // Raw sub-packet — body is the whole sub.
            sb.append(" op=0x").append(hx(w))
              .append(" (").append(opName(w)).append(')');
            appendOpDetail(sb, w, dg, start, subLen);
        }
    }

    /**
     * Decode the inner detail of a sub-packet. For the 0x1f
     * GamePackets multiplexer this mirrors the tag/sub-tag ladder in
     * {@code GamePacketReaderUDP.decodesub13} so the log names the
     * action; everything else gets a capped hex dump of the body.
     *
     * @param p     buffer
     * @param boff  offset of the op byte
     * @param blen  length of [op][data]
     */
    private static void appendOpDetail(StringBuilder sb, int op,
                                       byte[] p, int boff, int blen) {
        if (op == 0x1f && blen >= 4) {
            // [0x1f][localId LE2][tag]([subtag]...)
            int localId = u16(p, boff + 1);
            int tag = p[boff + 3] & 0xFF;
            int subtag = blen >= 5 ? p[boff + 4] & 0xFF : -1;
            sb.append("\n    1f: localId=").append(localId)
              .append(" tag=0x").append(hx(tag))
              .append(" (").append(tagName(tag)).append(')');
            if (subtag >= 0) {
                sb.append(" subtag=0x").append(hx(subtag));
            }
            // body after [1f][id LE2][tag] = data
            int dOff = boff + 4;
            int dLen = blen - 4;
            if (dLen > 0) {
                sb.append("\n    1f-body: ")
                  .append(hexDump(p, dOff, dLen));
            }
            return;
        }
        if (blen <= 1) return; // op-only, nothing more to show
        sb.append("\n    body: ").append(hexDump(p, boff + 1, blen - 1));
    }

    // ── name registries (mirror decodesub13 + retail catalog) ──────

    /** Sub-packet op byte name (the byte after the reliable wrapper,
     *  or the raw datagram first byte). Mirrors the switch in
     *  {@code GamePacketReaderUDP.decodesub13}. */
    static String opName(int op) {
        switch (op) {
            case 0x01: return "ReliableAck/RetransmitReq";
            case 0x02: return "AckChan";
            case 0x03: return "Reliable";
            case 0x07: return "Multipart";
            case 0x08: return "AbortSession";
            case 0x0b: return "CPing";
            case 0x0c: return "GetTimeSync";
            case 0x0d: return "TimeSync/ReliableSync";
            case 0x1f: return "GamePackets";
            case 0x20: return "Movement";
            case 0x22: return "Zoning";
            case 0x24: return "ReadyForWorldState";
            case 0x27: return "RequestInfoAboutWorldID";
            case 0x28: return "WorldInfo";
            case 0x2a: return "RequestPositionUpdate";
            case 0x2c: return "CharInfo";
            case 0x2d: return "NpcData/LstPlayer";
            case 0x31: return "ShortPlayerInfo";
            case 0x3c: return "Telemetry3c";
            case 0x00: return "Sub0x00";
            case 0x06: return "Sub0x06";
            case 0x0f: return "Sub0x0F";
            case 0x11: return "Sub0x11";
            default:   return "unknown";
        }
    }

    /** 0x1f tag-byte name. Mirrors the {@code case 0x1f:} ladder in
     *  decodesub13 and the docs/protocol sub-tag map. */
    static String tagName(int tag) {
        switch (tag) {
            case 0x06: return "AdminCommand";
            case 0x17: return "UseItem";
            case 0x1a: return "Dialog";
            case 0x1b: return "LocalChat";
            case 0x1e: return "InventoryMove";
            case 0x22: return "ExitSeat";
            case 0x25: return "Transaction";
            case 0x26: return "Vendor/Loot";
            case 0x2a: return "MissionGrant";
            case 0x3b: return "CrossChannelChat";
            case 0x3d: return "AppAction";
            case 0x4c: return "ChangedChannels";
            default:   return "unknown";
        }
    }

    /** Name for a raw (non-0x13) datagram first byte. */
    static String rawUdpName(int b0) {
        switch (b0) {
            case 0x01: return "Handshake/ReliableAck";
            case 0x03: return "SyncUDP";
            case 0x08: return "AbortSession";
            default:   return "unknown";
        }
    }

    /** TCP opcode name (high byte = subsystem). */
    static String tcpName(int op, int sub) {
        switch (op) {
            case 0x80: return "Handshake";
            case 0x83: return "Auth/Account";
            case 0x85: return "CharSelect/Session";
            default:   return "subsystem-0x" + hx(op);
        }
    }

    // ── byte helpers ───────────────────────────────────────────────

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static byte[] slice(byte[] src, int off, int len) {
        byte[] d = new byte[Math.max(0, len)];
        if (len > 0) System.arraycopy(src, off, d, 0, len);
        return d;
    }

    private static String hx(int b) {
        return String.format("%02x", b & 0xFF);
    }

    /** Hex dump capped at {@link #HEX_CAP} bytes. */
    static String hexDump(byte[] b, int off, int len) {
        int n = Math.min(len, HEX_CAP);
        StringBuilder sb = new StringBuilder(n * 2 + 16);
        for (int k = 0; k < n; k++) {
            int v = b[off + k] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
        }
        if (len > n) {
            sb.append("...(+").append(len - n).append("B)");
        }
        return sb.toString();
    }
}
