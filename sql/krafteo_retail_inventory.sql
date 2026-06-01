-- Retail-Krafteo inventory replication  (decoded from /tmp/retail_equip.pcap, CharInfo 0x03/0x07 disc=0x01)
-- Krafteo = player_characters.id=2 ;  F2 container_id=1 ;  QB container_id=3
-- Run with the server STOPPED.  Idempotent (DELETE then INSERT).
-- Plain items edits only; if inventory_view errors afterward just recreate that view.

BEGIN;

-- 1) Remove Krafteo's current F2(1)+QB(3) items and their mod-slot rows.
DELETE FROM public.item_mod_slot WHERE item_id IN (SELECT id FROM public.items WHERE container_id IN (1,3));
DELETE FROM public.items WHERE container_id IN (1,3);

-- 2) Insert retail-decoded items.
--    Stat defaults: curr_cond/max_cond=255, dmg/freq/handling/range=200, ammo_uses=3, stack=5.
--    Weapons (390/518/526) get clip_size/ammo_uses from defs.weapons and flags=6 (USES|WEAPON).
--    flags: 3=USES|STACK, 6=USES|WEAPON, 128=SIMPLE (all Ceres-serializable in createNetworkInfoData).
INSERT INTO public.items
  (id, container_id, type_id, flags, quality, curr_cond, max_cond, damage,
   frequency, handling, range, clip_size, ammo_uses, stack_count, mod_slots,
   mod_slots_used, constructor_char_id, slot_index, slot_x, slot_y)
VALUES
  (29, 1, 35, 3, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 4, 3),  -- F2 Medical Kit
  (30, 1, 318, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 0),  -- F2 Stamina Booster
  (31, 1, 825, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 3, 0),  -- F2 Milky Ren
  (32, 1, 1499, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 5, 5),  -- F2 Cryton RRN-80 Remote Repair Nanites
  (33, 1, 63, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 6, 0),  -- F2 Tangent Laserpointer Weapon enhancement
  (34, 1, 1988, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 5, 1),  -- F2 Big Pack of Construction Grease
  (35, 1, 826, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 5, 0),  -- F2 Rezas Irata Power Snack
  (36, 1, 2300, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 1),  -- F2 LAW Enforcer #1
  (37, 1, 79, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 4, 0),  -- F2 Choc Chogger
  (38, 1, 1989, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 3, 1),  -- F2 Big Pack of Catalytic Recycling Conversion Fluid
  (39, 1, 1507, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 7, 1),  -- F2 BLUEPRINT- 30 TB Capacity
  (40, 1, 1507, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 6, 2),  -- F2 BLUEPRINT- 30 TB Capacity
  (41, 1, 5801, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 1, 6),  -- F2 Tangent Armorpart 1
  (42, 1, 1982, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 1, 1),  -- F2 Implant Disinfection Gel
  (43, 1, 1507, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 4, 2),  -- F2 BLUEPRINT- 30 TB Capacity
  (44, 1, 1507, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 4, 1),  -- F2 BLUEPRINT- 30 TB Capacity
  (45, 1, 1967, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 0, 7),  -- F2 Cryton CD-TL90 Construction Device
  (46, 1, 1507, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 2),  -- F2 BLUEPRINT- 30 TB Capacity
  (47, 1, 353, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 4, 6),  -- F2 Unlabeled 'Atomsmelt' Cannon
  (48, 1, 1931, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 0, 3),  -- F2 Cryton SD-TL75 Salvage Device
  (49, 1, 1497, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 3),  -- F2 Cryton SD-TL30 Salvage Device
  (50, 1, 1988, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 0, 5),  -- F2 Big Pack of Construction Grease
  (51, 1, 1501, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 1, 0),  -- F2 Empty Datacube
  (52, 1, 1507, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 7, 2),  -- F2 BLUEPRINT- 30 TB Capacity
  (53, 1, 4495, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 6),  -- F2 NExT Mission Item Temp Sub-Part 2
  (54, 1, 4494, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 1, 5),  -- F2 NExT Mission Item Temp Sub-Part 1
  (55, 1, 4505, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 6, 5),  -- F2 NExT Mission Item Temp Sub-Part 12
  (56, 1, 3384, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 6, 1),  -- F2 9mm Clip - 'Amazon' converter mod
  (57, 1, 3381, 3, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 7, 0),  -- F2 9mm Clip - 'Amazon'
  (58, 1, 1002, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 0, 0),  -- F2 Wood
  (59, 1, 2310, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 6, 3),  -- F2 Crahn 'Novice' Gauntlet
  (60, 1, 2236, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 0, 1),  -- F2 Construction Coordinator v1
  (61, 1, 1837, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 8),  -- F2 Light Energyfield Belt
  (62, 1, 293, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 0, 2),  -- F2 Cryton's Damaged Head Bone
  (63, 1, 1860, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 5, 8),  -- F2 Crahn Shelter Vest
  (64, 3, 390, 6, 0, 255, 255, 200, 200, 200, 200, 16, 1, 5, 0, 0, 0, 0, 0, 0),  -- QB Lazar Gun
  (65, 3, 81, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 2, 0),  -- QB Halogen Flashlight
  (66, 3, 36, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 9, 0),  -- QB First Aid
  (67, 3, 1947, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 12, 0),  -- QB Cryton RD-TL90 Recycling Device
  (68, 3, 1008, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 13, 0),  -- QB Wire Junk
  (69, 3, 1001, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 14, 0),  -- QB Computer Junk
  (70, 3, 1008, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 15, 0),  -- QB Wire Junk
  (71, 3, 1008, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 16, 0),  -- QB Wire Junk
  (72, 3, 1001, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 18, 0),  -- QB Computer Junk
  (73, 3, 1001, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 19, 0),  -- QB Computer Junk
  (74, 3, 1001, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 20, 0),  -- QB Computer Junk
  (75, 3, 1008, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 21, 0),  -- QB Wire Junk
  (76, 3, 1001, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 22, 0),  -- QB Computer Junk
  (77, 3, 3160, 3, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 24, 0),  -- QB 9mm Clip
  (78, 3, 294, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 35, 0),  -- QB Cryton's Damaged Chest Bone
  (79, 3, 295, 128, 0, 255, 255, 200, 200, 200, 200, 0, 3, 5, 0, 0, 0, 0, 36, 0),  -- QB Cryton's Damaged Arm Bone
  (80, 3, 518, 6, 0, 255, 255, 200, 200, 200, 200, 400, 3, 5, 0, 0, 0, 0, 37, 0),  -- QB HEW Plasma Assault Drone PL-4
  (81, 3, 526, 6, 0, 255, 255, 200, 200, 200, 200, 160, 1, 5, 0, 0, 0, 0, 38, 0);  -- QB HEW Raygun Battle Drone RG-1

COMMIT;
