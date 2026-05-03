# Flow: Sewer traversal (interior zone, verticality)

**Status:** stub  
**Backing captures:**
- `RETAIL_AUGUSTO_20260426_201952` — markers
  `BEFORE_ENTER_SEWER`, `AFTER_ENTER_SEWER`,
  `BEFORE_EXIT_SEWER`, `BEFORE_KILL_MOB23`, etc.

## Scenario

Player descends into a sewer interior (typically via a manhole or
ladder) and re-emerges. The interior is a separate zone from the
surface — testing whether sewer transitions follow the same
TCP `0x830d`/`0x830c` path as outdoor zone walks, or use a
different mechanism.

## Phases

_TODO: walk the capture and check whether sewer entry shows
`TCP S->C 0x830d`/`0x830c` like outdoor zone changes._

## Open questions

- Are interior zones distinguished at the protocol level?
- Vertical-axis-only transition (e.g. ladder up) — does it
  trigger the same packets as horizontal walking?

## Related catalog entries

See [zone_walk_same_district.md](zone_walk_same_district.md).
