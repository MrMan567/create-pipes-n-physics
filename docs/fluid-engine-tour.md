# A tour of the fluid engine (for humans)

This is the learning companion to `CLAUDE.md` (the dense spec). It walks the engine the way you
would explore it in an IDE: what each object *is*, what one server tick does, and why the odd
rules exist. Read it top to bottom once, then keep it open while stepping through the code.

## 1. The one-paragraph mental model

Think of a pipe network as an electrical circuit that happens to carry water. Tanks are
**capacitors** (their "voltage" is the fluid surface height, called the *head*). Pipe runs are
**resistors** (long/viscous = less conductance). Pumps are **batteries** (they add head, called
*EMF*) with check valves. Once per tick, a linear solve computes what *should* flow where —
exactly like solving a circuit. Then, separately, the fluid **actually moves**: every pipe block
really stores mB (250 by default), and the solved flows are executed as *plug flow* through that
stored volume. What you see rendered in a glass pipe is literally the mB stored in that block.

The split matters: the **solve** is pure math and never touches the world; the **execution**
never decides anything hydraulic, it just moves real fluid at the solved rates under
conservation rules. When something looks wrong, first ask which half owns the bug.

## 2. The cast, in the order they act

### World model (`engine/`)
| Class | It is... |
|---|---|
| `Graph`, `Node`, `Edge` | The network as the solver sees it: endpoints (`Node`: HANDLER tank, PUMP, JUNCTION, OPEN_END, CLOSED_GATE shut valve) connected by contracted pipe runs (`Edge` = an ordered list of the pipe blocks between two nodes). Built by `GraphBuilder` (a BFS), cached across ticks by `GraphCache`. |
| `PipeFluidCell` | The interface a pipe block entity gains via mixin: its stored `FluidStack` (saved + synced — this **is** the render state) and a small synced animation stamp (flow direction + rate). |
| `PipeStore` / `PipeStore.Store` | Type-safe access to one cell's stored fluid: `insert`, `extract`, `room`, capacity from config. Never mixes fluids, clamps to capacity, batches the sync (`flush`). |
| `BoundaryColumn` | An endpoint resolved as a vertical fluid column (base height, capacity, fill, quirks like hose pulleys and relay blocks). The solver-facing view of a tank. |

### The solve (`engine/` + `engine/solve/`)
| Class | It is... |
|---|---|
| `FlowSolver` | Translates the graph + live tank fills into the circuit and back. Runs one *pass per fluid* on the shared topology. Produces a `Solution`. |
| `NetworkSolver` | The pure math (implicit Euler + active set). No Minecraft imports; JUnit-tested. Do not fear it, but you rarely need to open it. |
| `Solution` | One tick's decision: per-edge flows, per-fluid `FlowPass`es (what the executor runs), status flags (blocked/stalled/held + reasons, with helpers like `isBackedUp`), and THREE deliberately distinct head fields — display heads (the real fluid state), ceilings (friction-free planning potential: "how high could fluid be pushed from here"), anchors (the supply surface a budget is measured from). Also carries `actualFlow[]`, which the executor fills in afterwards with what REALLY moved. |

### The execution (`engine/flow/`) — where fluid really moves
| Class | It is... |
|---|---|
| `PipeFlowExecutor` | The one-page facade. Its `run()` is the tick lifecycle: build the `FlowNetwork`, one `BrigadePass` per fluid, one `SettlePass` for everything idle, flush. Start reading here. |
| `FlowNetwork` | This tick's fresh object view: `cellAt(pos)`, `reservoirAt(node)`, `slotAt(node)` (the junction/valve buffer cell). Resolved fresh because the world may have changed since the solve. |
| `Reservoir` | An endpoint you `drain()` / `fill()`. Owns ALL endpoint rules: the shared per-tick give/take budgets (`MAX_FLOW_PER_ENDPOINT`), the lip drain cap, simulate-then-execute, spill/pulley latches. One instance per physical tank, however many pipes touch it. |
| `BrigadePass` | One fluid pass: turns each flowing edge into a `FlowingRun` and ticks them consumers-first, so a chain moves one step everywhere in the same tick. Also answers "pull fluid arriving at this node" for runs feeding across junctions and pumps. |
| `FlowingRun` | One flowing edge. `tick()` = `deliver()` → `shiftForward()` → `intake()`. All movement is **plug flow**: fluid entering a dry cell parks until the feeder cell is full, so a front is a full column advancing `q/capacity` cells per tick, and a sink only fills once the column arrives (a full tail cell). |
| `SettlePass` / `SettlingRun` | Everything the brigade did not flow settles toward its hydrostatic resting profile: humps recede into tanks, submerged cells fill, broken siphons *retain* (but never draw) barometric legs, held pump lines pack fill-only, headless runs just obey gravity. |
| `FlowLedger` | The receipts: strongest per-edge movement (what goggles show), whether anything moved, whether settling continues (keeps the network awake). |

### The rendering (client)
| Class | It is... |
|---|---|
| `PipeFluidRenderer` | Draws each straight (glass) pipe cell's synced stored content. Fill fraction = `storedMb / capacity`. The flow stamp adds cosmetics only: scroll direction/speed and sub-tick extrapolation. There is no separate "render state" to go stale — if it draws wrong, the *content* is wrong. |
| `GlassPipeVisualMixin` / `TransparentStraightPipeRendererMixin` | Hide Create's own pipe-fluid drawing while the engine runs. |

## 3. One server tick, end to end

Entry point: `EngineTickHandler.onServerTick` → `tickNetwork` per network.

1. **Wake & dedupe.** Pipes heartbeat themselves dirty; sleeping networks skip; `GraphCache`
   serves the graph or `GraphBuilder` rebuilds it. Each network solves exactly once per tick.
2. **Solve** (`FlowSolver.solve`). Read-only. For each fluid (largest volume first): build the
   circuit (conductances, pump EMFs, one-way walls like "an empty tank can't give"), solve it,
   record flows/heads/flags. Emits a `FlowPass` per fluid with signed per-edge rates.
3. **Execute** (`FluidEngine.apply` → `PipeFlowExecutor.run`). The only place fluid moves:
   - `BrigadePass` per fluid: sources drain into head cells, columns shift forward one step,
     full tail cells pour into sinks. Backpressure is automatic — a full sink simply stops the
     column and the pipe backs up.
   - `SettlePass`: idle runs relax toward their waterlines; a running pump packs its
     dead-headed line (`pumpPrime`) because the solve reports zero steady-state flow there.
   - Every handler exchange is SIMULATE-then-EXECUTE; every cell move is paired integer mB.
     **Conservation is structural, not aspirational.**
4. **Sync.** Changed cells `notifyUpdate()` once (batched by `Store.flush`). Content rides the
   BE NBT: it survives save/load and travels with contraptions.
5. **Client.** `PipeFluidRenderer` draws contents. A fill that started in a dry cell renders as
   an advancing front along the flow axis; standing fluid just rises/falls at its waterline.

## 4. Worked example: you open a valve on a primed line

Setup: full tank → pump → 5 full pipes → shut valve → 3 empty pipes → empty tank.

- While shut: the valve is a `CLOSED_GATE` node. The solve gives the feed side zero flow but
  flags it *held*; `SettlingRun` (fill-only mode) has already let the pump pack those 5 cells
  and will not let them drain. The 3 downstream cells are empty; nothing fills them.
- You crank the valve open. The graph rebuilds: the gate node disappears, the two runs merge.
- Next solve: real flow `q` across the merged edge. `BrigadePass` builds one `FlowingRun`.
- Tick by tick: the run shifts forward. Cell 6 (first dry one) receives `q` per tick and *parks
  it* (plug flow) until full, then cell 7 starts, then 8. The client draws exactly this as an
  advancing front, because these cells started dry.
- Only when cell 8 (the tail) is full does `deliver()` start filling the tank — the fluid you
  saw travelling is the fluid that arrives. Meanwhile the source tank has been draining the
  whole time, because the pipe volume is real.

## 5. The invariants (memorize these five)

1. **The solve never mutates; the executor never decides.** Hydraulics in `FlowSolver`/
   `NetworkSolver`; movement in `engine/flow`. `/pipegraph` may call the solve safely anytime.
2. **Conservation by pairing.** Fluid only moves as `extract`+`insert` between our cells, or
   simulate-then-execute against a handler. If you add a code path that does only one half, you
   have written a dupe/void bug.
3. **Plug flow.** Into a dry cell, fluid parks until the feeder is full; a sink fills only from
   a full tail. This is both the physics and the render model.
4. **Endpoint rules live in `Reservoir` only.** Budgets, lips, latches. Never talk to an
   `IFluidHandler` from anywhere else in the execution layer. More generally, each endpoint rule
   has exactly one home: *classification* happens at `BoundaryColumn.resolve` (tank vs open end
   vs pulley vs relay), *direction* walls happen in the solve (empty→receive-only, full→give-only,
   lips, check valves), *magnitude* caps happen in `Reservoir` (budgets, lip cap). That map tells
   you where to look for any endpoint behavior.
5. **The renderer owns no state.** It draws synced content plus a cosmetic stamp. Never "fix" a
   visual bug in the renderer if the stored content is wrong — fix the executor.

## 6. Reading order & debugging

Read in this order: `PipeFluidCell` → `PipeStore` → `PipeFlowExecutor` (the facade) →
`FlowingRun` → `Reservoir` → `SettlingRun` → then `FlowSolver`'s class doc, and only then
`CLAUDE.md` for the full history and edge-case law.

Debugging tools:
- `/pipegraph` at a pipe: nodes, per-edge `solved=` (hydraulic intent) vs `actual=` (what the
  executor moved), plus a live overlay. Pointed at a foreign block it explains how the engine
  classifies it.
- Engineer's goggles on a pipe: status + reason, actual mB/t, sneak for the cell's stored mB
  ("Holds"), pressure, and lift budget.
- `-Dpipesnphysics.debugSettle=true`: logs every settle decision (targets, draws, drains).
- `./gradlew test` (solver math) and `./gradlew runGameTestServer` (~76 end-to-end tests on
  real Create blocks; the conservation suite will catch most mistakes you can make).

## 7. Why is it like this? (FAQ)

**Why doesn't the linear solve know about pipe volume?** Stability. The implicit-Euler circuit
solve is the battle-tested core (v1 died of oscillations). Pipe volume is enforced downstream in
the executor, which can clamp but never destabilize the math. The cost: a dead-headed pump
solves zero flow, so packing its line needed an explicit rule (`pumpPrime`).

**Why "passes" per fluid?** Minecraft fluids don't mix in a tick. Each fluid solves on the
shared pipes with other fluids' endpoints walled off; the brigade then moves each pass's fluid,
and plug flow lets one fluid physically push another ahead of it in the pipes.

**Why the hysteresis band in settling?** Drawing from a tank lowers its surface, which lowers
the targets computed *from* that surface, which would pour the fluid straight back. The band
makes the resting state a fixed point instead of a ping-pong.

**Why may a broken siphon keep fluid high in its legs?** A primed leg under a crest vacuum gap
is physically supported (up to `SUCTION_LIMIT`) — so it is *retained*. But nothing may be
*drawn up* past a tank's surface once air is at the crest; draw targets stop at the waterline.

**Where does fluid go when I break a pipe?** `NetworkEditHandler.spillBrokenPipe`: back into
adjacent cells and tanks with room; a bucket's worth becomes a source block; only dregs are
lost. Pistons and explosions void it, same as a broken tank.
