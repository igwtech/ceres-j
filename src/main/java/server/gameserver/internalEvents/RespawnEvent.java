package server.gameserver.internalEvents;

import server.database.playerCharacters.PlayerCharacter;
import server.gameserver.Player;
import server.gameserver.packets.server_udp.ForcedZoning;
import server.gameserver.packets.server_udp.LocalChatMessage;
import server.tools.Out;
import server.tools.Timer;

/**
 * Respawns the player at their current zone's start position after a
 * delay. Restores HP/PSI/STA to max and sends a ForcedZoning to the
 * same zone (which reloads the client's world state).
 */
public class RespawnEvent extends DummyEvent {

    public static final long RESPAWN_DELAY_MS = 3000;

    public RespawnEvent() {
        this.eventTime = Timer.getRealtime() + RESPAWN_DELAY_MS;
    }

    @Override
    public void execute(Player pl) {
        if (pl == null || pl.getCharacter() == null) return;

        PlayerCharacter pc = pl.getCharacter();

        // Restore pools
        pc.setHealth(pc.getMaxHealth());
        pc.setPsi(pc.getMaxPsi());
        pc.setStamina(pc.getMaxStamina());

        Out.writeln(Out.Info, "RespawnEvent: " + pc.getName()
                + " respawned in zone " + pl.getMapID());

        // Teleport back to same zone (forces reload)
        pl.send(new ForcedZoning(pl, pl.getMapID()));
        pl.send(new LocalChatMessage(pl, "[Server] You have been respawned.", 2));
    }
}
