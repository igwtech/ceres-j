"""Parse a pcap (no scapy), decrypt NC2 UDP, extract 0x03/0x22/0x0d
Zoning1 events. Pin the actual P1↔P3 sector-cross body bytes from
a retail capture.

Usage: python pcap_extract_zoning.py <pcap>
"""
import struct, sys
from pathlib import Path

# Import the cipher (decrypt-retail.py has the LFSR/CFB)
sys.path.insert(0, '/home/javier/Documents/Projects/Neocron/ceres-j/tools')
from importlib import import_module
cipher = import_module('decrypt-retail')


def parse_pcap(path):
    """Yield (ts_sec, ts_usec, ip_payload_bytes) for each UDP packet."""
    with open(path, 'rb') as f:
        # Global header (24B)
        gh = f.read(24)
        if len(gh) < 24:
            return
        magic = struct.unpack('<I', gh[:4])[0]
        if magic == 0xa1b2c3d4:
            byte_order = '<'  # little-endian
        elif magic == 0xd4c3b2a1:
            byte_order = '>'  # big-endian
        else:
            print(f"unknown pcap magic: 0x{magic:x}", file=sys.stderr)
            return
        # network type at offset 20 (DLT_EN10MB=1, DLT_LINUX_SLL=113)
        link_type = struct.unpack(f'{byte_order}I', gh[20:24])[0]
        while True:
            rec_hdr = f.read(16)
            if len(rec_hdr) < 16: break
            ts_sec, ts_usec, incl_len, orig_len = struct.unpack(
                f'{byte_order}IIII', rec_hdr)
            payload = f.read(incl_len)
            if len(payload) < incl_len: break
            # Skip link layer
            if link_type == 1:  # Ethernet
                if len(payload) < 14: continue
                ethertype = struct.unpack('>H', payload[12:14])[0]
                if ethertype != 0x0800: continue  # not IPv4
                ip_pkt = payload[14:]
            elif link_type == 113:  # Linux cooked v1
                if len(payload) < 16: continue
                ethertype = struct.unpack('>H', payload[14:16])[0]
                if ethertype != 0x0800: continue
                ip_pkt = payload[16:]
            elif link_type == 276:  # Linux cooked v2
                # SLL2 header is 20B: proto(2 BE) reserved(2) iface(4)
                # arphrd(2 BE) pkttype(1) lladdr_len(1) lladdr(8)
                if len(payload) < 20: continue
                proto_be = struct.unpack('>H', payload[0:2])[0]
                if proto_be != 0x0800: continue
                ip_pkt = payload[20:]
            elif link_type == 0:  # null/loopback
                if len(payload) < 4: continue
                ip_pkt = payload[4:]
            elif link_type == 101:  # LINKTYPE_RAW (raw IPv4, no link layer)
                ip_pkt = payload
            else:
                continue
            # Parse IPv4
            if len(ip_pkt) < 20: continue
            ihl = (ip_pkt[0] & 0x0f) * 4
            proto = ip_pkt[9]
            if proto != 17: continue  # UDP only
            src_ip = '.'.join(str(b) for b in ip_pkt[12:16])
            dst_ip = '.'.join(str(b) for b in ip_pkt[16:20])
            udp_hdr_off = ihl
            if len(ip_pkt) < udp_hdr_off + 8: continue
            src_port = struct.unpack('>H', ip_pkt[udp_hdr_off:udp_hdr_off+2])[0]
            dst_port = struct.unpack('>H', ip_pkt[udp_hdr_off+2:udp_hdr_off+4])[0]
            udp_len = struct.unpack('>H', ip_pkt[udp_hdr_off+4:udp_hdr_off+6])[0]
            udp_body = ip_pkt[udp_hdr_off+8:udp_hdr_off+udp_len]
            yield (ts_sec + ts_usec/1e6, src_ip, src_port,
                   dst_ip, dst_port, udp_body)


def decrypt_one(wire: bytes):
    """Decrypt a single NC2 UDP wire packet. Returns plaintext or None."""
    if len(wire) < 4: return None
    try:
        result = cipher.decrypt_wire_packet(wire)
        if result is None:
            return None
        plain, _seed = result
        return plain
    except Exception as e:
        return None


def walk_subpackets(plain):
    """Walk 0x13 wrapper sub-packets. Yield (sub_op, sub_body)."""
    if not plain or len(plain) < 7 or plain[0] != 0x13:
        return
    # [13][seq:LE2][ack:LE2][sub_len:LE2][body]...
    pos = 5
    while pos + 2 <= len(plain):
        sub_len = plain[pos] | (plain[pos+1] << 8)
        pos += 2
        if sub_len == 0 or pos + sub_len > len(plain):
            break
        sub = plain[pos:pos+sub_len]
        pos += sub_len
        if len(sub) >= 1:
            yield sub


def main():
    if len(sys.argv) < 2:
        print("usage: pcap_extract_zoning.py <pcap>", file=sys.stderr)
        sys.exit(2)
    pcap_path = sys.argv[1]
    zoning_events = []
    sc_burst_after = {}  # ts → list of S→C sub-packets near a Zoning
    npc_data_after = {}
    n_udp = 0
    n_dec = 0
    n_wrapped = 0
    for ts, sip, sp, dip, dp, body in parse_pcap(pcap_path):
        n_udp += 1
        plain = decrypt_one(body)
        if plain is None: continue
        n_dec += 1
        if plain[0] != 0x13: continue
        n_wrapped += 1
        for sub in walk_subpackets(plain):
            if len(sub) < 4: continue
            if sub[0] != 0x03: continue
            inner_op = sub[3]
            # 0x03/0x22 sub-tag at body[0] = sub[4]. Memory note says
            # the C→S Zoning body starts `0d 00 01 04 00 ...` (full
            # 18B body). sub[4]=0x0d is the sub-action tag.
            if inner_op == 0x22 and len(sub) >= 5 and sub[4] == 0x0d:
                zoning_events.append((ts, sip, dip, sp, dp, sub))
            # Also collect ALL 0x22 events (with any sub-tag) so we
            # can see what else is on the C→S 0x22 channel.
            if inner_op == 0x22 and len(sub) >= 5 and sub[4] != 0x0d:
                pass  # could log if useful
            # 0x03/0x1f Transaction (server reply)
            elif inner_op == 0x1f:
                pass  # could log if near a Zoning event
    print(f"=== pcap stats ===")
    print(f"  total UDP packets: {n_udp}")
    print(f"  successfully decrypted: {n_dec}")
    print(f"  with 0x13 wrapper: {n_wrapped}")
    print(f"\n=== Zoning1 (0x03/0x22 sub=0x0d) events: {len(zoning_events)} ===\n")
    for i, (ts, sip, dip, sp, dp, sub) in enumerate(zoning_events):
        direction = "C→S" if dp in (5002, 5003, 5004, 5005, 5006, 5007, 5008, 12000) else "S→C"
        print(f"  [{i+1}] T={ts:.3f}  {sip}:{sp} → {dip}:{dp}  ({direction})")
        print(f"      sub_len={len(sub)}  bytes:")
        print(f"      {sub.hex(' ')}")
        # Annotate envelope vs body
        if len(sub) >= 7:
            env = sub[:4].hex(' ')  # 03 [seq:LE2] 22
            head = sub[4:6].hex(' ')  # 04 00 (env tail) or 01 00
            body = sub[6:]
            print(f"      envelope: {env}  outer_head: {head}  body: {body.hex(' ')}")
        print()


if __name__ == '__main__':
    main()
