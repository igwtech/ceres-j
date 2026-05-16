package server.gameserver.packets.server_udp;

import server.gameserver.Player;
import server.networktools.PacketBuilderUDP1303;

/**
 * Zone-transition ChangeLocation message — sent when a player uses a
 * furniture/portal "world-change actor" (sewer entrance, hideout,
 * startmission, lab, holomatch exit, …).
 *
 * <p>Mirror of TinNS {@code PMsgBuilder::BuildChangeLocationMsg}
 * ({@code tinns/.../MessageBuilder.cxx:1351-1381}). TinNS emits the
 * payload as a {@code 0x0f}-length reliable sub-packet:
 *
 * <pre>
 *   03 &lt;udpId LE16&gt; 1f &lt;localId LE16&gt; 38 04 &lt;entityType u8&gt;
 *      &lt;Location u32 LE&gt; &lt;Entity u16 LE&gt;
 * </pre>
 *
 * <p>{@link PacketBuilderUDP1303} already writes the
 * {@code [0x13][ctr][ctr+sk][len][0x03][seq LE2]} reliable frame, so
 * this builder writes only the body that follows the {@code 0x03 seq}
 * pair, starting at {@code 0x1f}:
 *
 * <pre>
 *   1f &lt;localId LE16&gt; 38 04 &lt;entityType u8&gt;
 *      &lt;Location u32 LE&gt; &lt;Entity u16 LE&gt;
 * </pre>
 *
 * <p>Carries <strong>no coordinates</strong>. The server commits
 * {@code MISC_LOCATION = Location} but does NOT compute the
 * destination XYZ; the client self-positions from the
 * {@code Entity}/insertion-point index against the destination
 * zone's own {@code .dat} (doc §4).
 *
 * <p><strong>{@code entityType} byte.</strong> The doc (§2e) calls
 * this "entityType"; TinNS's {@code BuildChangeLocationMsg}
 * parameter is {@code nEntityType} but the {@code case 18/20/29}
 * call site passes {@code SewerLevel = (ft==20||ft==29)?1:0} (NOT
 * the appplaces SewerLevel field — that is TinNS debug-only).
 * {@code case 15} HOLOMATCH EXIT passes {@code 0}. We follow the
 * TinNS source and let the caller pass the function-type-derived
 * value via {@link server.gameserver.PortalResolver.Portal#entityTypeByte}.
 *
 * @see server.gameserver.PortalResolver
 */
public class ChangeLocation extends PacketBuilderUDP1303 {

    /**
     * @param pl         the crossing player (supplies the reliable
     *                   seq frame and the {@code localId} =
     *                   {@code pl.getMapID()}, matching TinNS
     *                   {@code GetLocalID()}).
     * @param location   destination worldId (appplaces ExitWorldID).
     * @param entity     destination insertion-point / entity index
     *                   (appplaces ExitWorldEntity); resolved by the
     *                   client against the destination {@code .dat}.
     * @param entityType the byte after {@code 0x04}; TinNS
     *                   {@code (ft==20||ft==29)?1:0} (HOLOMATCH = 0).
     */
    public ChangeLocation(Player pl, int location, int entity,
                          int entityType) {
        super(pl);
        write(0x1f);
        writeShort(pl.getMapID());     // localId LE16 (TinNS GetLocalID)
        write(0x38);
        write(0x04);                   // "Accepted (?)" — TinNS const
        write(entityType & 0xFF);      // SewerLevel/entityType byte
        writeInt(location);            // Location u32 LE
        writeShort(entity & 0xFFFF);   // Entity u16 LE
    }
}
