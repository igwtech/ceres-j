package server.gameserver.packets.server_tcp;

import server.networktools.PacketBuilderTCP;

/**
 * TCP {@code 0x83 0x86} — character-op acknowledgement.
 *
 * <p>The retail server uses ONE response opcode for every {@code 0x84 0x82}
 * sub-operation (read / preview / commit / delete). A status byte at
 * offset 6 of the response body distinguishes the variant. Verified
 * against {@code RETAIL_CREATION_LEVELING_LONG} (create) and
 * {@code RETAIL_CHARDEL_SUBWAY} (delete) captures —
 * see {@code docs/protocol/flows/character_creation.md} and
 * {@code FINDINGS_2026-05-03_CHARDEL_SUBWAY.md}.
 *
 * <h3>Wire format</h3>
 *
 * <pre>
 * Success / preview-ack:
 *   83 86 01 00 00 00 [status]   (7 bytes, status byte selects variant)
 *
 * Error:
 *   83 86 06 00 [length LE2] [ASCII message — no null terminator]
 * </pre>
 *
 * <h3>Status byte enum (verified)</h3>
 *
 * <table>
 *   <tr><th>Status</th><th>Meaning</th><th>Triggered by</th></tr>
 *   <tr><td>{@code 0x00}</td><td>create-commit success</td><td>op=7 (CREATE_CHAR commit)</td></tr>
 *   <tr><td>{@code 0x05}</td><td>delete success</td><td>op=3 (DELETE_CHAR)</td></tr>
 *   <tr><td>{@code 0x3d}</td><td>name-check / preview ok</td><td>op=5 (CHECK_NAME)</td></tr>
 *   <tr><td>(error path)</td><td>any rejection</td><td>any op; emits ASCII reason</td></tr>
 * </table>
 *
 * <p>Use the factory methods below; do not call the constructor directly.
 */
public final class CharOpAck extends PacketBuilderTCP {

    /** Status byte values. */
    public static final byte STATUS_COMMIT_SUCCESS = 0x00;
    public static final byte STATUS_DELETE_SUCCESS = 0x05;
    public static final byte STATUS_PREVIEW_OK     = 0x3d;

    private CharOpAck(byte status) {
        super();
        write(0x83);
        write(0x86);
        writeShort(0x0001); // success bit (LE: 01 00)
        writeShort(0x0000); // message-length = 0 for success variant
        write(status);
    }

    private CharOpAck(String errorMessage) {
        super();
        byte[] msg = errorMessage.getBytes();
        write(0x83);
        write(0x86);
        writeShort(0x0006); // error tag (LE: 06 00) — distinguishes from success
        writeShort(msg.length); // ASCII length, no null terminator
        write(msg);
    }

    /** Commit-success ack — used when {@code 0x84 0x82 op=7}
     *  (CREATE_CHAR commit) or after delete-confirm. */
    public static CharOpAck commitSuccess() {
        return new CharOpAck(STATUS_COMMIT_SUCCESS);
    }

    /** Delete-success ack — used after {@code 0x84 0x82 op=3}
     *  (DELETE_CHAR) succeeds. */
    public static CharOpAck deleteSuccess() {
        return new CharOpAck(STATUS_DELETE_SUCCESS);
    }

    /** Preview / name-check ok — used after {@code 0x84 0x82 op=5}
     *  (CHECK_NAME) when the proposed name is available. */
    public static CharOpAck previewAck() {
        return new CharOpAck(STATUS_PREVIEW_OK);
    }

    /** Generic error ack — emits a 0x06 status with an ASCII message
     *  body. Retail observed: "User already…" when a delete is
     *  in-flight against a slot still being deleted. */
    public static CharOpAck error(String message) {
        if (message == null) message = "ERROR";
        return new CharOpAck(message);
    }
}
