Irata
---------
Irata is a Neocron 2 Server Emulator

License
-------
This project ist licensed under the GPLv2. See license.txt for details.

InGame Commands
---------------
The commands all follow the same scheme: "./commandname [optional arguments]".
You have to send them in the local Chat withoud the "".
If you want to add new commands, you just have to look into the 
server.gameserver.packets.client_udp.LocalChat class and edit the corresponding
method.
The following commands are currently implemented: (experimental)
	- "item itemid;" the itemid may be looked up in items.def
	- "npc x;y;z;type;hp;armor;"  the type may be looked up in npc.def
	- "pos" tells you your current position
	- "zone zoneid" where zoneid is the id of the zone you want to go to, you can
	find the ids of the zones in the world.ini file somewhere in your nc2 directory

Project Members
---------------
MrsNemo		mrsnemo@gmx.de		initiator, developer, management
r2d22k							developer

Thanks to
---------
the authors and helpers of the projects NeoPolis and TinNS, as their work was a
big help to get started

Webpages
--------
http://sourceforge.net/projects/irata/		home of Irata
http://forum.linux-addicted.org/			TinNS, NeoPolis and old Irata forums
http://sourceforge.net/projects/javacsv/	our "Database" driver

Legal notice
------------
All trademarks are the property of their respective owners.