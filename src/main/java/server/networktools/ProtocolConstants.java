/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package server.networktools;

/**
 *
 * @author javier
 */
public class ProtocolConstants {
    public static int HEADERID_INGAME = 0x13;
    public static int HEADERID_FE = 0xfe;
    public static int HEADERID_REQUEST_SYNC1 = 0x01;
    public static int HEADERID_REQUEST_SYNC2= 0x03;
    public static int HEADERID_UDP_KEEPALIVE= 0x04;
    public static int HEADERID_CLIENT_LOGOUT = 0x08;

    public static int PACKET_FETYPE_ZONENAME=0x830c;

    /*
     * Protocol reference (from Wireshark dissector):
     *
     * FE packets:
     *   0x8001 = Handshake
     *   0x8000 = Handshake Response
     *   0x8003 = Handshake2
     *   0x8480 = Authentication
     *   0x8381 = Authentication OK
     *   0x8482 = Request Server-/Charlist
     *   0x8383 = Serverlist
     *   0x8385 = Charlist
     *   0x8737 = Request Gamedata
     *   0x873a = Gamedata
     *   0x873c = Request Gameinfo
     *   0x8305 = Gameinfo
     *   0x8386 = Request Answer
     *   0x830d = Gameinfo Ready
     *   0x8301 = Gameserver Authentication
     *   0x7b00 = Get Patchserver Version
     *   0x3702 = Patchserver Version
     *   0x8317 = Custom Chat
     *   0x8318 = Direct chat failed - user not online
     *   0x8303 = Client kicked
     *
     * Ingame packets:
     *   0x0c = Sync2
     *   0x1b = NPCUpdate
     *   0x1f = Team Stuff
     *   0x20 = Movement
     *   0x27 = Subway
     *   0x2a = StartPos
     *   0x2d = PlayerTarget
     *
     * 1F sub-packets:
     *   0x01 = Shoot
     *   0x1b = Local Chat
     *   0x4c = C.Channels
     *   0x02 = Jump
     *   0x16 = Death
     *   0x17 = Use
     *   0x18 = NPCScript
     *   0x19 = Dialog
     *   0x1a = NPCResponse
     *   0x1e = ItemMove
     *   0x1f = SlotUse
     *   0x22 = ExitChair
     *   0x27 = CloseCon
     *   0x29 = Hack Succ
     *   0x2c = Hack Fail
     *   0x2e = Outfitter
     *   0x2f = GenRep
     *   0x30 = HLT Update
     *   0x31 = Use Denied
     *   0x33 = ChatList
     *   0x38 = WorldAccess
     *   0x3b = OtherChat
     *   0x3d = QuickCmd
     *   0x3e = TradeSetting
     *   0x40 = JoinTeam
     *   0x4e = PlayerTrade
     *
     * 25 sub-packets:
     *   0x01 = Start Processor
     *   0x03 = Stop Processor
     *   0x04 = Increase a subskill
     *   0x06 = Skillboost
     *   0x07 = Use a booster
     *   0x0b = Level Up
     *   0x0c = Switch a weapon mod
     *   0x11 = Update money counter in active trade window
     *   0x13 = Confirmation/Management
     *   0x14 = Move item in inventory
     *   0x16 = Start Reloading
     *   0x17 = Combine two items in inventory
     *   0x18 = RPOS command
     *   0x19 = Main Skills
     *   0x1f = Synaptic Impairment
     *   0x25 = Stop Reloading
     *
     * 03 sub-packets:
     *   0x01 = Out-Of-Order
     *   0x07 = Multipart
     *   0x08 = Zoning End
     *   0x0d = TimeSync
     *   0x1b = 0x03 1B Group
     *   0x1f = Gaming Packets
     *   0x22 = Chars/Clans/Map Stuff Request
     *   0x23 = Chars/Clans/Map Stuff Response
     *   0x25 = Player info in zone
     *   0x26 = Remove WorldItem
     *   0x27 = Request Info About WorldID
     *   0x28 = Info About WorldID
     *   0x2b = CityCom
     *   0x2c = Start Position Response
     *   0x2d = NPC Stuff (initial data)
     *   0x2e = Weather change
     *   0x2f = Update model
     *   0x30 = Short Playerinfo
     *   0x31 = Request Short Playerinfo
     *
     * 22 sub-packets:
     *   0x03 = Zoning2
     *   0x06 = InfoQuery
     *   0x0b = Ent.Pos.Req
     *   0x0d = Zoning1
     *
     * 2B sub-packets:
     *   0x17 = ReceiveDB
     *   0x18 = UpdateDB
     *   0x19 = TryAccess
     *   0x1b = QueryDB
     *   0x1f = Terminal
     *
     * 1B sub-packets:
     *   0x19 = AddWorldItem
     */
}
