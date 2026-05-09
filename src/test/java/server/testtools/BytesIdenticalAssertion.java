package server.testtools;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import server.networktools.PacketBuilderTCP;

/**
 * Bit-for-bit retail parity assertion utility. Compares Ceres-J emitted
 * bytes against retail captures stored in {@code docs/protocol/_data/packets.json}.
 *
 * <p>Catalog keys use the filesystem form documented in
 * {@code docs/protocol/packets/}: {@code tcp_s2c_830d}, {@code udp_s2c_03_2c},
 * {@code udp_s2c_03_07_01}. Internally these are translated to the JSON
 * form ({@code TCP S->C 0x830d}, {@code UDP S->C 0x03/0x2c}, etc.).
 */
public final class BytesIdenticalAssertion {

    private static final String CATALOG_RELATIVE_PATH =
            "docs/protocol/_data/packets.json";

    private static volatile JsonObject CACHED_PACKETS = null;
    private static volatile Path CACHED_PATH = null;

    private BytesIdenticalAssertion() { }

    public static void assertMatchesRetail(byte[] actual, String catalogKey) {
        List<byte[]> samples = loadRetailSamples(catalogKey);
        if (samples.isEmpty()) {
            throw new AssertionError(
                    "no retail samples found for catalog key '" + catalogKey
                            + "' (json key: '" + toJsonKey(catalogKey) + "')");
        }
        byte[] compareBytes = stripTcpFrameIfApplicable(actual, catalogKey);
        for (byte[] sample : samples) {
            if (java.util.Arrays.equals(sample, compareBytes)) {
                return;
            }
        }
        // Pick the closest sample (by length) for the diff report.
        byte[] closest = samples.get(0);
        int bestDelta = Math.abs(closest.length - compareBytes.length);
        for (byte[] s : samples) {
            int d = Math.abs(s.length - compareBytes.length);
            if (d < bestDelta) {
                bestDelta = d;
                closest = s;
            }
        }
        throw new AssertionError(
                "actual bytes did not match any of " + samples.size()
                        + " retail samples for '" + catalogKey + "'.\n"
                        + describeDiff(closest, compareBytes));
    }

    public static void assertMatchesRetailSample(byte[] actual,
                                                 String catalogKey,
                                                 int sampleIdx) {
        List<byte[]> samples = loadRetailSamples(catalogKey);
        if (sampleIdx < 0 || sampleIdx >= samples.size()) {
            throw new AssertionError(
                    "sample index " + sampleIdx + " out of range for '"
                            + catalogKey + "' (have " + samples.size()
                            + " samples)");
        }
        byte[] expected = samples.get(sampleIdx);
        byte[] compareBytes = stripTcpFrameIfApplicable(actual, catalogKey);
        if (!java.util.Arrays.equals(expected, compareBytes)) {
            throw new AssertionError(
                    "actual bytes did not match retail sample "
                            + sampleIdx + " for '" + catalogKey + "'.\n"
                            + describeDiff(expected, compareBytes));
        }
    }

    /**
     * The catalog at {@code _data/packets.json} stores TCP samples with
     * the {@code fe LL LL} envelope already stripped (see
     * {@code tools/catalog_extract.py:_fold_tcp}). When the call-site
     * passes wire bytes for a TCP key, strip the frame so the comparison
     * sees the same view. UDP keys are left untouched.
     */
    private static byte[] stripTcpFrameIfApplicable(byte[] actual,
                                                    String catalogKey) {
        if (!catalogKey.toLowerCase(Locale.ROOT).startsWith("tcp_")) {
            return actual;
        }
        if (actual.length >= 3 && (actual[0] & 0xff) == 0xfe) {
            int len = (actual[1] & 0xff) | ((actual[2] & 0xff) << 8);
            if (3 + len <= actual.length) {
                byte[] body = new byte[len];
                System.arraycopy(actual, 3, body, 0, len);
                return body;
            }
        }
        return actual;
    }

    public static String describeDiff(byte[] expected, byte[] actual) {
        StringBuilder sb = new StringBuilder();
        int common = Math.min(expected.length, actual.length);
        int firstDiff = -1;
        for (int i = 0; i < common; i++) {
            if (expected[i] != actual[i]) {
                firstDiff = i;
                break;
            }
        }
        if (firstDiff == -1 && expected.length != actual.length) {
            firstDiff = common;
        }
        if (firstDiff == -1) {
            sb.append("byte arrays are identical (").append(common)
                    .append(" bytes)\n");
        } else if (firstDiff < common) {
            sb.append("first differs at offset ").append(firstDiff)
                    .append(": expected ")
                    .append(hex2(expected[firstDiff]))
                    .append(", actual ")
                    .append(hex2(actual[firstDiff]))
                    .append('\n');
        } else {
            sb.append("arrays share ").append(common)
                    .append("-byte prefix; lengths differ: expected=")
                    .append(expected.length).append(" actual=")
                    .append(actual.length).append('\n');
        }
        sb.append("expected (").append(expected.length).append("B): ")
                .append(toHex(expected)).append('\n');
        sb.append("actual   (").append(actual.length).append("B): ")
                .append(toHex(actual)).append('\n');
        return sb.toString();
    }

    public static List<byte[]> loadRetailSamples(String catalogKey) {
        JsonObject packets = loadCatalog();
        String jsonKey = toJsonKey(catalogKey);
        if (!packets.has(jsonKey)) {
            return Collections.emptyList();
        }
        JsonElement entry = packets.get(jsonKey);
        if (!entry.isJsonObject()) {
            return Collections.emptyList();
        }
        JsonElement samplesEl = entry.getAsJsonObject().get("samples");
        if (samplesEl == null || !samplesEl.isJsonArray()) {
            return Collections.emptyList();
        }
        JsonArray samples = samplesEl.getAsJsonArray();
        List<byte[]> out = new ArrayList<>(samples.size());
        for (JsonElement s : samples) {
            if (s.isJsonPrimitive()) {
                out.add(hexToBytes(s.getAsString()));
            }
        }
        return out;
    }

    public static byte[] sliceWire(ByteArrayOutputStream packet) {
        if (packet instanceof PacketBuilderTCP tcp) {
            // getData() finalizes the FE-frame LE16 length bytes in
            // the underlying buffer; toByteArray() then slices to count.
            tcp.getData();
        }
        return packet.toByteArray();
    }

    // ---- internals ----

    static String toJsonKey(String catalogKey) {
        if (catalogKey == null) {
            throw new IllegalArgumentException("catalogKey is null");
        }
        String[] parts = catalogKey.toLowerCase(Locale.ROOT).split("_");
        if (parts.length < 3) {
            throw new IllegalArgumentException(
                    "catalogKey must be like 'tcp_s2c_830d' or "
                            + "'udp_s2c_03_2c': '" + catalogKey + "'");
        }
        String transport = parts[0];
        String dir = parts[1];
        String transportU;
        if (transport.equals("tcp")) {
            transportU = "TCP";
        } else if (transport.equals("udp")) {
            transportU = "UDP";
        } else {
            throw new IllegalArgumentException(
                    "unknown transport '" + transport + "' in '"
                            + catalogKey + "'");
        }
        String dirArrow;
        if (dir.equals("s2c")) {
            dirArrow = "S->C";
        } else if (dir.equals("c2s")) {
            dirArrow = "C->S";
        } else {
            throw new IllegalArgumentException(
                    "unknown direction '" + dir + "' in '"
                            + catalogKey + "'");
        }
        StringBuilder ops = new StringBuilder();
        for (int i = 2; i < parts.length; i++) {
            if (i > 2) {
                ops.append('/');
            }
            ops.append("0x").append(parts[i]);
        }
        return transportU + " " + dirArrow + " " + ops;
    }

    private static JsonObject loadCatalog() {
        JsonObject cached = CACHED_PACKETS;
        if (cached != null) {
            return cached;
        }
        synchronized (BytesIdenticalAssertion.class) {
            if (CACHED_PACKETS != null) {
                return CACHED_PACKETS;
            }
            Path path = resolveCatalogPath();
            CACHED_PATH = path;
            try {
                String json = Files.readString(path);
                JsonElement root = JsonParser.parseString(json);
                JsonObject obj = root.getAsJsonObject();
                JsonElement packets = obj.get("packets");
                if (packets == null || !packets.isJsonObject()) {
                    throw new IllegalStateException(
                            "catalog at " + path
                                    + " has no 'packets' object");
                }
                CACHED_PACKETS = packets.getAsJsonObject();
                return CACHED_PACKETS;
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "failed to read catalog at " + path, e);
            }
        }
    }

    private static Path resolveCatalogPath() {
        // Walk up from the current working directory looking for
        // docs/protocol/_data/packets.json. Tests can be invoked from
        // the project root or from a nested module directory; both
        // should resolve.
        Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve(CATALOG_RELATIVE_PATH);
            if (Files.exists(candidate)) {
                return candidate;
            }
            // Also look inside ceres-j/ subdir (in case cwd is the
            // outer Neocron repo root rather than ceres-j itself).
            Path ceresCandidate = p.resolve("ceres-j")
                    .resolve(CATALOG_RELATIVE_PATH);
            if (Files.exists(ceresCandidate)) {
                return ceresCandidate;
            }
        }
        throw new IllegalStateException(
                "could not locate " + CATALOG_RELATIVE_PATH
                        + " from working directory " + cwd);
    }

    private static byte[] hexToBytes(String hex) {
        String clean = hex.trim().replace(" ", "");
        if ((clean.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "hex string has odd length: '" + hex + "'");
        }
        byte[] out = new byte[clean.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(clean.charAt(i * 2), 16);
            int lo = Character.digit(clean.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException(
                        "non-hex character in '" + hex + "'");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (int i = 0; i < data.length; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(hex2(data[i]));
        }
        return sb.toString();
    }

    private static String hex2(byte b) {
        return String.format("%02x", b & 0xff);
    }

    // Test hooks — package-private.
    static void resetCacheForTesting() {
        synchronized (BytesIdenticalAssertion.class) {
            CACHED_PACKETS = null;
            CACHED_PATH = null;
        }
    }

    /**
     * Hand-rolled fallback parser, exposed for tests. Uses Gson — the
     * project already depends on it. Kept separate from {@link #loadCatalog}
     * so test code can read packets.json without touching the cache.
     */
    static JsonObject parseCatalogForTesting(Path path) throws IOException {
        String json = Files.readString(path);
        return JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonObject("packets");
    }

    @SuppressWarnings("unused")
    private static final Gson GSON_PIN = new Gson(); // ensure dep is on cp
}
