package server.testtools;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import server.networktools.WireEncrypt;

/**
 * Pure-Java loader for the libpcap file format, specialised for
 * Neocron 2 retail captures. Decrypts UDP datagrams with the LFSR
 * cipher (via {@link WireEncrypt}) and splits each {@code 0x13}
 * gamedata datagram into the same sub-packet boundaries the
 * production reader uses ({@code GamePacketReaderUDP}).
 *
 * <h3>Wire format</h3>
 *
 * <p>libpcap globals (24 B): magic + ver + tz/sig + snaplen +
 * linktype. Records are {@code [ts_sec][ts_usec][caplen][origlen]}
 * + caplen bytes of link-layer payload. Supported linktypes:
 * {@code 1=Ethernet}, {@code 101=raw IP}, {@code 113=Linux SLL}.
 *
 * <h3>Output</h3>
 *
 * <p>{@link #load(File)} returns a flat {@link List} of
 * {@link Record} entries in capture order. Each record has:
 * <ul>
 *   <li>{@code direction} — {@code C2S} or {@code S2C} (relative
 *       to the highest-traffic non-loopback peer, treated as the
 *       server)</li>
 *   <li>{@code proto} — {@code UDP_SUB}, {@code UDP_RAW}, or
 *       {@code TCP}</li>
 *   <li>{@code bytes} — for {@code UDP_SUB}, the inner sub-packet
 *       body (decrypted, post-0x13 split). For {@code UDP_RAW}, a
 *       full decrypted plaintext datagram that is NOT a 0x13 frame
 *       (handshake/sync/abort). For {@code TCP}, the raw segment
 *       payload bytes.</li>
 *   <li>{@code timestamp} — capture timestamp in microseconds</li>
 * </ul>
 *
 * <h3>Why pure-Java?</h3>
 *
 * <p>Avoids a scapy shellout and keeps the harness runnable from
 * a plain {@code mvn test} invocation with no system dependencies
 * beyond the JDK.
 */
public final class PcapReplay {

    /** Direction relative to the auto-detected server peer. */
    public enum Direction { C2S, S2C }

    /** Protocol classification of a record. */
    public enum Proto { UDP_SUB, UDP_RAW, TCP }

    /** Single replayable record from the capture. */
    public static final class Record {
        public final long timestampUs;
        public final Direction direction;
        public final Proto proto;
        public final byte[] bytes;
        /** For UDP_SUB: the parent 0x13 datagram's
         *  decrypted plaintext, useful for diagnostics. Null
         *  otherwise. */
        public final byte[] parentDatagram;
        /** Source IP/port (string form) — diagnostic only. */
        public final String src;
        /** Destination IP/port (string form) — diagnostic only. */
        public final String dst;

        Record(long ts, Direction dir, Proto proto, byte[] bytes,
                byte[] parent, String src, String dst) {
            this.timestampUs = ts;
            this.direction = dir;
            this.proto = proto;
            this.bytes = bytes;
            this.parentDatagram = parent;
            this.src = src;
            this.dst = dst;
        }

        /** First byte of {@link #bytes}, or -1 if empty. */
        public int firstByte() {
            return bytes.length > 0 ? (bytes[0] & 0xFF) : -1;
        }

        /** Short hex preview ({@code n} bytes) for diagnostics. */
        public String previewHex(int n) {
            int m = Math.min(n, bytes.length);
            StringBuilder sb = new StringBuilder(m * 2);
            for (int i = 0; i < m; i++) {
                sb.append(String.format("%02x", bytes[i] & 0xFF));
            }
            if (m < bytes.length) sb.append("..");
            return sb.toString();
        }

        @Override public String toString() {
            return String.format("[%s/%s %s→%s len=%d %s]",
                    direction, proto, src, dst, bytes.length,
                    previewHex(16));
        }
    }

    /** Result of loading a pcap. */
    public static final class Loaded {
        public final List<Record> records;
        /** Auto-detected server IP (highest-traffic non-loopback
         *  peer). May be {@code null} if no peer found. */
        public final String serverIp;
        /** Total packets in the file (including ones skipped). */
        public final int totalPackets;
        /** Counts per direction × proto, for quick triage. */
        public final Map<String, Integer> counts;

        Loaded(List<Record> records, String serverIp,
                int total, Map<String, Integer> counts) {
            this.records = records;
            this.serverIp = serverIp;
            this.totalPackets = total;
            this.counts = counts;
        }
    }

    // ── Public entry points ─────────────────────────────────

    /** Load and decode a pcap. */
    public static Loaded load(File pcap) throws IOException {
        return load(pcap, null);
    }

    /** Load and decode a pcap, treating {@code serverIp} as the
     *  S2C source. If null, auto-detect. */
    public static Loaded load(File pcap, String serverIp)
            throws IOException {
        List<RawPacket> raws;
        try (InputStream in = new FileInputStream(pcap)) {
            raws = readPcap(in);
        }

        if (serverIp == null) {
            serverIp = detectServerIp(raws);
        }

        List<Record> out = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>();
        for (RawPacket rp : raws) {
            if (rp.srcIp == null) continue;
            // Filter to packets touching the server peer
            if (serverIp != null
                    && !serverIp.equals(rp.srcIp)
                    && !serverIp.equals(rp.dstIp)) {
                continue;
            }
            Direction dir = (serverIp != null
                    && serverIp.equals(rp.srcIp))
                    ? Direction.S2C : Direction.C2S;
            String srcS = rp.srcIp + ":" + rp.srcPort;
            String dstS = rp.dstIp + ":" + rp.dstPort;

            if (rp.isUdp) {
                if (rp.payload.length < 4) continue;
                byte[] plain = WireEncrypt.decrypt(rp.payload);
                if (plain == null || plain.length == 0) continue;

                int hdr = plain[0] & 0xFF;
                if (hdr == 0x13 && plain.length >= 7) {
                    // 0x13 frame: skip 4-byte counter+sessionkey
                    // header at offset 1, then loop sub-packets.
                    int pos = 5;
                    while (pos + 2 <= plain.length) {
                        int sz = (plain[pos] & 0xFF)
                                | ((plain[pos + 1] & 0xFF) << 8);
                        pos += 2;
                        if (sz <= 0) break;
                        if (pos + sz > plain.length) break;
                        byte[] sub = new byte[sz];
                        System.arraycopy(plain, pos, sub, 0, sz);
                        pos += sz;
                        out.add(new Record(rp.tsUs, dir,
                                Proto.UDP_SUB, sub, plain,
                                srcS, dstS));
                        bump(counts, dir + "/UDP_SUB");
                    }
                } else {
                    // Non-0x13 plaintext: handshake/sync/abort.
                    // Replay these whole.
                    out.add(new Record(rp.tsUs, dir, Proto.UDP_RAW,
                            plain, plain, srcS, dstS));
                    bump(counts, dir + "/UDP_RAW");
                }
            } else if (rp.isTcp) {
                if (rp.payload.length == 0) continue;
                out.add(new Record(rp.tsUs, dir, Proto.TCP,
                        rp.payload, null, srcS, dstS));
                bump(counts, dir + "/TCP");
            }
        }
        return new Loaded(out, serverIp, raws.size(), counts);
    }

    private static void bump(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }

    // ── Server-peer auto-detection ──────────────────────────

    private static String detectServerIp(List<RawPacket> raws) {
        Map<String, Integer> peerCount = new HashMap<>();
        int seen = 0;
        for (RawPacket rp : raws) {
            if (rp.srcIp == null) continue;
            if (++seen > 5000) break;
            for (String ip : new String[]{rp.srcIp, rp.dstIp}) {
                if (ip == null) continue;
                // Treat 127.x, 10.x, 172.16-31.x, 192.168.x as
                // local. Server is whichever non-local peer
                // has the most packets.
                if (isLocal(ip)) continue;
                peerCount.merge(ip, 1, Integer::sum);
            }
        }
        String best = null;
        int bestN = -1;
        for (Map.Entry<String, Integer> e : peerCount.entrySet()) {
            if (e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    private static boolean isLocal(String ip) {
        if (ip.startsWith("127.")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            // 172.16.0.0 – 172.31.255.255
            int dot = ip.indexOf('.', 4);
            if (dot < 0) return false;
            try {
                int second = Integer.parseInt(
                        ip.substring(4, dot));
                return second >= 16 && second <= 31;
            } catch (NumberFormatException e) { return false; }
        }
        return false;
    }

    // ── pcap file parsing ───────────────────────────────────

    /** Internal parsed packet (link-layer stripped, IP/UDP/TCP
     *  fields extracted). */
    private static final class RawPacket {
        long tsUs;
        String srcIp, dstIp;
        int srcPort, dstPort;
        boolean isUdp, isTcp;
        byte[] payload = new byte[0];
    }

    private static List<RawPacket> readPcap(InputStream in)
            throws IOException {
        DataInputStream din = new DataInputStream(in);

        // Global header (24 B). Magic determines byte order.
        int magic = din.readInt();
        boolean little;
        if (magic == 0xa1b2c3d4) {
            little = false;
        } else if (magic == 0xd4c3b2a1) {
            little = true;
        } else {
            throw new IOException(
                    "Not a pcap (magic 0x"
                            + Integer.toHexString(magic) + ")");
        }
        // version major/minor + tz + sigfigs + snaplen (skip)
        readBytes(din, 16);
        int linktype = readU32(din, little);

        List<RawPacket> out = new ArrayList<>();
        while (true) {
            long ts;
            int caplen;
            try {
                long tsSec = readU32(din, little) & 0xFFFFFFFFL;
                long tsUsec = readU32(din, little) & 0xFFFFFFFFL;
                ts = tsSec * 1_000_000L + tsUsec;
                caplen = readU32(din, little);
                readU32(din, little); // origlen
            } catch (EOFException e) { break; }
            byte[] frame = readBytes(din, caplen);
            RawPacket rp = parseFrame(frame, linktype);
            if (rp != null) {
                rp.tsUs = ts;
                out.add(rp);
            }
        }
        return out;
    }

    private static int readU32(DataInputStream din, boolean little)
            throws IOException {
        int v = din.readInt();
        if (little) {
            v = Integer.reverseBytes(v);
        }
        return v;
    }

    private static byte[] readBytes(DataInputStream din, int n)
            throws IOException {
        byte[] b = new byte[n];
        din.readFully(b);
        return b;
    }

    /**
     * Parse one link-layer frame into a {@link RawPacket}. Returns
     * null if the frame isn't IPv4/UDP|TCP we can replay.
     */
    private static RawPacket parseFrame(byte[] frame, int linktype) {
        int ipOff;
        switch (linktype) {
            case 1:   // Ethernet
                if (frame.length < 14) return null;
                int ethType = ((frame[12] & 0xFF) << 8)
                        | (frame[13] & 0xFF);
                if (ethType != 0x0800) return null; // not IPv4
                ipOff = 14;
                break;
            case 101: // raw IP
                ipOff = 0;
                break;
            case 113: // Linux SLL (cooked)
                if (frame.length < 16) return null;
                int sllType = ((frame[14] & 0xFF) << 8)
                        | (frame[15] & 0xFF);
                if (sllType != 0x0800) return null;
                ipOff = 16;
                break;
            case 276: // Linux SLL2
                if (frame.length < 20) return null;
                int sll2Type = ((frame[0] & 0xFF) << 8)
                        | (frame[1] & 0xFF);
                if (sll2Type != 0x0800) return null;
                ipOff = 20;
                break;
            default:
                return null;
        }

        if (frame.length < ipOff + 20) return null;
        int verIhl = frame[ipOff] & 0xFF;
        if ((verIhl >> 4) != 4) return null; // IPv6 not supported
        int ihl = (verIhl & 0x0F) * 4;
        if (ihl < 20 || frame.length < ipOff + ihl) return null;
        int totalLen = ((frame[ipOff + 2] & 0xFF) << 8)
                | (frame[ipOff + 3] & 0xFF);
        int proto = frame[ipOff + 9] & 0xFF;
        String src = ipToString(frame, ipOff + 12);
        String dst = ipToString(frame, ipOff + 16);

        int l4Off = ipOff + ihl;
        int l4End = ipOff + totalLen;
        if (l4End > frame.length) l4End = frame.length;

        RawPacket rp = new RawPacket();
        rp.srcIp = src;
        rp.dstIp = dst;

        if (proto == 17) { // UDP
            if (l4Off + 8 > frame.length) return null;
            rp.srcPort = ((frame[l4Off] & 0xFF) << 8)
                    | (frame[l4Off + 1] & 0xFF);
            rp.dstPort = ((frame[l4Off + 2] & 0xFF) << 8)
                    | (frame[l4Off + 3] & 0xFF);
            int udpLen = ((frame[l4Off + 4] & 0xFF) << 8)
                    | (frame[l4Off + 5] & 0xFF);
            int payOff = l4Off + 8;
            int payEnd = Math.min(l4Off + udpLen, l4End);
            if (payEnd > frame.length) payEnd = frame.length;
            if (payEnd <= payOff) return null;
            rp.payload = new byte[payEnd - payOff];
            System.arraycopy(frame, payOff, rp.payload, 0,
                    rp.payload.length);
            rp.isUdp = true;
            return rp;
        } else if (proto == 6) { // TCP
            if (l4Off + 20 > frame.length) return null;
            rp.srcPort = ((frame[l4Off] & 0xFF) << 8)
                    | (frame[l4Off + 1] & 0xFF);
            rp.dstPort = ((frame[l4Off + 2] & 0xFF) << 8)
                    | (frame[l4Off + 3] & 0xFF);
            int dataOff = ((frame[l4Off + 12] & 0xFF) >> 4) * 4;
            int payOff = l4Off + dataOff;
            if (payOff < l4End) {
                rp.payload = new byte[l4End - payOff];
                System.arraycopy(frame, payOff, rp.payload, 0,
                        rp.payload.length);
            }
            rp.isTcp = true;
            return rp;
        }
        return null;
    }

    private static String ipToString(byte[] f, int off) {
        return (f[off] & 0xFF) + "." + (f[off + 1] & 0xFF) + "."
                + (f[off + 2] & 0xFF) + "." + (f[off + 3] & 0xFF);
    }

    private PcapReplay() {}
}
