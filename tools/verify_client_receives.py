#!/usr/bin/env python3
"""Answer the ONE question: does the client receive our packets after
the zone-handoff? Check recvmsg (S→C) and sendmsg (C→S) counts before
and after the port change."""
import os, re; os.chdir('/home/javier/Documents/Projects/Neocron/ceres-j')
from pathlib import Path
import importlib.util, sys
spec = importlib.util.spec_from_file_location('pb', 'tools/parse-burst.py')
pb = importlib.util.module_from_spec(spec); spec.loader.exec_module(pb)

_RE = re.compile(
    r'^\d+\s+(\d+:\d+:\d+\.\d+)\s+(recvmsg|sendmsg)\(\d+<UDP[^>]*>.*?iov_base="((?:[^"\\]|\\.)*)".*?= (\d+)$',
    re.MULTILINE,
)

def ts_ms(ts):
    h,m,s = ts.split(':'); s2,us = s.split('.')
    return int(h)*3600000+int(m)*60000+int(s2)*1000+int(us)//1000

path = sys.argv[1] if len(sys.argv) > 1 else '/tmp/nc2_strace.log'
with open(path, 'r', errors='replace') as f: content = f.read()

packets = []
for m in _RE.finditer(content):
    ts, call = m.group(1), m.group(2)
    n = int(m.group(4))
    if n <= 0: continue
    d = 'S2C' if call == 'recvmsg' else 'C2S'
    data = pb.decode_strace_bytes(m.group(3))
    if data: packets.append((ts_ms(ts), d, data))

if not packets:
    print("No packets found"); sys.exit(1)

# Decrypt all and find the zone-handoff point
# Zone-handoff = where the client sends the SECOND burst of 0x01 handshakes
# after a gap of gameplay packets
handshake_bursts = []
in_burst = False
burst_start = None
for ms, d, wire in packets:
    if d != 'C2S': continue
    p = pb.decrypt_wire(wire)
    if not p: continue
    if p[0] == 0x01:
        if not in_burst:
            burst_start = ms
            in_burst = True
    else:
        if in_burst:
            handshake_bursts.append(burst_start)
            in_burst = False

start_ms = packets[0][0]
print(f"Total packets: {len(packets)}")
print(f"Handshake burst starts (ms from session start): {[b - start_ms for b in handshake_bursts]}")

# Use the SECOND handshake burst as the zone-handoff point
if len(handshake_bursts) >= 2:
    handoff_ms = handshake_bursts[1]
else:
    handoff_ms = handshake_bursts[0] if handshake_bursts else start_ms + 10000
    print(f"WARNING: only {len(handshake_bursts)} handshake bursts, using {handoff_ms - start_ms}ms")

print(f"\nZone-handoff at: +{handoff_ms - start_ms}ms")

# Count S2C and C2S before and after handoff
s2c_before = s2c_after = c2s_before = c2s_after = 0
last_s2c_ms = last_c2s_ms = 0
for ms, d, wire in packets:
    if d == 'S2C':
        if ms < handoff_ms: s2c_before += 1
        else:
            s2c_after += 1
            last_s2c_ms = ms
    else:
        if ms < handoff_ms: c2s_before += 1
        else:
            c2s_after += 1
            last_c2s_ms = ms

print(f"\n{'':>20} {'Before':>10} {'After':>10}")
print(f"{'S→C (recvmsg)':>20} {s2c_before:>10} {s2c_after:>10}")
print(f"{'C→S (sendmsg)':>20} {c2s_before:>10} {c2s_after:>10}")
print(f"\nLast S→C at: +{last_s2c_ms - start_ms}ms")
print(f"Last C→S at: +{last_c2s_ms - start_ms}ms")
print(f"Session end (last pkt): +{packets[-1][0] - start_ms}ms")

# Show what the client receives AFTER zone-handoff (first 20)
print(f"\n=== First 20 S→C packets AFTER zone-handoff ===")
count = 0
for ms, d, wire in packets:
    if ms < handoff_ms: continue
    if d != 'S2C': continue
    p = pb.decrypt_wire(wire)
    if not p: continue
    rel = ms - handoff_ms
    print(f"  +{rel:>6}ms  {len(p):>4}B  byte0=0x{p[0]:02x}")
    count += 1
    if count >= 20: break

# Show what the client sends AFTER zone-handoff (first 20)
print(f"\n=== First 20 C→S packets AFTER zone-handoff ===")
count = 0
for ms, d, wire in packets:
    if ms < handoff_ms: continue
    if d != 'C2S': continue
    p = pb.decrypt_wire(wire)
    if not p: continue
    rel = ms - handoff_ms
    print(f"  +{rel:>6}ms  {len(p):>4}B  byte0=0x{p[0]:02x}")
    count += 1
    if count >= 20: break

# Last 20 packets of the session
print(f"\n=== Last 20 packets of session ===")
for ms, d, wire in packets[-20:]:
    p = pb.decrypt_wire(wire)
    if not p: continue
    rel = ms - start_ms
    dir_str = 'S→C' if d == 'S2C' else 'C→S'
    print(f"  +{rel:>6}ms {dir_str}  {len(p):>4}B  byte0=0x{p[0]:02x}")
