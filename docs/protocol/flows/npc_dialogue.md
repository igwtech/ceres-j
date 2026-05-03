# Flow: NPC dialogue

**Status:** stub  
**Backing captures:** `RETAIL_ZONING_AND_ITEMS_LONG_20260502_010613`
— markers `NPC_TALK`, `NPC_TALK2`, `NPC_TALK3`.

## Scenario

Player approaches an NPC, opens conversation, possibly cycles through
dialogue options, and closes the dialogue.

## Marker correlation

All three NPC_TALK markers in the long capture correlate with:

- `UDP S->C 0x32` (heavy bursts) — likely the dialogue text /
  topic packet
- `UDP C->S 0x32` — client's dialogue selection
- `UDP S->C 0x03/0x32` (`Reliable/?`) — reliable variant
- `UDP S->C 0x1b` (background position updates, not flow-specific)

## Phases

_TODO._

## Open questions

- `0x32` carries what payload? Looks like dialogue text + option
  list; samples in catalog are `32fb03000000c84100`.
- Why does `0x32` appear both wrapped in `0x03` (reliable) and
  raw? Are these alternate transports for the same content?

## Related catalog entries

- `UDP C->S 0x32`
- `UDP S->C 0x32`
- `UDP S->C 0x03/0x32`
