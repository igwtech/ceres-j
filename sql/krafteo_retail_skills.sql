-- krafteo_retail_skills.sql
--
-- Set Ceres-J's Krafteo (player_characters.id = 2) to the EXACT skill values
-- decoded from a retail NC2 CharInfo capture (/tmp/retail_equip.pcap, harness
-- logged into RETAIL as msn3wolf -> Krafteo).
--
-- Source: multipart 0x03/0x07 CharInfo chain (3 fragments, disc 0x01,
-- seqs 0x0a/0x0b/0x0c -> 957B reassembled).
--   Section 2 (pools)  -> HP 598/598, PSI 7/7, STA 151/151 (informational only;
--                         pool maxes are subskill-derived on the client, so they
--                         are NOT written here).
--   Section 3 (mains)  -> per-entry [level u8][SP_avail u16][XP u32][rate u8][cap u8].
--   Section 4 (subs)   -> 4B header (2e 02 00 01) + 45 x (value u8, rank u8).
--                         DB stores only the per-subskill VALUE (level); the
--                         rank/PtsPerLvl byte is recomputed by the server
--                         (PlayerCharacter.getSubskillPtsPerLvl), so it is not stored.
--
-- Idempotent: single UPDATE, safe to re-run. NOT applied automatically.
-- Apply with the server STOPPED (in-memory char cache will otherwise clobber it),
-- e.g.:  docker exec -i neocron-postgres psql -U ceres -d ceres < krafteo_retail_skills.sql

UPDATE player_characters SET
  -- Main attributes (Section 3): <attr>_lvl = leveled value, <attr>_pts = SP available
  str_lvl = 36,  str_pts = 170,   -- STR  lvl 36, 170 SP (xp 311378, cap 40)
  dex_lvl = 39,  dex_pts = 130,   -- DEX  lvl 39, 130 SP (xp 433056, cap 100)
  con_lvl = 30,  con_pts = 40,    -- CON  lvl 30,  40 SP (xp 153880, cap 40)
  int_lvl = 40,  int_pts = 0,     -- INT  lvl 40,   0 SP (xp 470661, cap 100)
  psi_lvl = 20,  psi_pts = 95,    -- PSI  lvl 20,  95 SP (xp 148851, cap 20)

  -- Subskills (Section 4): each = decoded VALUE byte (level). rank byte is derived.
  mc  = 0,    -- Melee Combat            (slot 1,  val 0)
  hc  = 0,    -- Heavy Combat            (slot 2,  val 0)
  tra = 8,    -- Transport               (slot 3,  val 8)
  pcr = 0,    -- Pistol Combat           (slot 6,  val 0)
  pc  = 20,   -- Projectile Combat       (slot 10, val 20)
  rc  = 0,    -- Rifle Combat            (slot 11, val 0)
  tc  = 0,    -- Tech Combat             (slot 12, val 0)
  vhc = 0,    -- Vehicle Combat          (slot 13, val 0)
  agl = 8,    -- Agility                 (slot 14, val 8)
  rep = 12,   -- Repair                  (slot 15, val 12)
  rec = 50,   -- Recycle                 (slot 16, val 50)
  rcl = 0,    -- Recycle (cl?)           (slot 17, val 0)
  atl = 55,   -- Athletics               (slot 20, val 55)
  "end" = 10, -- Endurance               (slot 21, val 10) -- quoted: END is reserved
  "for" = 2,  -- Force                   (slot 22, val 2)  -- quoted: FOR is reserved
  fir = 0,    -- Fire resist             (slot 23, val 0)
  enr = 0,    -- Energy resist           (slot 24, val 0)
  xrr = 0,    -- X-ray resist            (slot 25, val 0)
  por = 0,    -- Poison resist           (slot 26, val 0)
  hlt = 50,   -- Health                  (slot 27, val 50)
  hck = 0,    -- Hacking                 (slot 30, val 0)
  brt = 0,    -- Barter                  (slot 31, val 0)
  psu = 0,    -- PSI Use                 (slot 32, val 0)
  wep = 18,   -- Weapon Lore             (slot 33, val 18)
  cst = 112,  -- Construction            (slot 34, val 112)
  res = 0,    -- Research                (slot 35, val 0)
  imp = 0     -- Implant                 (slot 36, val 0)
WHERE id = 2;
