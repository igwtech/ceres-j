# CharInfo Multipart Stream — Decoded Reference

CharInfo is the large multi-section payload sent at world-entry time. It
arrives at the client as a chain of `0x03 → 0x07` reliable multipart
fragments. After reassembly, it begins with the 3-byte header
`22 02 01` followed by length-prefixed sections.

This document captures byte-level offsets that have been validated against
four retail captures (`nc2_strace_RETAIL_ACC1_CHAR1`, `_ACC1_CHAR2`,
`_ACC2_CHAR1`, `_ACC2_CHAR2`), the long retail death capture, and our
current Ceres-J output. The validating tools live in `tools/`:

- `tools/charinfo_decode_full.py` — section-aware annotated dump
- `tools/charinfo_section2_field_audit.py` — per-field comparison across
  all retail captures (identifies literal vs. character-derived fields)
- `tools/charinfo_section_compare.py` — section-size diff retail vs ours

## Top-level layout

```
[22 02 01]                              3-byte stream header
[section_1_data ...]                    no leading marker — see Section 1
[section_id(1)][size_lo(1)][size_hi(1)] section marker
[section_data ...]                      `size` bytes
... repeat ...
```

Section ID order observed in retail and Ceres-J:
`1, 2, 3, 4, 5, 6, 7, 12, 8, 9, 10, 11, 13`. (Note the
`newSection(0x0c)` insertion between 7 and 8 in `CharInfo.java`.)

---

## Section 1 — header / identity (10 bytes)

```
+0  byte   0xfa                literal
+1  byte   profession          1..N
+2  word   transaction id      LE16, low half is incremented x3 in CharInfo.java
+4  dword  char id             LE32
+8  word   17                  literal (NCE 2.5)
```

| capture            | profession | char id |
|--------------------|-----------:|--------:|
| RETAIL_ACC1_CHAR1  |       0x24 |   99930 |
| RETAIL_ACC2_CHAR1  |       0x24 |   99930 |
| RETAIL_ACC2_CHAR2  |       0x24 |   99930 |
| RETAIL_DEATH       |       0x24 |   99930 |
| Ceres-J Asddf      |       0x0a |       2 |

Note: all four retail captures were of the **same character** (Tank-class
PE, char id 99930), so the test set for "profession varies" is one-deep.
The format matches our implementation in `CharInfo.java:18-30`.

---

## Section 2 — pools and synaptic (28 bytes)

```
+0  byte   0x04                literal header
+1  byte   0x04                literal header
+2  word   cur HP              LE16
+4  word   max HP              LE16
+6  word   cur PSI             LE16
+8  word   max PSI             LE16
+10 word   cur STA             LE16
+12 word   max STA             LE16
+14 word   255                 literal
+16 word   255                 literal
+18 word   ~0.351 * max_HP     **NOT** literal 101 — character-derived
+20 word   ~? * max_PSI        **NOT** literal 101 — character-derived
+22 word   ~? * max_STA        **NOT** literal 101 — character-derived
+24 byte   100                 literal (NOT synaptic — see below)
+25 byte   128                 literal
+26 word   0                   padding
```

### Field audit across retail captures

```
capture                        cur_HP max_HP cur_PSI max_PSI cur_STA max_STA  f@18  f@20  f@22  f@24  f@25
RETAIL_ACC1_CHAR1                719    719      81      81     169     169   252   324   144   100   128
RETAIL_ACC2_CHAR1                396    396      25      25     140     140   139   179    80   100   128
RETAIL_ACC2_CHAR2               1067   1067       0       0     214     214   374   481   214   100   128
```

Findings:
- `cur_HP == max_HP` in every retail capture (character was at full HP
  when the snapshot was taken).
- `f@18 / max_HP ≈ 0.350` in **all** captures (252/719, 139/396, 374/1067
  all round to 0.350). This is a derived field, not a constant.
- `f@20` and `f@22` correlate weakly to PSI/STA but the formulas vary;
  more captures with different chars are needed to determine.
- `f@24` is **always 100**, not the character's synaptic stat.
  `CharInfo.java:47` writes `pc.getSynaptic()` here — when synaptic
  differs from 100 we'll diverge from retail. Possibly a bug, possibly
  retail uses a different field.
- `f@25` (literal 128 / 0x80) matches.
- `f@26 / f@27` always zero padding.

### Current Ceres-J bug

`CharInfo.java:42-46` writes `101` (0x65) to `f@18..f@22`. Retail values
are character-derived (~35% of max_HP for f@18; formulas for f@20/f@22
TBD). Until the formulas are reverse-engineered, sending wrong values
here may cause the client's character system to compute wrong derived
stats — though it does NOT obviously affect HP-bar display.

---

## Section 3 — primary skills (60 bytes)

```
+0 byte   0x06                  count = 6 skills
+1 byte   0x09                  bytes per skill
+2 ... 6 × { lvl(1) | pts_LE16 | xp_LE32 | rate(1) | max(1) }
+56 4 bytes WoC trailer (lvl, skill_id, 0x00, 0x00)
```

Skill order: STR, DEX, CON, INT, PSI, X (unknown 6th skill).

WoC (Way of the Core) trailer bytes vary per character; in retail it
encodes the character's WoC skill.

### Skill maxes from retail (ACC2_CHAR1)

| Skill | lvl | pts | xp     | rate | max |
|-------|----:|----:|-------:|-----:|----:|
| STR   |   0 |   0 |      0 |  0x00|  1  |
| DEX   |  36 | 170 | 311140 |  0x30|  40 |
| CON   |  39 | 130 | 431061 |  0x08| 100 |
| INT   |  30 |  94 | 149361 |  0x30|  40 |
| PSI   |  40 |   0 | 468720 |  0x08| 100 |
| X???  |  20 |  95 | 148851 |  0x40|  20 |

Note STR has lvl=0 with max=1 — likely class-locked.

---

## Section 4 — sub-skills (94 bytes)

```
+0 byte   0x2e                count = 46 (0x2e) subskill slots
+1 byte   0x02                bytes per subskill
+2 ... 46 × { lvl(1) | pts(1) }
```

Subskill index → name mapping (from `PlayerCharacter.SUBSKILLS`):

```
 0: null         16: rec        32: psu
 1: mc           17: rcl        33: wep
 2: hc           18: null       34: cst
 3: tra          19: null       35: res
 4..9: null      20: atl        36: imp
10: pc           21: end        37..39: null
11: rc           22: for        40: ppu
12: tc           23: fir        41: apu
13: vhc          24: enr        42: mst
14: agl          25: xrr        43: ppw
15: rep          26: por        44: psr
                 27: hlt        45: wpw
                 28..29: null
                 30: hck
                 31: brt
```

Slots 0, 4-9, 18-19, 28-29, 37-39 are `null` placeholders. Verified
working in our packet — Asddf's DB columns (mc=0, hc=0, tra=5, pc=0,
rc=40, tc=10, ..., hlt=8, ...) appear at the correct slot indexes.

---

## Section 5 — F2 inventory (variable, retail typical 800-1000 bytes)

```
+0 word   item_count          LE16
+2 ... per-item entries:
        +0 word   per-item length (excludes its own length prefix)
        +2 byte   0x00
        +3 byte   posX                inventory grid X
        +4 byte   posY                inventory grid Y
        +5 ...   NetworkInfoData      (per-item-type variable)
```

Retail ACC2_CHAR1 had 28 items totaling 823 bytes. Our test character
has 0 items so section is `00 00`.

---

## Section 6 — QB / processor / implants / armour

```
+0 byte   item_count
+1 ... per-item entries (similar to section 5 but length-prefixed
       differently — see Item.PACKET_CHARINFOQB in Item.java)
```

---

## Section 7 — unknown small constant

Always 1 byte = `0x00` in our output. Retail also has a small section.

## Section 0x0c (12) — gogu (currency)

`0x00` (no items) — `CharInfo.java:103`.

---

## Section 8 — buddies / GR / cash / textures (~28-50 bytes)

```
+0 byte   0x0a                literal
+1 dword  cash                LE32
+5 word   gr_count            usually 0
+7 byte   0x04
+8 byte   0x04
+9 byte   0x04
+10 dword 0                   padding
+14 word  transaction id (LE16)  (incremented again from section 1)
+16 dword 0                   padding
+20 byte  0
+21 byte  0
+22 byte  0
+23 byte  class * 2           gender/class encoding
+24 byte  0
+25 byte  texture_head
+26 byte  texture_torso
+27 byte  texture_leg
+28 byte  rank
+29 dword 100002              app id (literal)
+33 byte  0x01
+34 dword 0                   padding
```

Bit-for-bit shape matches `CharInfo.java:106-126` but several literal
bytes need verification.

---

## Section 9 — faction sympathies (~92 bytes)

```
+0 word   21                  faction count
+2 byte   current faction
+3 byte   0
+4 byte   4
+5 ... 21 × float32_LE        sympathy values
+5+84 float32 SL low padding
+5+88 float32 padding
+5+92 byte   current faction (repeat)
```

---

## Sections 10, 11, 13 — stub / not yet decoded

`CharInfo.java:143-153` writes minimal data:
- 0x0a (clan): empty
- 0x0b: single 0 byte
- 0x0d: 8 bytes (profession, transaction id repeat, charid)

---

## Open questions

1. **Section 2 fields @18/@20/@22.** All three are NOT the literal 101
   our code writes — they're character-derived numbers in retail. The
   ~0.35 ratio for f@18 vs max_HP is suspiciously clean across captures.
   Need more retail samples (with different professions) to derive the
   formula.
2. **HP-bar visual update flow.** Confirmed via live test that:
   - CharInfo section 2's max_HP=100 does NOT reach the client display
     (HUD shows 294 — derived from skills/subskills client-side).
   - Raw 0x1f 0x50 PoolDelta packets do NOT move the local player's HUD
     (tested with both +/- deltas).
   - The client computes max_HP locally from skill data, ignoring our
     packet's max field.
3. **Damage delivery chain.** Retail's typical damage signature is the
   short 6-byte event `1f 01 00 25 23 30` repeated every ~500ms during
   combat. Our `DamageEvent` always emits the rich 27-byte 0x25 0x06
   variant which retail only sends at the fatal hit. This may be why
   our HUD doesn't tick down despite `!damage` working server-side.

## Where the formula likely lives

The client's max_HP / max_PSI / max_STA are computed from primary skills
and subskills via a class-specific formula. TinNS source code or the
retail client's `FUN_0055c270` (CharSystem state machine) are the most
likely references. We have Ghidra dumps in `docs/state_string_refs.txt`
that may help locate the function.
