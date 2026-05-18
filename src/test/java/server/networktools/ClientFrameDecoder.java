package server.networktools;

import java.util.ArrayList;
import java.util.List;

/**
 * Faithful model of the Neocron 2 client's application-layer frame
 * pipeline, reverse-engineered byte-exactly from {@code
 * neocronclient.exe} and used as the oracle for framing regression
 * tests.
 *
 * <p><strong>Every step is cited to a decompiled function</strong>
 * (dumps: {@code docs/re_state_sync_dump*.txt},
 * {@code docs/re_framing_dump*.txt}; spec:
 * {@code docs/protocol/RE_state_sync.md}):
 *
 * <ol>
 *   <li><b>{@code FUN_0055f5a0} @ 0055f5a0</b> — WINSOCKMGR receive:
 *       hands {@code FUN_0055ec10} the whole decrypted datagram
 *       ({@code param_4=&plain[0]}, {@code param_5=totalLen}).</li>
 *   <li><b>{@code FUN_0055ec10} @ 0055ec10</b> — switch on
 *       {@code *param_4}. The in-game gamedata datagram's byte 0 is
 *       {@code 0x13} (≥0x0F) → {@code default:} → datagram is the
 *       payload; the {@code 0x13} outer + per-sub framing is parsed
 *       by the netbuffer class' splitter
 *       ({@code FUN_004b8f00}'s vtable pre-handler).</li>
 *   <li><b>0x13 sub-splitter</b> — the canonical reference parser
 *       ({@code tools/npc-lifecycle.py}, verified 2026-05-17) and
 *       the retail decoded burst ({@code docs/retail_decoded_burst.txt})
 *       pin the wire as:
 *       <pre>13 [octr LE2] [octr+sk LE2]
 *  ( [subLen LE2] [0x03] [seq LE2] [op] [data] )+</pre>
 *       where {@code subLen == len([0x03][seq LE2][op][data])}. The
 *       reliable {@code [0x03][seq LE2]} wrapper is stripped; the
 *       application message body is {@code [op][data]} with size
 *       {@code subLen - 3}.</li>
 *   <li><b>{@code FUN_004b7190} @ 004b7190</b> — ClientNetBuffer
 *       enqueue: writes {@code [size LE4][channel 1B][body, size
 *       bytes]} into the queue. {@code size} = {@code param_1[0]},
 *       {@code body} = {@code param_1[1]}.</li>
 *   <li><b>{@code FUN_004b8cd0} @ 004b8cd0</b> — dequeue: reads
 *       {@code size LE4}, {@code channel 1B}, then {@code body =
 *       base + readoff} and advances {@code readoff += size}.</li>
 *   <li><b>{@code FUN_00541f20} @ 00541f20</b> — WWORLDMGR: {@code
 *       switch(body[0])}; {@code default:} logs {@code
 *       "@WWORLDMGR : Corrupted Message Type:%i, Size:%i"} with
 *       {@code body[0]} and the dequeued {@code size}. There is NO
 *       {@code case 0x0F}.</li>
 * </ol>
 *
 * <p>This class implements steps 3–5 exactly (the only steps that
 * touch wire bytes; the cipher and the {@code -0x0F} datagram-byte
 * bias do not affect per-sub framing). A {@link Message} mirrors
 * the 12-byte {@code {size,bodyPtr,channel}} descriptor that {@code
 * FUN_004b8cd0} fills.
 */
public final class ClientFrameDecoder {

    /** One dequeued ClientNetBuffer message. */
    public static final class Message {
        /** {@code desc[0]} — what {@code FUN_00541f20} prints as
         *  {@code Size:}. Must equal {@link #body}.length. */
        public final int size;
        /** {@code desc[2]} — the reliable wrapper byte (0x03). */
        public final int channel;
        /** {@code desc[1]} — body; {@code body[0]} is the WWORLDMGR
         *  Message Type that {@code switch(*pcVar2)} dispatches. */
        public final byte[] body;
        /** Reliable sequence the splitter stripped (diagnostics). */
        public final int seq;

        Message(int size, int channel, byte[] body, int seq) {
            this.size = size;
            this.channel = channel;
            this.body = body;
            this.seq = seq;
        }

        public int type() {
            return body.length > 0 ? body[0] & 0xFF : -1;
        }
    }

    private ClientFrameDecoder() {}

    /**
     * Decode one finalized {@code 0x13} datagram exactly as the
     * client's splitter + ClientNetBuffer would, returning the
     * ordered list of dequeued messages.
     *
     * @throws FramingError if the wire violates the client's
     *         length invariants (mirrors {@code FUN_004b8cd0}'s
     *         {@code "@WCLIENT : Error parsing ClientNetBuffer"} /
     *         {@code FUN_0055f5a0}'s sublen bounds check).
     */
    public static List<Message> decode(byte[] datagram) {
        List<Message> out = new ArrayList<>();
        int n = datagram.length;
        if (n < 5 || (datagram[0] & 0xFF) != 0x13) {
            throw new FramingError(
                    "datagram byte 0 must be 0x13 (got "
                    + (n > 0 ? hex(datagram[0]) : "empty") + ")");
        }
        // [0x13][octr LE2][octr+sk LE2] — outer header, 5 bytes.
        int i = 5;
        while (i + 2 <= n) {
            int subLen = (datagram[i] & 0xFF)
                    | ((datagram[i + 1] & 0xFF) << 8);
            i += 2;
            if (subLen == 0) {
                break; // FUN_0055f5a0: (datasize) > 0 loop guard
            }
            if (i + subLen > n) {
                throw new FramingError(
                        "subLen " + subLen + " exceeds remaining "
                        + (n - i) + " bytes (sub at off " + (i - 2)
                        + ") — would over-read the datagram");
            }
            int subStart = i;
            i += subLen;

            int w = datagram[subStart] & 0xFF;
            if (w == 0x03 || w == 0x02) {
                // Reliable / ack-chan: [w][seq LE2][op][data].
                if (subLen < 4) {
                    throw new FramingError(
                            "reliable sub too short (" + subLen
                            + "B) — no room for [w][seq][op]");
                }
                int seq = (datagram[subStart + 1] & 0xFF)
                        | ((datagram[subStart + 2] & 0xFF) << 8);
                // Application body the client parses = sub[3:]
                // (= [op][data]); size = subLen - 3.
                byte[] body = new byte[subLen - 3];
                System.arraycopy(datagram, subStart + 3, body, 0,
                        body.length);
                out.add(new Message(body.length, w, body, seq));
            } else {
                byte[] sub = new byte[subLen];
                System.arraycopy(datagram, subStart, sub, 0, subLen);
                out.add(new Message(subLen, w, sub, -1));
            }
        }
        return out;
    }

    /** Raised when the wire violates a client length invariant. */
    public static final class FramingError extends RuntimeException {
        FramingError(String m) { super(m); }
    }

    private static String hex(byte b) {
        return String.format("0x%02x", b & 0xFF);
    }
}
