package server.networktools;

/**
 * Neocron 2 protocol constants.
 * <p>
 * See docs/PROTOCOL.md for full protocol documentation.
 *
 * @author javier
 */
public class ProtocolConstants {

    // =========================================================================
    // TCP Framing
    // =========================================================================

    /** TCP packet header byte */
    public static final int TCP_HEADER = 0xFE;

    // =========================================================================
    // TCP Packet IDs (2 bytes, big-endian)
    // =========================================================================

    // --- Handshake ---
    public static final int TCP_HANDSHAKE_A      = 0x8001; // S->C Server hello
    public static final int TCP_HANDSHAKE_B      = 0x8000; // C->S Client hello
    public static final int TCP_HANDSHAKE_C      = 0x8003; // S->C Handshake complete

    // --- Authentication ---
    public static final int TCP_AUTH             = 0x8480; // C->S Login (user/pass)
    public static final int TCP_AUTH_ACK         = 0x8381; // S->C Login success
    public static final int TCP_AUTH_B           = 0x8301; // C->S Re-auth with char slot
    public static final int TCP_REQUEST_FAILED   = 0x8386; // S->C Generic failure

    // --- Character Management ---
    public static final int TCP_GET_CHARLIST     = 0x8482; // C->S Request char list
    public static final int TCP_CHARLIST         = 0x8385; // S->C Character list
    public static final int TCP_CHECK_CHARNAME   = 0x8482; // C->S Check name availability
    public static final int TCP_CREATE_CHAR      = 0x8482; // C->S Create character
    public static final int TCP_DELETE_CHAR      = 0x8482; // C->S Delete character

    // --- Game Data ---
    public static final int TCP_GET_GAMEDATA     = 0x8737; // C->S Request game data
    public static final int TCP_GAMEDATA         = 0x873a; // S->C Game data response
    public static final int TCP_GET_UDP_CONN     = 0x873c; // C->S Request UDP info
    public static final int TCP_UDP_SERVER_DATA  = 0x8305; // S->C UDP IP/port/session
    public static final int TCP_LOCATION         = 0x830c; // S->C Zone name
    public static final int TCP_GAMEINFO_READY   = 0x830d; // S->C Server ready

    // --- Chat ---
    public static final int TCP_CUSTOM_CHAT      = 0x8317; // C<->S Chat message
    public static final int TCP_CHAT_FAILED      = 0x8318; // S->C User not online
    public static final int TCP_CLIENT_KICKED    = 0x8303; // S->C Disconnect

    // --- Patch Server ---
    public static final int TCP_GET_PATCH_VER    = 0x7b00; // C->S Version request
    public static final int TCP_PATCH_VER        = 0x3702; // S->C Version response

    // =========================================================================
    // UDP Packet Types (first byte after decryption)
    // =========================================================================

    /** UDP handshake — session establishment */
    public static final int UDP_HANDSHAKE        = 0x01;
    /** UDP sync — reliable delivery acknowledgment */
    public static final int UDP_SYNC             = 0x03;
    /** UDP keepalive — connection heartbeat */
    public static final int UDP_KEEPALIVE        = 0x04;
    /** UDP abort — session termination */
    public static final int UDP_ABORT            = 0x08;
    /** UDP gamedata — multiplexed game packets */
    public static final int UDP_GAMEDATA         = 0x13;

    // Legacy aliases
    public static final int HEADERID_INGAME          = UDP_GAMEDATA;
    public static final int HEADERID_FE              = TCP_HEADER;
    public static final int HEADERID_REQUEST_SYNC1   = UDP_HANDSHAKE;
    public static final int HEADERID_REQUEST_SYNC2   = UDP_SYNC;
    public static final int HEADERID_UDP_KEEPALIVE   = UDP_KEEPALIVE;
    public static final int HEADERID_CLIENT_LOGOUT   = UDP_ABORT;
    public static final int PACKET_FETYPE_ZONENAME   = TCP_LOCATION;

    // =========================================================================
    // UDP Gamedata (0x13) Sub-packet Types
    // =========================================================================

    public static final int UDP_SUB_RELIABLE     = 0x03; // Reliable delivery wrapper
    public static final int UDP_SUB_PING         = 0x0b; // Client ping
    public static final int UDP_SUB_TIMESYNC     = 0x0c; // Time synchronization
    public static final int UDP_SUB_MOVEMENT     = 0x20; // Player movement
    public static final int UDP_SUB_REQUEST_POS  = 0x2a; // Request position

    // =========================================================================
    // 0x03 Reliable Sub-types
    // =========================================================================

    public static final int REL_RESEND           = 0x01; // Retransmission request
    public static final int REL_MULTIPART        = 0x07; // Multi-part packet
    public static final int REL_ZONING_END       = 0x08; // Zone transition complete
    public static final int REL_TIMESYNC         = 0x0d; // Server time sync
    public static final int REL_GROUP_1B         = 0x1b; // NPC/world item group
    public static final int REL_GAME_PACKETS     = 0x1f; // Core game packets
    public static final int REL_INFO_REQUEST     = 0x22; // Char/clan/map request
    public static final int REL_INFO_RESPONSE    = 0x23; // Char/clan/map response
    public static final int REL_PLAYER_INFO      = 0x25; // Player info in zone
    public static final int REL_REMOVE_WORLD_ITEM = 0x26; // Remove world object
    public static final int REL_REQ_WORLD_INFO   = 0x27; // Request world ID info
    public static final int REL_WORLD_INFO       = 0x28; // World ID info response
    public static final int REL_CITYCOM          = 0x2b; // CityCom terminal
    public static final int REL_START_POS        = 0x2c; // Starting position
    public static final int REL_NPC_DATA         = 0x2d; // NPC initial data
    public static final int REL_WEATHER          = 0x2e; // Weather change
    public static final int REL_UPDATE_MODEL     = 0x2f; // Player model update
    public static final int REL_SHORT_PLAYER     = 0x30; // Brief player info
    public static final int REL_REQ_SHORT_PLAYER = 0x31; // Request brief player info

    // =========================================================================
    // 0x1F Game Sub-types
    // =========================================================================

    public static final int GAME_SHOOT           = 0x01;
    public static final int GAME_JUMP            = 0x02;
    public static final int GAME_DEATH           = 0x16;
    public static final int GAME_USE             = 0x17;
    public static final int GAME_NPC_SCRIPT      = 0x18;
    public static final int GAME_DIALOG          = 0x19;
    public static final int GAME_NPC_RESPONSE    = 0x1a;
    public static final int GAME_LOCAL_CHAT      = 0x1b;
    public static final int GAME_ITEM_MOVE       = 0x1e;
    public static final int GAME_SLOT_USE        = 0x1f;
    public static final int GAME_EXIT_CHAIR      = 0x22;
    public static final int GAME_CLOSE_CONN      = 0x27;
    public static final int GAME_HACK_SUCCESS    = 0x29;
    public static final int GAME_HACK_FAIL       = 0x2c;
    public static final int GAME_OUTFITTER       = 0x2e;
    public static final int GAME_GENREP          = 0x2f;
    public static final int GAME_HLT_UPDATE      = 0x30;
    public static final int GAME_USE_DENIED      = 0x31;
    public static final int GAME_CHAT_LIST       = 0x33;
    public static final int GAME_WORLD_ACCESS    = 0x38;
    public static final int GAME_OTHER_CHAT      = 0x3b;
    public static final int GAME_QUICK_CMD       = 0x3d;
    public static final int GAME_TRADE_SETTING   = 0x3e;
    public static final int GAME_JOIN_TEAM       = 0x40;
    public static final int GAME_CHANGE_CHANNELS = 0x4c;
    public static final int GAME_PLAYER_TRADE    = 0x4e;

    // =========================================================================
    // Auth Packet Offsets (Modern NCE 2.5.x client)
    // =========================================================================

    /** Auth (0x8480) unknown block size — 30 bytes in modern client, was 18 in old */
    public static final int AUTH_UNKNOWN_BLOCK_SIZE = 30;

    /** UDPServerData flags field — retail server sends 0x00890000 */
    public static final int UDP_SERVER_DATA_FLAGS = 0x00890000;

    /** Default UDP game port */
    public static final int DEFAULT_UDP_PORT = 5000;
}
