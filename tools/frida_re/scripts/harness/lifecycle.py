"""Client lifecycle for one instrumented pass: clean slate -> point the
client at the target server -> launch (gadget WAIT mode) -> attach the
orchestrator (which loads _agent.js and writes the decrypted wire/input/
d3d trace) -> resolve the live device pointer -> ... -> teardown.

All waits are STATE-BASED (poll port / trace) with hard timeouts so a
pass is deterministic and never hangs.
"""
from __future__ import annotations
import json
import os
import re
import socket
import subprocess
import time


class Lifecycle:
    def __init__(self, server_ip, run_dir, *, scripts_dir, frida_re_dir,
                 py, nc2_dir="/home/javier/Neocron2", port=27042):
        self.server_ip = server_ip
        self.run_dir = run_dir
        self.scripts_dir = scripts_dir
        self.frida_re_dir = frida_re_dir
        self.py = py
        self.nc2_dir = nc2_dir
        self.port = port
        self.trace = os.path.join(run_dir, "trace.jsonl")
        self.cmds = os.path.join(run_dir, "cmds.txt")
        self._client = None
        self._orch = None

    # ── helpers ──────────────────────────────────────────────────────
    def _log(self, msg):
        print(f"[lifecycle] {msg}", flush=True)

    @staticmethod
    def _pkill(pattern):
        subprocess.run(["pkill", "-f", pattern],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def _set_ini(self, path, key, line):
        if not os.path.isfile(path):
            return
        with open(path, "r", encoding="latin1") as f:
            txt = f.read()
        new = re.sub(rf"(?m)^[ \t]*{re.escape(key)}[ \t]*=.*", line, txt)
        with open(path, "w", encoding="latin1") as f:
            f.write(new)

    # ── phases ───────────────────────────────────────────────────────
    def clean_slate(self):
        self._log("clean slate")
        self._pkill("neocronclient.exe")
        self._pkill("orchestrator")
        time.sleep(2)

    def set_server(self):
        self._set_ini(os.path.join(self.nc2_dir, "neocron.ini"),
                      "NETBASEIP", f'NETBASEIP = "{self.server_ip}:7000"')
        u = os.path.join(self.nc2_dir, "ini", "updater.ini")
        self._set_ini(u, "SERVERIP", f"SERVERIP={self.server_ip}")
        self._set_ini(u, "GAMESERVERIP", f"GAMESERVERIP={self.server_ip}")
        self._log(f"client target -> {self.server_ip}")

    def launch_client(self):
        log = open(os.path.join(self.run_dir, "launch.log"), "w")
        self._client = subprocess.Popen(
            [os.path.join(self.scripts_dir, "launch_nc2.sh")],
            stdout=log, stderr=subprocess.STDOUT)
        self._log(f"launched client (pid {self._client.pid})")

    def wait_gadget(self, timeout=90) -> bool:
        end = time.time() + timeout
        while time.time() < end:
            with socket.socket() as s:
                s.settimeout(0.5)
                if s.connect_ex(("127.0.0.1", self.port)) == 0:
                    self._log("gadget is listening")
                    return True
            time.sleep(0.5)
        return False

    def start_orchestrator(self):
        log = open(os.path.join(self.run_dir, "orch.log"), "w")
        open(self.cmds, "a").close()
        self._orch = subprocess.Popen(
            [self.py, "-m", "orchestrator", "--port", str(self.port),
             "--trace", self.trace, "--commands", self.cmds,
             "--duration", "3600"],
            cwd=self.frida_re_dir, stdout=log, stderr=subprocess.STDOUT)
        self._log(f"orchestrator attached (pid {self._orch.pid})")

    def _trace_lines(self):
        if not os.path.isfile(self.trace):
            return []
        with open(self.trace, encoding="utf-8", errors="ignore") as f:
            return f.readlines()

    def wait_device(self, timeout=60):
        """Return the real (heap, arg_idx 6) device pointer, or None."""
        end = time.time() + timeout
        while time.time() < end:
            for line in self._trace_lines():
                if '"d3d9_device_candidates"' not in line:
                    continue
                try:
                    c = json.loads(line)["extras"]["candidates"]
                except Exception:
                    continue
                for e in c:
                    if e.get("arg_idx") == 6:
                        return e["pDev"]
            time.sleep(0.5)
        return None

    def wait_inworld(self, timeout=60, min_recv=40) -> bool:
        """In-world == sustained decrypted S->C UDP in the trace."""
        end = time.time() + timeout
        while time.time() < end:
            n = sum(1 for ln in self._trace_lines()
                    if '"ev":"udp_recv"' in ln)
            if n >= min_recv:
                return True
            time.sleep(0.5)
        return False

    def teardown(self):
        self._log("teardown")
        if self._orch:
            self._orch.terminate()
        self._pkill("neocronclient.exe")
        self._pkill("proton run .*neocronclient")
        self._log(f"artifacts in: {self.run_dir}")
