#!/usr/bin/env python3
"""Capture what the CURRENT Ceres-J emits to a client that behaves like the
real one at world entry (nc2-bot drive_world_entry), as a harness-style
trace.jsonl (udp_recv / udp_send with plain_hex) so the gap scripts can diff
it against the retail Frida corpus.

Requires the nc2-bot checkout next to ceres-j (drive_world_entry).
Usage: bot_capture.py OUT_DIR [--spot 0] [--listen 30]
"""
import argparse, asyncio, json, logging, os, socket, sys, time
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "..", "nc2-bot"))
from nc2bot.session.tcp import TcpSession
from nc2bot.session.udp import UdpTransport
from nc2bot.session import handshake as hs_mod


async def main(args):
    logging.basicConfig(level=logging.WARNING)
    os.makedirs(args.out, exist_ok=True)
    events = []
    t_start = time.time_ns()

    # Record every datagram both ways by wrapping the transport methods.
    orig_recv = UdpTransport.recv_plain
    orig_send = UdpTransport.send_plain

    async def rec_recv(self, *a, **k):
        dg = await orig_recv(self, *a, **k)
        events.append({"ev": "udp_recv", "ts_ns": time.time_ns(),
                       "direction": "s2c", "plain_hex": dg.plaintext.hex()})
        return dg

    async def rec_send(self, plain, *a, **k):
        events.append({"ev": "udp_send", "ts_ns": time.time_ns(),
                       "direction": "c2s", "plain_hex": bytes(plain).hex()})
        return await orig_send(self, plain, *a, **k)

    UdpTransport.recv_plain = rec_recv
    UdpTransport.send_plain = rec_send

    async with TcpSession(args.host, 12000, recv_timeout=5.0) as sess:
        await sess.login(args.user, args.password)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.bind(("0.0.0.0", 0)); port = probe.getsockname()[1]
        sel = await sess.select_char(username=args.user, password=args.password,
                                     spot=args.spot, client_udp_port=port)
        usd = sel.udp_server_data
        events.append({"ev": "tcp_connect", "ts_ns": t_start,
                       "extras": {"ip": args.host, "port": 12000}})
        print(f"zone={sel.location.zone_name!r}")
        async with UdpTransport(server_host=usd.server_ip, server_port=usd.udp_port,
                                local_port=port, recv_timeout=5.0) as udp:
            res = await hs_mod.drive_world_entry(
                udp, session_id=usd.session_id, account_id=usd.account_id,
                char_slot=args.spot, bsp_load_delay_seconds=0.3,
                listen_seconds=args.listen)
    with open(os.path.join(args.out, "trace.jsonl"), "w") as f:
        for e in events:
            f.write(json.dumps(e) + "\n")
    print(f"wrote {len(events)} events; reliable subs seen={res.reliable_subs_seen}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--host", default="172.18.0.3")
    ap.add_argument("--user", default="msn3wolf")
    ap.add_argument("--password", default="sprlnk2kk")
    ap.add_argument("--spot", type=int, default=0)
    ap.add_argument("--listen", type=float, default=30.0)
    asyncio.run(main(ap.parse_args()))
