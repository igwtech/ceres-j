# Agent RPC reference

The Frida agent exposes the following methods via `rpc.exports`.
Call them from the orchestrator REPL (`call`, `read`, `write`,
`send-udp`) or directly from Python via `AgentController.call()`.

## Introspection

### `getStatus() -> object`

```js
{
  module: "NeocronClient.exe",
  base: "0x00400000",           // null if EXE not loaded yet
  installed: [
    { name: "udp_cipher_a", addr: "0x00560090" },
    { name: "udp_cipher_b", addr: "0x0055ff30" }
  ],
  hookTable: [...],              // canonical table the orchestrator pushed
  frida: "16.5.6",
  arch: "ia32",
  pageSize: 4096
}
```

Use this to confirm the agent attached and which hooks are live.

## Memory I/O

### `readMem(addr: int, n: int) -> number[]`

Reads `n` bytes from runtime VA `addr`. Returns a plain array of ints
(0..255) — Frida serialises typed arrays through QuickJS as int
arrays, which the orchestrator's `readMem` REPL command joins back
into a hex string.

### `writeMem(addr: int, bytes: number[]) -> int`

Writes the byte array to runtime VA `addr`. Returns the number of
bytes written. Use with extreme care — there is no safety on writing
into code pages.

## Function invocation

### `callFunction(addr: int, args: number[], retType?: string, argTypes?: string[]) -> any`

Invokes the function at runtime VA `addr` using Frida's
`NativeFunction`. Default ABI: cdecl, all args pointer-sized
(matches 32-bit Win32). Pass `argTypes` (e.g. `["uint32", "uint16"]`)
to override.

Examples:

```python
# Call FUN_00560090 with (buf_ptr, len) where buf_ptr has been
# allocated via writeMem into a known scratch region.
ctrl.call("callFunction", 0x00560090, [scratch_addr, 64])
```

## High-level helpers

### `sendUdp(bytes: number[]) -> int`

*Placeholder.* Designed to route a payload through the client's
cipher path and Winsock layer so the server sees a datagram
indistinguishable from a real client send. Currently returns -1 and
emits a `hook_error` event because the cipher argument signatures
have not yet been pinned — first attach + capture cipher_enter /
cipher_leave samples to lock the calling convention, then this
helper will be wired.

## Adding a new RPC method

1. Add the JS implementation under `rpc.exports = { … }` in
   `agent/_agent.js`.
2. (Optional) Add a REPL shorthand in `orchestrator/__main__.py`
   `_dispatch_command`.
3. Add a unit test in `tests/test_controller.py` (mocks Frida —
   asserts the orchestrator calls the exported method with the right
   args).

## Adding a new hook

1. Add a `HookSpec` row in `orchestrator/symbols.py` with
   `implemented=True`.
2. Register a factory in `agent/_agent.js`'s `HOOK_FACTORIES` map.
3. Update `docs/SYMBOLS.md` (the table is purely human-facing).
4. The orchestrator pushes the table at attach automatically.
