# Implementation Plan: Decentralized Emergency Mesh Network (Android)

> **For the AI agent:** Read this whole file before doing anything. Work **one phase at a time**. Do not start a phase until the previous phase's acceptance criteria pass. After each task, run its verification command, and commit.

---

## 0. Project Summary

An Android app in which phones exchange messages **directly with each other** (no cellular, no internet, no server). Every phone is a sender, receiver and **relay**, so a message from A can reach D through B and C. Each phone-to-phone jump is a **hop**.

Core problems to implement:
1. **Multi-hop routing** (managed flooding plus learned next hops)
2. **Deduplication** with Bloom filters
3. **Store-and-forward** with ACKs and retransmission
4. **Power-efficient discovery** via adaptive duty cycling
5. **Encryption** (end-to-end payloads, encrypted local storage)

## 1. Scope Guard (IMPORTANT)

**In scope:** everything above, the Android UI, a simulator, and evaluation tooling.

**Out of scope. Do NOT build, scaffold, or suggest any of these:**
- Machine learning, AI, or message triage or prioritisation
- Any cloud backend, REST API, FastAPI, PostgreSQL, or PostGIS
- Any web dashboard (React, Leaflet, Recharts)
- Internet gateway sync
- Offline maps, shelter data, or resource sharing
- iOS

**Optional stretch (only after Phase 10, only if asked):** LoRa transport.

## 2. Rules for the Agent

1. **Keep `mesh-core` and `mesh-sim` free of Android imports.** They must compile and test on the plain JVM.
2. **All radio access goes through the `Transport` interface.** Routing code never calls Nearby, BLE, or Wi-Fi APIs directly.
3. **Write tests with every feature.** No task is done until its tests pass.
4. **Do not invent API signatures.** For Nearby Connections, Room, SQLCipher, and Noise or Tink, read the official documentation for the exact version you use. If unsure, say so and ask.
5. **You cannot test real radios.** Real-device steps are marked **[HUMAN GATE]**. Stop at those, write a checklist for the human, and wait.
6. **Small commits**, one per task, with clear messages.
7. **Pin dependency versions** in the Gradle version catalog. Use the latest stable versions and record them in `docs/DECISIONS.md`.
8. Keep messages small. Never assume payloads larger than a few KB.
9. When a design choice is ambiguous, record the decision and reasoning in `docs/DECISIONS.md` and continue.

## 3. Repository Layout

```
mesh-network/
├── settings.gradle.kts
├── gradle/libs.versions.toml
├── mesh-core/               # pure Kotlin/JVM
│   └── src/{main,test}/kotlin/.../
│       ├── protocol/        # Packet, PacketType, NodeId, codec
│       ├── routing/         # Router, RouteTable, NeighbourTable
│       ├── dedup/           # BloomFilter, RotatingBloom, SeenCache
│       ├── delivery/        # Outbox, RetryPolicy, AckTracker, SyncSummary
│       ├── crypto/          # interfaces only (impl in Phase 8)
│       └── transport/       # Transport interface, events
├── mesh-sim/                # pure Kotlin/JVM simulator
│   └── src/{main,test}/kotlin/.../
│       ├── VirtualClock.kt, SimTransport.kt, SimNetwork.kt
│       ├── topology/, mobility/, failure/
│       └── metrics/         # collector, CSV export
├── mesh-android/            # Android library
│   └── src/main/kotlin/.../
│       ├── transport/NearbyTransport.kt
│       ├── storage/         # Room + SQLCipher
│       ├── service/MeshService.kt (foreground service)
│       ├── power/DutyCycleController.kt
│       └── testmode/TopologyFilter.kt, TrafficGenerator.kt, LogExporter.kt
├── app/                     # Compose UI
├── analysis/                # Python: pandas + matplotlib scripts
├── docs/                    # DECISIONS.md, PROTOCOL.md, TEST_PLAN.md
└── IMPLEMENTATION_PLAN.md
```

## 4. Key Design Specifications

### 4.1 Packet (Protocol Buffers)

```proto
syntax = "proto3";
package mesh.v1;

enum PacketType { PACKET_TYPE_UNSPECIFIED = 0; HELLO = 1; DATA = 2; ACK = 3; SYNC_SUMMARY = 4; }

message Packet {
  bytes      msg_id        = 1;  // 16 random bytes
  bytes      origin        = 2;  // 8-byte node id
  bytes      dest          = 3;  // 8-byte node id; empty = broadcast
  PacketType type          = 4;
  uint32     ttl           = 5;  // remaining hops
  uint32     hop_count     = 6;  // hops travelled so far
  uint64     created_at_ms = 7;
  uint64     expires_at_ms = 8;
  bytes      payload       = 9;  // DATA: encrypted body; HELLO: name+pubkey; SYNC_SUMMARY: bloom bytes
}
```

Defaults: `ttl = 8`, message lifetime 24 h (both configurable).

### 4.2 Routing Algorithm

- **Neighbour table:** direct peers from transport connect and disconnect events, refreshed by HELLO every `helloIntervalMs`. A neighbour missing 3 intervals is removed, which invalidates routes through it.
- **Route table:** `dest -> (nextHop, cost, lastSeen)`, learned from received packets. If a packet from `origin` arrives via neighbour `N` with `hop_count = h`, then `route(origin) = (N, h, now)`. Routes expire after `routeTtlMs`.
- **On receive:** if `msg_id` already seen, drop. Otherwise mark seen. If `dest` is this node or broadcast, deliver locally (and send an ACK for direct messages). If `dest` is not this node and `ttl > 0`, forward with `ttl-1` and `hop_count+1`.
- **Forwarding:** broadcast means send to all neighbours except the sender. Direct means send to `route(dest).nextHop` if a fresh route exists, otherwise flood to all neighbours except the sender.
- **Failure:** if a unicast send fails or no ACK arrives in time, invalidate that route and fall back to flooding.
- **Store-and-forward:** if there are no neighbours, keep the message in the outbox and retry on the next peer-connect event.

### 4.3 Deduplication

- `BloomFilter(expectedItems, falsePositiveRate)` with a double-hashing scheme. Target: 10,000 items at 1% false positives.
- `RotatingBloom`: keeps a current and a previous filter and rotates on a timer.
- `SeenCache`: small exact LRU (about 1,000 ids) checked alongside the Bloom filter to cut false positives.
- Expose counters (`duplicatesDropped`, `bloomFalsePositivesSuspected`) for evaluation.

### 4.4 Delivery Reliability

- Outbox persisted before sending.
- Direct messages retransmit with exponential backoff (for example 5 s, 15 s, 45 s, capped) until ACK or expiry.
- **Anti-entropy on connect:** send a `SYNC_SUMMARY` containing a Bloom filter of held message ids. The peer sends any messages it holds that are not in that filter.

### 4.5 Duty Cycling

`DutyCycleController` with states **ACTIVE** (continuous discovery and advertising) and **IDLE** (short scan windows, sleep between). Transition to IDLE when the neighbourhood is stable and there is no recent traffic. Lengthen sleep at lower battery. Return to ACTIVE on new traffic or a lost neighbour. All timings configurable.

## 5. Phases

Each phase lists **Tasks**, **Deliverables**, and **Acceptance criteria** (commands the agent must run).

---

### Phase 0: Project setup

**Tasks**
1. Create the Gradle multi-module project (`mesh-core`, `mesh-sim`, `mesh-android`, `app`) with a version catalog.
2. `minSdk 26`, latest stable `compileSdk` and `targetSdk`. Kotlin JVM modules target a current LTS JDK.
3. Add `docs/DECISIONS.md`, a README with build instructions, and a `.gitignore`.
4. Add ktlint or detekt (optional but recommended).

**Acceptance**
- `./gradlew build` succeeds.
- `./gradlew :mesh-core:test :mesh-sim:test` runs (may be empty).

---

### Phase 1: Protocol and data model (`mesh-core`)

**Tasks**
1. Add the Protobuf schema (4.1) and generate code (Protobuf Kotlin lite or Wire).
2. Implement `NodeId` (8 bytes derived from a public-key hash), a `PacketCodec` (encode and decode), and a `Packet` factory helpers (new DATA, ACK, HELLO).
3. Write `docs/PROTOCOL.md` describing fields and rules.

**Acceptance**
- Round-trip encode and decode tests pass, including empty `dest` (broadcast) and maximum-size payloads.
- `./gradlew :mesh-core:test` passes.

---

### Phase 2: Core mesh engine (`mesh-core`)

**Tasks**
1. Define interfaces: `Transport` (send to peer, broadcast to peers, events for peer up, peer down, packet received), `Clock`, `MessageStore`, `Crypto` (no-op implementation for now).
2. Implement `BloomFilter`, `RotatingBloom`, `SeenCache` (4.3).
3. Implement `NeighbourTable` and `RouteTable` (4.2).
4. Implement `Router` following 4.2: receive, deliver, forward, ACK generation.
5. Implement `Outbox`, `RetryPolicy`, `AckTracker`, and `SyncSummary` anti-entropy (4.4).
6. Implement a `MeshNode` facade: `send(dest, payload)`, `broadcast(payload)`, `onDelivered` callbacks, and delivery state (QUEUED, SENT, RELAYED, DELIVERED, EXPIRED).

**Acceptance (all using a fake in-memory transport and a fake clock)**
- Bloom filter: no false negatives over 100,000 random ids, measured false-positive rate within about 2x of target.
- Router: duplicate packets are dropped; ttl reaching 0 stops forwarding; a packet is never sent back to its sender.
- Route learning: after A's packet reaches D via C, D has a route to A with the right cost.
- Outbox: a message queued with no neighbours is sent after a peer-up event; retransmission follows the backoff schedule; ACK cancels retries.
- `./gradlew :mesh-core:test` passes with coverage of every class above.

---

### Phase 3: Simulator and metrics (`mesh-sim`)

**Tasks**
1. `VirtualClock` and `SimNetwork` (discrete-event scheduler), and `SimTransport` implementing `Transport` with configurable link latency and loss.
2. Topology generators: line, grid, random geometric graph.
3. Mobility: random waypoint with a radio-range model that updates links over time.
4. Failure injection: kill or revive nodes at scheduled times.
5. Metrics collector: delivery rate, latency in virtual time, hop count, transmissions per delivered message, duplicates dropped, **routing convergence time**. Export CSV.
6. A CLI `./gradlew :mesh-sim:run --args="scenario=line hops=4 ..."`.
7. Python scripts in `analysis/` that read the CSVs and produce charts (delivery vs hops, latency vs hops, recovery time).

**Acceptance (automated scenario tests)**
- **Line A-B-C-D-E:** A to E direct message delivers in 4 hops with an ACK back.
- **Relay failure:** in a grid with redundant paths, kill a relay mid-run; deliveries resume, and the convergence time is measured and non-zero.
- **Partition and heal:** partition the network, queue messages, reconnect; all are eventually delivered (store-and-forward).
- **Dedup on vs off:** with dedup off, transmissions per delivered message are measurably higher.
- `./gradlew :mesh-sim:run` writes CSVs and `python analysis/plot_all.py` produces PNG charts.

---

### Phase 4: Android scaffold and persistence (`mesh-android`, `app`)

**Tasks**
1. App shell with navigation and dependency injection (Hilt or Koin).
2. Room database with tables `messages`, `outbox`, `routes`, `nodes` encrypted via SQLCipher; the key is generated once and protected with the Android Keystore.
3. Implement the `MessageStore` interface from Phase 2 using Room.
4. Identity: generate a keypair on first launch, derive the `NodeId`, store the display name.

**Acceptance**
- `./gradlew :app:assembleDebug` succeeds.
- Instrumented or Robolectric tests: messages persist across process restart; the database file is not readable as plain SQLite without the key.

---

### Phase 5: Nearby Connections transport (`mesh-android`)

**Tasks**
1. Implement `NearbyTransport : Transport` using the many-to-many (cluster) strategy: advertise and discover concurrently, auto-accept connections, exchange byte payloads.
2. Map connection lifecycle callbacks to peer-up and peer-down events. Handle reconnection and duplicate connections between the same two devices.
3. Request the required runtime permissions (Bluetooth scan, advertise and connect on Android 12+, Nearby Wi-Fi devices on 13+, location on older versions, notifications). Build a permissions onboarding flow.
4. Enforce a maximum packet size and reject oversize packets safely.
5. Add structured logging of every send, receive, connect and disconnect with timestamps.

**Acceptance**
- Builds and passes unit tests using a mocked Nearby client.
- **[HUMAN GATE 1]** Two phones discover each other and exchange a test packet. The agent writes a step-by-step checklist in `docs/TEST_PLAN.md`; the human runs it and reports the result.

---

### Phase 6: Foreground service and duty cycling

**Tasks**
1. `MeshService` as a foreground service (connected-device type) with a persistent notification, owning the `MeshNode` and `NearbyTransport`.
2. `DutyCycleController` (4.5) with unit tests using a fake clock.
3. WorkManager jobs for housekeeping: expire old messages, restart the service if killed, rotate Bloom filters.
4. Battery-aware behaviour (lengthen idle sleep when battery is low).

**Acceptance**
- Unit tests cover all duty-cycle state transitions.
- **[HUMAN GATE 2]** Relaying continues with the screen off for 10 minutes. Checklist in `docs/TEST_PLAN.md`.

---

### Phase 7: User interface (Compose)

**Tasks**
1. Onboarding: display name, permissions.
2. Inbox and conversation screens; compose with **Direct** or **Broadcast**.
3. Per-message status chip: QUEUED, SENT, RELAYED, DELIVERED, EXPIRED, with **hop count** on delivered messages.
4. **Mesh screen:** list of direct neighbours and all known reachable nodes with their hop count and next hop; optionally a simple node graph.
5. Debug panel: duplicates dropped, retransmissions, queue length, current duty-cycle state.

**Acceptance**
- `./gradlew :app:assembleDebug` succeeds.
- Compose UI tests for compose, status changes, and the Mesh screen with fake data.
- **[HUMAN GATE 3]** Manual walk-through of every screen on a real device.

---

### Phase 8: Security

**Tasks**
1. Implement the `Crypto` interface with a real end-to-end scheme: encrypt DATA payloads to the recipient's public key so relays cannot read them. Prefer the Noise Protocol (for example via a Noise library for JVM). If integration is impractical, use X25519 with ChaCha20-Poly1305 through a well-vetted library and record the decision in `docs/DECISIONS.md`. Do not implement cryptographic primitives yourself.
2. HELLO carries the public key; the `nodes` table stores it.
3. Optional: sign packets so origin and headers can be verified.

**Acceptance**
- Tests: encrypt-decrypt round trip; a relay node fed the packet cannot recover plaintext; a tampered payload fails authentication.
- Simulator scenarios still pass with real crypto enabled.

---

### Phase 9: Evaluation harness and experiments

**Tasks**
1. **Test mode** (debug builds): `TopologyFilter` that accepts links and packets only from an allow-list of node IDs, so phones sitting on one desk behave as a chain A-B-C-D and force real multi-hop paths.
2. `TrafficGenerator`: send N messages at a configured rate to a chosen destination.
3. `LogExporter`: write CSV of send, receive, ACK and hop events to device storage so it can be pulled with `adb`.
4. Latency is measured at the **sender** (ACK time minus send time), because phone clocks are not synchronised.
5. `docs/TEST_PLAN.md`: step-by-step procedures for experiments E1 to E6 from the report (hop count, node failure, redundant paths, dedup on or off, partition and heal, duty cycling battery test). For battery: fixed duration (for example 1 hour), screen off, record battery percentage before and after, and capture `adb shell dumpsys batterystats`.
6. Python scripts to merge the device CSVs and generate the tables and charts used in the report.

**Acceptance**
- The harness builds; a dry run in the simulator produces the same CSV schema as the device export.
- **[HUMAN GATE 4]** The team runs E1 to E6 on real phones (3 to 6 devices), then supplies the CSVs. The agent produces the tables and charts. **Never fabricate or estimate results.**

---

### Phase 10: Hardening and demo

**Tasks**
1. Fix issues found in the human gates.
2. Add rate limiting and a maximum outbox size to prevent runaway flooding.
3. Prepare a scripted demo: 4 phones, chain topology via test mode, send A to D, show hops on the Mesh screen, switch off C, show rerouting or store-and-forward recovery.
4. Update README with build, install, and demo instructions.

**Acceptance**
- `./gradlew build` and all tests pass.
- Demo script rehearsed successfully **[HUMAN GATE 5]**.

---

### Phase 11 (Optional stretch): LoRa transport

Only if requested. Implement a `LoRaTransport : Transport` bridging to an external LoRa module (for example over USB serial or BLE) so the same `Router` runs unchanged across long-range links. Do not begin without an explicit go-ahead from the team.

## 6. Dependency Graph (for parallel agents)

- **Phase 0** first.
- **Phase 1 → Phase 2 → Phase 3** are sequential (core logic, then simulator).
- **Phase 4** (Android scaffold, storage, identity) can run **in parallel** with Phases 2 and 3 once Phase 1's interfaces exist.
- **Phase 5** needs Phase 2's `Transport` interface and Phase 4.
- **Phases 6 and 7** can run in parallel after Phase 5.
- **Phase 8** can start once Phase 2 is done, but merge it after Phase 5.
- **Phase 9** needs Phases 3, 6, 7.

## 7. Suggested Work Split for a Team of 4

| Stream | Phases |
|---|---|
| Core protocol and routing | 1, 2, 8 |
| Simulator and evaluation | 3, 9 |
| Android transport and service | 5, 6 |
| App, storage and UI | 4, 7 |

Everyone joins the human gates, since they need physical phones.

## 8. Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Nearby Connections limits on simultaneous connections | Keep the neighbour set small; prefer stable links; document the limit found on real devices |
| OEM background-process killing (Xiaomi, Oppo, etc.) | Foreground service, WorkManager restart, an in-app note guiding users to disable battery optimisation |
| Flooding overhead in dense networks | TTL, dedup, learned routes, rate limits; report overhead honestly |
| Bloom-filter false positives suppress a new message | Exact LRU cache, persistent DB as truth for own messages, ACK retransmission |
| Cannot automate radio tests | Simulator for logic; human gates and a test-mode topology filter for hardware |
| Different phone models behave differently | Test on at least two brands; log device model and Android version with every run |

## 9. Definition of Done

- All phases through 10 complete; all automated tests pass.
- Multi-hop delivery demonstrated on real phones (at least 3 hops via test mode).
- Experiments E1 to E6 run, with real measured results in the report.
- No code or dependencies from the out-of-scope list.

---

## 10. Kickoff Prompt (paste into Antigravity)

> Read `IMPLEMENTATION_PLAN.md` in full. It defines an Android peer-to-peer emergency mesh network with multi-hop routing. Respect the **Scope Guard** and **Rules for the Agent** exactly.
>
> Start with **Phase 0** only. Use Planning mode: produce a task list and implementation plan for Phase 0, then wait for my approval before writing code. After Phase 0 passes its acceptance criteria, stop and summarise what you did. I will then ask you to continue with the next phase.

**Tips for using Antigravity**
- Use **Planning mode** for each phase and review the plan artifact before approving.
- Use a **fresh conversation per phase**, and tell the agent to read `IMPLEMENTATION_PLAN.md` and `docs/DECISIONS.md` at the start.
- Use the **Manager view** to run independent phases in parallel (see Section 6), for example the simulator and the Android scaffold.
- At each **[HUMAN GATE]**, ask the agent to write the checklist, run it on real phones yourselves, and paste the results back.
