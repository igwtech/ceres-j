-- =====================================================================
-- 2026-06-01  items: opaque tokens/slot  ->  named relational columns
-- =====================================================================
-- Replaces the opaque items.tokens BYTEA blob (17 LE16 shorts) and the
-- packed items.slot INTEGER with named, editable/validatable columns,
-- plus an item_mod_slot side table for the five mod values.
--
-- WIRE-SAFE: the server's in-memory short[17] tokens representation and
-- every packet serializer (Item.createNetworkInfoData / getItemInfoPacketData,
-- EquipStateAck / CharInfo builders) are UNCHANGED. ItemManager simply
-- reassembles the identical short[17] + packed inventorypos from these
-- named columns on load, so the wire output stays byte-identical.
--
-- IDEMPOTENT: safe to run multiple times. Each step is guarded with
-- IF EXISTS / IF NOT EXISTS or a column-existence check.
--
-- tokens index -> column:
--   0 curr_cond     1 max_cond      2 damage         3 frequency
--   4 handling      5 range         6 clip_size      7 ammo_uses
--   8 stack_count   9 mod_slots    10 mod_slots_used 11..15 mod1..mod5
--  16 constructor_char_id
--  (each token is a little-endian 16-bit value: byte[2*i] | byte[2*i+1]<<8)
--
-- slot packing:  slot = slot_x + slot_y*256 + slot_index*65536
--   slot_index = slot / 65536
--   slot_y     = (slot % 65536) / 256
--   slot_x     =  slot % 256
--
-- NOTE: do NOT apply against the live DB without review. The human will
-- run this after reviewing. The inventory_view (+ its trigger) is dropped
-- below because it referenced items.tokens/items.slot; it is intentionally
-- NOT recreated here — recreate it against the new columns:
--   curr_cond, max_cond, damage, frequency, handling, range, clip_size,
--   ammo_uses, stack_count, mod_slots, mod_slots_used, constructor_char_id,
--   slot_index, slot_x, slot_y  (+ item_mod_slot for mod1..mod5).
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 0. Drop the inventory_view + its trigger (they reference the columns
--    we are about to remove). The human recreates the view post-review.
-- ---------------------------------------------------------------------
DROP TRIGGER IF EXISTS inventory_view_trigger ON inventory_view;
DROP TRIGGER IF EXISTS inventory_view_iud ON items;
DROP VIEW IF EXISTS inventory_view CASCADE;

-- ---------------------------------------------------------------------
-- 1. ADD the named columns (idempotent).
-- ---------------------------------------------------------------------
ALTER TABLE items ADD COLUMN IF NOT EXISTS curr_cond           INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS max_cond            INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS damage              INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS frequency           INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS handling            INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS range               INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS clip_size           INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS ammo_uses           INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS stack_count         INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS mod_slots           INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS mod_slots_used      INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS constructor_char_id INTEGER  DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS slot_index          SMALLINT DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS slot_x              SMALLINT DEFAULT 0;
ALTER TABLE items ADD COLUMN IF NOT EXISTS slot_y              SMALLINT DEFAULT 0;

-- ---------------------------------------------------------------------
-- 2. BACKFILL the named columns from the legacy tokens/slot.
--    get_byte(tokens, n) is 0-indexed; each token i is the LE16
--    get_byte(2*i) | get_byte(2*i+1) << 8. Guarded so re-runs after the
--    DROP in step 5 are no-ops.
-- ---------------------------------------------------------------------
DO $mig$
BEGIN
  IF EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'items' AND column_name = 'tokens')
  THEN
    UPDATE items SET
      curr_cond      = COALESCE(get_byte(tokens,  0) | (get_byte(tokens,  1) << 8), 0),
      max_cond       = COALESCE(get_byte(tokens,  2) | (get_byte(tokens,  3) << 8), 0),
      damage         = COALESCE(get_byte(tokens,  4) | (get_byte(tokens,  5) << 8), 0),
      frequency      = COALESCE(get_byte(tokens,  6) | (get_byte(tokens,  7) << 8), 0),
      handling       = COALESCE(get_byte(tokens,  8) | (get_byte(tokens,  9) << 8), 0),
      range          = COALESCE(get_byte(tokens, 10) | (get_byte(tokens, 11) << 8), 0),
      clip_size      = COALESCE(get_byte(tokens, 12) | (get_byte(tokens, 13) << 8), 0),
      ammo_uses      = COALESCE(get_byte(tokens, 14) | (get_byte(tokens, 15) << 8), 0),
      stack_count    = COALESCE(get_byte(tokens, 16) | (get_byte(tokens, 17) << 8), 0),
      mod_slots      = COALESCE(get_byte(tokens, 18) | (get_byte(tokens, 19) << 8), 0),
      mod_slots_used = COALESCE(get_byte(tokens, 20) | (get_byte(tokens, 21) << 8), 0),
      -- tokens[11..15] mod1..mod5 -> item_mod_slot (step 4)
      constructor_char_id = COALESCE(get_byte(tokens, 32) | (get_byte(tokens, 33) << 8), 0)
    WHERE tokens IS NOT NULL AND octet_length(tokens) = 34;
  END IF;

  -- Decode the packed slot int into its three dimensions.
  IF EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'items' AND column_name = 'slot')
  THEN
    UPDATE items SET
      slot_index = (COALESCE(slot,0) / 65536),
      slot_y     = ((COALESCE(slot,0) % 65536) / 256),
      slot_x     = (COALESCE(slot,0) % 256);
  END IF;
END
$mig$;

-- ---------------------------------------------------------------------
-- 3. CREATE the item_mod_slot side table (idempotent).
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS item_mod_slot (
    item_id   INTEGER NOT NULL,
    slot_no   INTEGER NOT NULL,
    mod_value INTEGER NOT NULL,
    PRIMARY KEY (item_id, slot_no)
);

-- ---------------------------------------------------------------------
-- 4. BACKFILL item_mod_slot from tokens[11..15] (non-zero mods only).
--    token i bytes start at 2*i: mod1@22, mod2@24, mod3@26, mod4@28,
--    mod5@30. Stored signed (NC mod values may be negative): interpret
--    the LE16 as a signed 16-bit int. Re-runnable via ON CONFLICT.
-- ---------------------------------------------------------------------
DO $mods$
BEGIN
  IF EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema = current_schema()
        AND table_name = 'items' AND column_name = 'tokens')
  THEN
    INSERT INTO item_mod_slot (item_id, slot_no, mod_value)
    SELECT id, m.slot_no, s.val
    FROM items i
    CROSS JOIN LATERAL (
        VALUES
          (1, get_byte(i.tokens, 22) | (get_byte(i.tokens, 23) << 8)),
          (2, get_byte(i.tokens, 24) | (get_byte(i.tokens, 25) << 8)),
          (3, get_byte(i.tokens, 26) | (get_byte(i.tokens, 27) << 8)),
          (4, get_byte(i.tokens, 28) | (get_byte(i.tokens, 29) << 8)),
          (5, get_byte(i.tokens, 30) | (get_byte(i.tokens, 31) << 8))
    ) AS m(slot_no, raw)
    CROSS JOIN LATERAL (
        -- reinterpret the unsigned LE16 as a signed 16-bit value
        SELECT CASE WHEN m.raw >= 32768 THEN m.raw - 65536 ELSE m.raw END AS val
    ) AS s
    WHERE i.tokens IS NOT NULL
      AND octet_length(i.tokens) = 34
      AND m.raw <> 0
    ON CONFLICT (item_id, slot_no) DO UPDATE
      SET mod_value = EXCLUDED.mod_value;
  END IF;
END
$mods$;

-- ---------------------------------------------------------------------
-- 5. DROP the legacy opaque columns.
-- ---------------------------------------------------------------------
ALTER TABLE items DROP COLUMN IF EXISTS tokens;
ALTER TABLE items DROP COLUMN IF EXISTS slot;

-- ---------------------------------------------------------------------
-- 6. DROP the dead reference tables (item type metadata lives in the
--    separate item_type table, populated from defs\items.def).
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS item_defs;
DROP TABLE IF EXISTS item_containers;

COMMIT;
