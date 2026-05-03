# Flow: Vendor buy (cash + inventory update)

**Status:** stub  
**Backing captures:**
- `RETAIL_CASH_VENDOR_PCAP_FRESH_20260426_143918` — markers
  `BEFORE_BUY`, `AFTER_BUY`.
- `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613` — markers
  `NPC_VENDOR_OPEN`, `NPC_VENDOR_SELL`, `NPC_VENDOR_CLOSE`.

## Scenario

Player walks up to an NPC vendor, opens the trade window, selects
an item, confirms purchase. Server deducts cash and adds the item
to the player's inventory.

## Cash carrier (already known)

`UDP S->C 0x03/0x1f` sub-tag `0x04` is the wallet update — verified
2026-04-26 against retail HUD on 3 mob kills. See
[`packets/udp_s2c_03_1f.md`](../packets/udp_s2c_03_1f.md) for the
breakdown.

Vendor-buy variant (~41 bytes) is the same packet with ~30 bytes
of item-receipt appended.

## Phases (high-level)

1. **Vendor window open** — `UDP S->C 0x32` / `0x03/0x32`.
2. **Item selection (client)** — `UDP C->S 0x32` carrying item ID.
3. **Server-side validation** — likely happens silently.
4. **Cash + inventory update (server)** —
   `UDP S->C 0x03/0x1f` sub-tag `0x04` (cash) + item-receipt
   trailer.
5. **Window close** — `UDP C->S 0x32` close, server stops
   sending vendor stream.

## Open questions

- Item-receipt trailer byte format: 30B observed but not yet decoded.
- What does the client send to confirm the purchase before the
  server commits? Is there an ACK/round-trip?

## Related catalog entries

- `UDP S->C 0x03/0x1f` GamePackets (cash carrier)
- `UDP S->C 0x32` / `UDP C->S 0x32` (vendor stream)
- `UDP S->C 0x03/0x32` (reliable variant)
