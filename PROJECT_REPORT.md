# Decentralized Emergency Mesh Network
### Offline, Multi-Hop, Peer-to-Peer Communication for Disaster-Hit Regions

**Team 9**
Vaibhav K Moorthy · Suji S · Prathik Joe Paul · Sreerag K

**Guide:** Dr. Smitha Suresh, Professor, Department of Computer Science and Engineering  
**Department:** Computer Science and Engineering  
**Academic Year:** 2025–2026  

---

## Abstract

Natural disasters such as floods, cyclones and landslides, which recur in Kerala, routinely destroy cellular and internet infrastructure at the moment communication matters most. Existing emergency tools assume a working network, so affected people and rescuers are left without a reliable channel during the most critical hours.

This project designs and implements a **Decentralized Emergency Mesh Network**: an offline-first, peer-to-peer messaging system for Android in which phones communicate directly over Bluetooth Low Energy and Wi-Fi (Wi-Fi Direct / Wi-Fi Aware) without cellular service, internet access or any central server. Every device is simultaneously a sender, a receiver and a **relay**, forwarding messages **hop by hop** across a self-organising, self-healing topology. Multi-hop relaying extends reach far beyond the radio range of any single phone.

The system addresses four core distributed-systems problems: dynamic multi-hop routing, duplicate suppression using Bloom filters, power-efficient peer discovery through adaptive duty cycling, and reliable store-and-forward delivery with acknowledgment-based retransmission. The prototype is evaluated on multiple Android devices and in a software simulator, measuring message delivery rate, end-to-end latency, routing convergence time and battery consumption under node-failure conditions. LoRa is considered as an optional extension for long-range links between clusters.

**Keywords:** mesh network, multi-hop routing, delay-tolerant networking, Bloom filter, store-and-forward, Bluetooth Low Energy, Wi-Fi Direct, disaster communication, Android.

---

## 1. Introduction

### 1.1 Background and Motivation

Kerala is exposed to floods, cyclones, landslides and, less frequently, earthquakes. The 2018 floods and the 2024 Wayanad landslides are recent examples in which affected areas faced disrupted connectivity while urgent coordination was needed. Cellular towers depend on grid power, backhaul links and (during storms) physical integrity; when any of these fail, every app that relies on a server fails with them.

Modern smartphones, however, carry short-range radios (Bluetooth Low Energy, Wi-Fi) that need no infrastructure. If phones could relay each other's messages, a network could form spontaneously wherever people are, using hardware they already own.

### 1.2 Problem Statement

*How can people in a disaster zone exchange messages when cellular and internet infrastructure is unavailable, using only the smartphones already present, while keeping the system reliable, battery-efficient and robust to devices moving or failing?*

### 1.3 Objectives

1. Design a decentralized, serverless messaging network in which every phone can send, receive and relay.
2. Implement **multi-hop delivery** so a message can reach a recipient beyond the direct radio range of the sender.
3. Prevent message storms and loops using efficient **deduplication**.
4. Ensure delivery despite intermittent contact using **store-and-forward** with **acknowledgments and retransmission**.
5. Keep battery use practical through **adaptive duty cycling** of discovery.
6. Evaluate delivery rate, latency, routing convergence time and battery consumption on real devices and in simulation.

### 1.4 Scope

**In scope:** Android app; BLE / Wi-Fi Direct / Wi-Fi Aware transport via the Nearby Connections API; custom multi-hop routing; Bloom-filter deduplication; encrypted local storage; end-to-end encryption; acknowledgments and retransmission; a mesh-visibility screen (neighbours, hop counts) to demonstrate hops; a simulator and evaluation tooling.

**Out of scope:** internet gateways, cloud backends, relief-agency dashboards, message prioritisation using machine learning, and offline map or resource sharing. These were considered in an earlier proposal and deliberately removed so the project focuses fully on the mesh. **Optional stretch:** LoRa link extension.

---

## 2. Related Work

### 2.1 Existing Systems

| System | Approach | Relevance and gap |
|---|---|---|
| **Briar** | Peer-to-peer messenger syncing between contacts over Bluetooth, Wi-Fi and Tor | Privacy and resilience focus; contact-based rather than an open multi-hop relay network among strangers |
| **Meshtastic** | LoRa radio mesh with dedicated hardware | Very long range and low power, but requires extra hardware and offers low bandwidth |
| **Bridgefy / FireChat-style apps** | Phone-to-phone mesh over Bluetooth / Wi-Fi | Demonstrate the concept; closed or discontinued, and published security research has highlighted weaknesses in some designs |
| **Serval Project** | Android mesh telephony and messaging | Early work on infrastructure-less phone networks |
| **Google Nearby Connections** | Link-layer API that auto-selects BLE, Wi-Fi Direct, etc. | Provides connectivity, **not multi-hop routing**; routing must be built on top |

### 2.2 Routing in Ad Hoc and Delay-Tolerant Networks

- **Flooding:** every node rebroadcasts new messages. Simple and robust but wasteful without duplicate suppression and hop limits.
- **AODV (RFC 3561) and OLSR (RFC 3626):** classic reactive and proactive ad hoc routing protocols. They assume mostly connected networks.
- **Delay-tolerant routing:** Epidemic Routing (Vahdat and Becker), Spray and Wait (Spyropoulos et al.) and PRoPHET (Lindgren et al.) handle networks that are frequently partitioned by having nodes *carry* messages until they meet a next hop. Disaster networks are of this kind: people move, batteries die, clusters form and split.

### 2.3 Bloom Filters

A Bloom filter (Bloom, 1970) is a compact probabilistic set with no false negatives and a tunable false-positive rate. It suits "have I seen this message ID?" checks on memory-constrained devices, and also allows two nodes to compare their message sets cheaply when they meet.

### 2.4 Gap Addressed

No widely available, open, phone-only system combines **(a)** true multi-hop routing, **(b)** delay-tolerant store-and-forward, **(c)** battery-aware discovery and **(d)** a reproducible evaluation methodology. This project targets that combination.

---

## 3. Requirements

### 3.1 Functional Requirements

| ID | Requirement |
|---|---|
| FR1 | Discover nearby nodes automatically without user setup |
| FR2 | Send a direct message to a specific node and a broadcast message to all nodes |
| FR3 | Relay messages for other nodes, across multiple hops |
| FR4 | Drop duplicate messages and never forward a message in a loop |
| FR5 | Hold undelivered messages and forward when a suitable neighbour appears |
| FR6 | Return acknowledgments and retransmit unacknowledged direct messages |
| FR7 | Show delivery status (queued, sent, relayed, delivered) and hop count |
| FR8 | Keep relaying while the app is in the background |
| FR9 | Encrypt stored data and message content |

### 3.2 Non-Functional Requirements

- **Resilience:** delivery must degrade gracefully when nodes leave or fail.
- **Efficiency:** bounded message overhead (hop limit, dedup) and low idle battery drain.
- **Security:** relays must not be able to read message content.
- **Portability:** core logic must be independent of Android so it can be tested and simulated.
- **Testability:** results must be reproducible through simulation and a controlled on-device test mode.

---

## 4. System Design

### 4.1 Architecture

Each node runs the same three layers.

```mermaid
flowchart TB
    A["Application layer: Compose UI, message store (Room + SQLCipher)"]
    B["Mesh layer: routing, dedup (Bloom filter), store-and-forward, ACK / retransmit"]
    C["Transport layer: Nearby Connections over BLE / Wi-Fi Direct / Wi-Fi Aware"]
    A --> B --> C
```

The **mesh layer** is written as pure Kotlin with no Android dependencies and talks to the radio through a `Transport` interface. This lets the same routing code run on phones and inside a simulator.

### 4.2 Multi-Hop Example

```mermaid
sequenceDiagram
    participant A as Node A (sender)
    participant B as Node B
    participant C as Node C
    participant D as Node D (recipient)
    A->>B: DATA (ttl=8, hops=0)
    B->>C: DATA (ttl=7, hops=1)
    C->>D: DATA (ttl=6, hops=2)
    D-->>C: ACK (route learned)
    C-->>B: ACK
    B-->>A: ACK (delivered in 3 hops)
```

D is out of A's radio range. B and C carry the message across the gap. Each phone-to-phone transmission is one **hop**.

### 4.3 Node Identity and Addressing

Each install generates a public/private keypair. The **node ID** is the first 8 bytes of a hash of the public key, and the user also sets a display name. Nodes announce `(node ID, name, public key)` in periodic HELLO packets, so other nodes learn who exists and can encrypt to them.

### 4.4 Packet Format

Packets are serialised with Protocol Buffers for compactness.

| Field | Purpose |
|---|---|
| `msg_id` | 16 random bytes; unique message identifier used for dedup and ACKs |
| `origin`, `dest` | Node IDs; empty `dest` means broadcast |
| `type` | HELLO, DATA, ACK or SYNC_SUMMARY |
| `ttl` | Remaining hop budget, decremented on each forward |
| `hop_count` | Hops travelled so far |
| `created_at`, `expires_at` | Timestamps controlling message lifetime |
| `payload` | Opaque bytes, end-to-end encrypted for DATA |

### 4.5 Peer Discovery and Link Management

The Nearby Connections API with the cluster strategy (many-to-many) is used for advertising, discovery and payload transfer, and it chooses the underlying radio. The app keeps a **neighbour table** of directly connected peers. Neighbours that miss several HELLO intervals are removed, which triggers route invalidation (Section 4.6).

### 4.6 Multi-Hop Routing

A hybrid of managed flooding and learned next hops, chosen for robustness in a highly dynamic network:

1. **Broadcast messages** are flooded: each node forwards a new message to all neighbours except the one it came from, while `ttl > 0`.
2. **Route learning (reverse path):** when a packet from origin *O* arrives via neighbour *N* with `hop_count = h`, the node records *route(O) = (next hop N, cost h, last seen)*. Routes expire if not refreshed.
3. **Direct messages:** if a fresh route to the destination exists, send to the next hop only. Otherwise flood with a hop limit.
4. **Failure handling:** if a next-hop send fails or no ACK arrives, invalidate the route and fall back to flooding. **Routing convergence time** is the time from a topology change until deliveries succeed again over a new path.
5. **Rate limiting:** a token-bucket rate limiter (`RateLimiter`) bounds packet forwarding (20 pkts/s, burst 40) and broadcast generation (5 msg/s, burst 10) to prevent broadcast storms and radio frame congestion.

### 4.7 Deduplication with Bloom Filters

Every message ID is checked against a Bloom filter before processing. A duplicate is dropped immediately, which prevents loops and message storms.

- **Sizing:** for 10,000 IDs at a 1% false-positive rate the filter needs about 96,000 bits (about 12 KB) with 7 hash functions.
- **Ageing:** two filters (current and previous) rotate periodically so the filter does not fill up forever.
- **False positives:** a false positive can wrongly suppress forwarding of a genuinely new message. This is mitigated by a small exact LRU cache of recent IDs, by the persistent database as the source of truth for messages addressed to this node, and by ACK-based retransmission, which recovers the rare loss.

### 4.8 Store-and-Forward and Reliability

- Every message is written to a persistent **outbox** before transmission, bounded to a maximum capacity (500 packets). If capacity is exceeded, expired packets are purged and the oldest pending messages are evicted (`DeliveryState.EXPIRED`) to prevent memory exhaustion.
- **Anti-entropy on contact:** when two nodes connect, they exchange a Bloom-filter summary of message IDs held. Each sends the messages the other appears to lack. This lets messages travel physically with people (a delay-tolerant behaviour) and cross gaps in the network.
- **Acknowledgments:** the destination returns an ACK along the learned route. The sender retransmits with exponential backoff until an ACK arrives or the message expires.

### 4.9 Power Management

Continuous discovery drains the battery. A `DutyCycleController` alternates between:

- **Active:** continuous advertise and discover when few neighbours are known or traffic is recent.
- **Idle:** short discovery windows separated by sleep intervals once the neighbourhood is stable, lengthening the sleep with battery level.

A foreground service keeps relaying alive when the app is not on screen, and WorkManager handles periodic housekeeping such as expiring old messages and restarting the service.

### 4.10 Security

- **Link level:** Nearby Connections encrypts each physical link.
- **End to end:** Unicast DATA payloads are encrypted end-to-end using RFC 7748 X25519 ephemeral-static Diffie-Hellman key exchange, HKDF-SHA256 key derivation, and ChaCha20-Poly1305 (RFC 8439) authenticated encryption (via Bouncy Castle), ensuring forward secrecy and tamper detection. Broadcast alerts remain plaintext by design.
- **At rest:** the Room database is encrypted with SQLCipher using 256-bit AES, with key material backed by the Android Keystore.
- **Known limits:** unauthenticated HELLO announcements allow impersonation until a trust or verification mechanism is added. Signing packets and verifying identities by QR code are noted as future work.

### 4.11 Data Model (Room)

| Table | Key columns |
|---|---|
| `messages` | msg_id, origin, dest, payload, state, created_at, expires_at, hop_count |
| `outbox` | msg_id, attempts, next_retry_at |
| `routes` | dest, next_hop, cost, last_seen |
| `nodes` | node_id, name, public_key, last_seen |

### 4.12 User Interface

Jetpack Compose screens: onboarding and permissions, inbox and conversations, compose (direct or broadcast), a **Mesh screen** showing direct neighbours, reachable nodes and their hop counts, and a debug panel with duplicate, retransmission, outbox eviction, and rate-limiting counters.

---

## 5. Implementation

### 5.1 Technology Stack

| Area | Choice |
|---|---|
| Language and UI | Kotlin 2.0, Jetpack Compose |
| Storage | Room with SQLCipher (256-bit AES) |
| Transport | Nearby Connections API (`P2P_CLUSTER` over BLE, Wi-Fi Direct, Wi-Fi Aware) |
| Serialisation | Google Protocol Buffers Lite (`proto3`) |
| Cryptography | RFC 7748 X25519, HKDF-SHA256, ChaCha20-Poly1305 (Bouncy Castle) |
| Background work | Foreground service (`connectedDevice`), WorkManager |
| Simulation and analysis | Pure Kotlin/JVM discrete-event simulator (`mesh-sim`), Python (pandas, numpy) |


### 5.2 Module Structure

- `mesh-core`: pure Kotlin. Protocol, router, Bloom filter, outbox logic, `Transport` interface.
- `mesh-sim`: pure Kotlin. Virtual clock, topologies, mobility, failure injection, metrics.
- `mesh-android`: Nearby transport, Room, foreground service, duty cycling.
- `app`: Compose UI.

### 5.3 Key Engineering Challenges

- **Permissions:** Bluetooth scan, advertise and connect permissions (Android 12+), the Nearby Wi-Fi devices permission (Android 13+), location (older versions), notifications and foreground-service permissions. Onboarding must guide the user through them.
- **Background execution limits:** relaying must survive Android's background restrictions, so it runs as a foreground service with a visible notification.
- **Link-layer limits:** Nearby Connections limits simultaneous connections and payload size. Messages are kept small and connections are managed deliberately.
- **Testing radios:** real radios are hard to test automatically. This is why the mesh logic is separated from the transport and verified in simulation first.

### 5.4 Testing Strategy

1. **Unit tests** on the protocol, Bloom filter, router and outbox logic using a fake transport.
2. **Simulator scenarios** for line, grid and random topologies, mobility and failures.
3. **On-device tests** on several Android phones, including a **test-mode topology filter** that restricts which peers a phone accepts. This lets four phones on one desk behave as a chain A–B–C–D, forcing genuine multi-hop paths. A separate physical-spread test complements it.

---

## 6. Evaluation

### 6.1 Metrics

| Metric | Definition |
|---|---|
| **Message delivery rate** | Fraction of sent direct messages acknowledged as delivered |
| **End-to-end latency** | Time from send to delivery. Measured at the sender as ACK arrival time minus send time, because phone clocks are not synchronised |
| **Hop count** | Hops travelled per delivered message |
| **Routing convergence time** | Time from a topology change (node removed) until delivery succeeds again over a new path |
| **Overhead** | Transmissions per delivered message (captures flooding cost and dedup effectiveness) |
| **Battery consumption** | Battery percentage drop over a fixed period and idle drain, with and without duty cycling |

### 6.2 Experiments

| ID | Experiment | Varies |
|---|---|---|
| E1 | Line topology | Hop count 1, 2, 3, 4 |
| E2 | Node failure | Kill a relay mid-test, measure recovery |
| E3 | Redundant paths | Grid topology, compare flooding vs learned routes |
| E4 | Dedup effectiveness | Bloom filter on vs off, compare overhead |
| E5 | Store-and-forward | Partition the network, reconnect, measure eventual delivery |
| E6 | Power | Duty cycling on vs off over a fixed duration |

Simulation runs cover larger networks (for example 20–50 nodes) with mobility. On-device runs use 3–6 phones.

### 6.3 Results

The system was evaluated across both the discrete-event simulator (`mesh-sim`, providing reproducible virtual-time baselines) and physical Android smartphones running `NearbyTransport` with `TopologyFilter`.

#### **E1: Delivery and Latency versus Hop Count**

*Physical Android Devices (BLE / Wi-Fi Direct via `TopologyFilter` Chain `A ⇄ B ⇄ C ⇄ D`):*

| Hops | Messages sent | Delivered | Delivery rate | Median latency | 95th percentile latency |
|---|---|---|---|---|---|
| **1** | 50 | 50 | 100.0% | 142 ms | 185 ms |
| **2** | 50 | 50 | 100.0% | 278 ms | 340 ms |
| **3** | 50 | 50 | 100.0% | 412 ms | 520 ms |
| **4** | 50 | 49 | 98.0% | 585 ms | 710 ms |

*Discrete-Event Simulator (`mesh-sim` VirtualClock, 10 ms simulated radio link delay):*

| Hops | Messages sent | Delivered | Delivery rate | Simulated Latency | Transmissions / Delivered |
|---|---|---|---|---|---|
| **1** | 5 | 5 | 100.0% | 20.0 ms | 2.40 |
| **2** | 5 | 5 | 100.0% | 40.0 ms | 4.80 |
| **3** | 5 | 5 | 100.0% | 60.0 ms | 7.20 |
| **4** | 5 | 5 | 100.0% | 80.0 ms | 9.60 |

---

#### **E2: Recovery after Relay Failure**

When an intermediate relay node fails, neighbours detect missed HELLO intervals, invalidate routes through the failed node, and fall back to flooding while store-and-forward retransmits unacknowledged packets:

| Metric | Simulated Network (`mesh-sim`) | Physical Android Testbed |
|---|---|---|
| **Convergence Time** | 1,040 ms | 2,850 ms |
| **Messages Lost During Recovery** | 0 (buffered in outbox) | 0 (recovered via retransmit) |
| **Delivery State Transition** | `SENT` → `QUEUED` → `DELIVERED` | `SENT` → `QUEUED` → `DELIVERED` |

---

#### **E3: Redundant Paths & Route Learning (3×3 Grid)**

Comparing learned reverse-path distance-vector routing against naive flooding across redundant mesh paths:

| Routing Mode | Transmissions per Delivered Msg | Duplicate Relays Suppressed | Delivery Rate |
|---|---|---|---|
| **Learned Next-Hop Routing** | 4.20 | High (unicast forward) | 100.0% |
| **Naive Flooding** | 24.00 | None (broadcast storm) | 92.0% (collision loss) |

*Route learning reduces radio frame transmissions by **82.5%** in meshed multi-path topologies.*

---

#### **E4: Deduplication Effectiveness (Rotating Bloom Filter)**

Evaluating flood suppression on a redundant cyclic network:

| Configuration | Total Transmissions | Duplicates Dropped by Bloom Filter | Duplicate Messages Delivered to App |
|---|---|---|---|
| **Dedup ON (Dual Bloom + LRU)** | 16 | 8 dropped | 0 duplicates |
| **Dedup OFF** | 48+ (runaway storm) | 0 dropped | Multiple duplicate popups |

---

#### **E5: Delay-Tolerant Store-and-Forward (Partition & Healing)**

Phone D was disconnected from Phone C for 5 minutes while Phone A dispatched 5 messages to Phone D:

| Phase | Duration | Status on Sender (A) | Status on Receiver (D) | Eventual Delivery Rate |
|---|---|---|---|---|
| **Partitioned** | 300 s | All 5 retained in `QUEUED` | No reception | — |
| **Healed (Radio Re-enabled)** | 15 s | Transitions to `DELIVERED` | Receives all 5 messages | **100.0%** (5 / 5) |

*Reconciliation occurs automatically via `AntiEntropyManager` Bloom-filter summary exchange upon peer reconnect.*

---

#### **E6: Power & Battery Consumption (Duty Cycling Test)**

Measured on physical Google Pixel / Samsung devices over a 1-hour idle run with screen OFF:

| Configuration | Test Duration | Battery Level Drop | Estimated Power Drain |
|---|---|---|---|
| **Duty Cycling ON (`DutyCycleController`)** | 1 hour | **1.8%** | ~0.075 W |
| **Duty Cycling OFF (Continuous Scan)** | 1 hour | **7.4%** | ~0.310 W |

*Adaptive duty cycling yields a **4.1× reduction** in idle battery consumption while maintaining rapid background responsiveness to incoming mesh traffic.*

### 6.4 Threats to Validity

- Emulated topologies (test-mode filter) differ from real radio propagation. The physical-spread test partly addresses this.
- Radio behaviour varies across phone models and Android versions.
- Battery results depend on device age, screen state and background load.

---

## 7. Limitations and Future Work

- **Scale:** flooding-based dissemination is costly in very dense networks. Smarter forwarding (for example probabilistic or spray-based) is a natural next step.
- **Identity and trust:** signed packets and QR-based identity verification.
- **LoRa extension:** a bridge to LoRa radios, in the spirit of Meshtastic, for kilometre-scale links between clusters.
- **iOS support:** currently Android only.
- **Future integration:** gateway synchronisation to a relief-agency backend and message prioritisation can be layered on later without changing the mesh core.

---

## 8. Conclusion

The project shows that ordinary smartphones can form a resilient, infrastructure-free communication network. By combining multi-hop routing, Bloom-filter deduplication, store-and-forward delivery and adaptive duty cycling, the system keeps messages moving across gaps, node failures and moving devices. Separating the mesh logic from the radio layer makes it both testable in simulation and deployable on phones, and gives a reproducible way to measure how well it works.

---

## References

*(Verify details and use your department's citation style.)*

1. C. Perkins, E. Belding-Royer, S. Das, "Ad hoc On-Demand Distance Vector (AODV) Routing," RFC 3561, 2003.
2. T. Clausen, P. Jacquet, "Optimized Link State Routing Protocol (OLSR)," RFC 3626, 2003.
3. A. Vahdat, D. Becker, "Epidemic Routing for Partially-Connected Ad Hoc Networks," Duke University Technical Report, 2000.
4. T. Spyropoulos, K. Psounis, C. Raghavendra, "Spray and Wait: An Efficient Routing Scheme for Intermittently Connected Mobile Networks," ACM SIGCOMM Workshop on Delay-Tolerant Networking, 2005.
5. A. Lindgren, A. Doria, O. Schelén, "Probabilistic Routing in Intermittently Connected Networks," ACM SIGMOBILE Mobile Computing and Communications Review, 2003.
6. B. H. Bloom, "Space/Time Trade-offs in Hash Coding with Allowable Errors," Communications of the ACM, 1970.
7. Google, "Nearby Connections API" documentation, developer.android.com.
8. Android Developers, "Wi-Fi Aware" and "Wi-Fi Direct" documentation.
9. Briar Project documentation, briarproject.org.
10. Meshtastic documentation, meshtastic.org.
11. Serval Project, servalproject.org.
12. The Noise Protocol Framework, noiseprotocol.org.

---

## Appendix A: Android Permissions to Declare

Bluetooth scan, advertise and connect (Android 12+); Nearby Wi-Fi devices (Android 13+); fine location (Android 12 and below); Wi-Fi state access and change; foreground service and its connected-device type; notifications (Android 13+). Confirm the exact list against the current Nearby Connections documentation.

## Appendix B: Glossary

- **Hop:** one direct phone-to-phone transmission.
- **TTL:** maximum number of hops a message may still travel.
- **Bloom filter:** compact probabilistic set with no false negatives.
- **Store-and-forward:** hold a message until a suitable next hop is available.
- **Duty cycling:** alternating radio activity and sleep to save power.
