# Architecture & Design Decisions

This document tracks all key technical choices, library selections, and design rationales for the **Decentralized Emergency Mesh Network**.

---

## ADR 001: Multi-Module Architecture & JVM Core Isolation

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:**
  Disaster mesh networks must be testable without requiring continuous flashing to physical hardware or booting multiple Android emulators. Real ad hoc radio environments are non-deterministic and difficult to automate in CI.
- **Decision:**
  Separate the codebase into four modules:
  - `mesh-core`: Pure Kotlin JVM. Houses the routing table, packet definitions, deduplication (Bloom filter), store-and-forward outbox, and abstract `Transport` contract. No Android dependencies (`android.*`) permitted.
  - `mesh-sim`: Pure Kotlin JVM. Implements discrete-event scheduling (`VirtualClock`), simulated node mobilities, link latency/loss, and metric collection.
  - `mesh-android`: Android library implementing `NearbyTransport` using Google Nearby Connections API, Room + SQLCipher persistence, and background `MeshService`.
  - `app`: Jetpack Compose UI application.
- **Consequences:**
  Protocol and routing tests run in milliseconds on standard JVM test runners. The exact same router runs on real phones and in the simulator.

---

## ADR 002: Toolchain & Baseline Versions

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:**
  Need stable, modern Android & Kotlin toolchain compatibility across Windows, Linux, and macOS.
- **Decision:**
  - **Gradle:** 8.14 (utilizes pre-cached local distribution).
  - **Android Gradle Plugin (AGP):** 8.5.1.
  - **Kotlin:** 2.0.21 (with official Compose compiler plugin).
  - **JDK Target:** Java 17 LTS (compatible with Android Studio JBR OpenJDK 21 and AGP 8.5+).
  - **Android SDK:** `minSdk = 26` (Android 8.0 Oreo, covers 95%+ of active devices and provides modern BLE/Wi-Fi APIs), `compileSdk = 34`, `targetSdk = 34`.

---

## ADR 003: Centralized Version Catalog (`libs.versions.toml`)

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:**
  Dependencies shared across `mesh-core`, `mesh-sim`, `mesh-android`, and `app` must be pinned and aligned.
- **Decision:**
  All versions, dependencies, and plugins are defined in `gradle/libs.versions.toml`. Build scripts access them via typesafe accessors (`libs.plugins...`, `libs....`).

---

## ADR 004: Deduplication & Anti-Storm Strategy

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:**
  Flooding in mesh topologies produces broadcast storms and loops if packets are repeatedly forwarded.
- **Decision:**
  Implement dual rotating Bloom filters (sized for 10,000 message IDs with a target 1% false positive rate) backed by a 1,000-item exact LRU cache (`SeenCache`). This bounds memory to ~12 KB while providing quick duplicate rejection.

---

## ADR 005: Protobuf Lite Wire Protocol & Node Identity

- **Date:** 2026-09-20
- **Status:** Accepted
- **Context:**
  Peer-to-peer radio transmissions (BLE / Wi-Fi Direct / Nearby Connections) have constrained bandwidth and frame sizes. Packet framing must be compact, strongly typed, deterministic across JVM and Android, and safe against malformed frames.
- **Decision:**
  - Use official Google Protocol Buffers (`proto3`) with Java Lite runtime (`com.google.protobuf:protobuf-javalite:4.28.3`) to minimize method count and DEX size while preserving binary wire performance.
  - Node IDs (`NodeId`) are fixed 8-byte identifiers derived from `SHA-256(PublicKey)[0..7]`.
  - Message IDs (`msg_id`) are fixed 16-byte random identifiers.
  - Broadcast is modeled natively as an empty `dest` byte array.
  - Enforce a 64 KiB ceiling (`MAX_PACKET_SIZE`) and 65,280 byte payload limit (`MAX_PAYLOAD_SIZE`) with strict boundary validation in `PacketCodec`.
- **Consequences:**
  Eliminates reflection overhead, ensures small binary footprint on Android, and enables seamless serialization between JVM simulator and Android devices.

---

## ADR 006: Google Nearby Connections Transport (`P2P_CLUSTER`)

- **Date:** 2026-09-29
- **Status:** Accepted
- **Context:**
  The mesh network requires spontaneous, off-grid communication between Android devices over physical radios (BLE, Wi-Fi Direct, Wi-Fi Aware). The link-layer transport must discover peers concurrently, form multi-peer clusters, auto-negotiate connections, and handle intermittent link losses and connection collisions.
- **Decision:**
  - Implement `NearbyTransport : Transport` leveraging Google Play Services Nearby Connections API.
  - Employ `Strategy.P2P_CLUSTER` (many-to-many mesh topology) allowing concurrent advertising and discovery on all nodes.
  - Endpoints advertise their 16-character hexadecimal `NodeId` as their endpoint name, enabling immediate peer recognition.
  - Break connection initiation races symmetrically using lexicographical NodeId tie-breaking (`localNodeId.toHex() < peerHex`), ensuring only one node initiates the connection request.
  - Detect and drop duplicate connection endpoints if two physical links form between the same peer pair, preventing duplicated `onPeerConnected` events.
  - Strictly enforce `PacketCodec.MAX_PACKET_SIZE` (64 KiB) ceiling on all outbound and inbound frames to prevent buffer overflows or radio frame fragmentation issues.
  - Implement structured logging (`TransportLogger`) for all advertising, discovery, connection lifecycle, and packet transfer events with millisecond timestamps.
- **Consequences:**
  Provides robust physical link management across diverse Android hardware while keeping core routing logic strictly isolated behind the clean, JVM-testable `Transport` contract.

---

## ADR 007: Foreground Service, Adaptive Duty Cycling & Housekeeping

- **Date:** 2026-09-29
- **Status:** Accepted
- **Context:**
  Android aggressively terminates background processes and suspends BLE/Wi-Fi scanning when the screen turns off or under OEM battery optimizations. At the same time, continuous unthrottled scanning drains battery rapidly during disasters.
- **Decision:**
  - Implement `MeshService` as an Android `connectedDevice` foreground service with a persistent notification.
  - Implement `DutyCycleController` managing state transitions:
    - **ACTIVE:** Continuous scanning and advertising during topology changes or active message traffic.
    - **IDLE_SLEEPING:** Halts discovery scanning when the neighbourhood is stable and inactive, reducing idle radio power consumption.
    - **IDLE_SCANNING:** Periodic 10-second scan window to detect newly arriving nodes.
  - Dynamically scale sleep intervals based on battery levels (30s normal, 90s at <=20%, 150s at <=10%).
  - Return immediately to `ACTIVE` whenever new traffic occurs, a link drops, or manual wake up is triggered.
  - Implement `HousekeepingWorker` (WorkManager) running every 15 minutes to purge expired messages, rotate dual Bloom filters, and revive `MeshService` if killed.
- **Consequences:**
  Guarantees unbroken multi-hop relaying with the screen turned off while keeping idle power consumption sustainable over extended disaster recovery periods.

---

## ADR 008: Jetpack Compose User Interface, Navigation Architecture & Hop-Count Visualization

- **Date:** 2026-09-29
- **Status:** Accepted
- **Context:**
  In a disaster scenario without infrastructure, users require immediate clarity on:
  1. Distinguishing emergency broadcasts (propagating to all reachable nodes) from 1-on-1 direct peer communications.
  2. Clear feedback on message delivery states (QUEUED in store-and-forward, SENT across link, RELAYED by intermediate hops, DELIVERED with recipient ACK confirmation, or EXPIRED).
  3. Physical mesh topology visibility (who is a direct 1-hop link vs who requires multi-hop relaying).
  4. Real-time network engine health (duplicates dropped by anti-storm Bloom filter, outbox queue length, and battery-aware duty cycling state).
  5. Low power consumption on OLED/AMOLED screens.
- **Decision:**
  - Build the user interface with Jetpack Compose using a battery-efficient Slate Dark theme (`#090D16` / `#0F172A`).
  - Implement a 3-tab navigation hierarchy:
    - **Inbox:** Persistent Emergency Broadcast channel + Discovered 1-on-1 direct peer chats with reachability badges (`1-HOP DIRECT`, `N HOPS`, `STORE-FORWARD`).
    - **Mesh Screen:** Overview of direct 1-hop neighbours from `NeighbourTable`, known distance-vector reachable nodes from `RouteTable`, and a visual canvas graph rendering topological links.
    - **Debug Panel:** Real-time health metrics grid (duplicates dropped, outbox queue, retransmissions, duty cycle FSM state, battery level), diagnostic action triggers (force HELLO, purge store), and live link-layer event log inspector.
  - Implement per-message status chips with a distinct color hierarchy (QUEUED: Amber, SENT: Blue, RELAYED: Purple, DELIVERED: Emerald, EXPIRED: Rose) and hop count chips (`0 hops (Broadcast)`, `1 hop (Direct)`, `N hops`).
  - Store and propagate `hop_count` from protobuf packets into Room encrypted database (`MessageEntity`) and domain `MessageRecord`.
- **Consequences:**
  Provides a clean, intuitive, and battery-conserving interface that makes complex multi-hop mesh routing and store-and-forward dynamics transparent to end users.

---

## ADR 009: End-to-End Encryption with X25519, HKDF-SHA256, and ChaCha20-Poly1305 (Bouncy Castle)

- **Date:** 2026-10-04
- **Status:** Accepted
- **Context:**
  In a multi-hop disaster network, intermediate relay nodes forward packets on behalf of other devices. To protect message privacy, unicast DATA packet payloads must be encrypted end-to-end to the recipient's public key so that intermediaries cannot inspect or tamper with payloads. Furthermore, key material must be compact to fit the 32-byte public key field in `HelloPayload` without bloated serialization wrappers or conflicting Protobuf runtimes (such as Tink's `protobuf-java` collision with `protobuf-javalite`).
- **Decision:**
  - Implement `X25519Crypto : Crypto` using Bouncy Castle (`org.bouncycastle:bcprov-jdk18on:1.78.1`).
  - Key pairs (`MeshKeyPair`) are standard RFC 7748 X25519 (32-byte private and public keys).
  - Unicast payload encryption employs an ephemeral-static Diffie-Hellman exchange:
    1. A fresh ephemeral X25519 key pair is generated per message (ensuring forward secrecy).
    2. Diffie-Hellman agreement is computed against the recipient's 32-byte public key.
    3. HKDF-SHA256 derives a 32-byte symmetric key and 12-byte nonce bound to context string `"mesh-v1-e2e"`.
    4. Authenticated encryption is performed using ChaCha20-Poly1305 (RFC 8439) with a 128-bit MAC tag.
    5. Wire format is `[32 bytes ephemeral public key] + [ciphertext + 16-byte Poly1305 tag]`.
  - Discovered peer public keys from HELLO packets are automatically registered in Room's encrypted `nodes` table via `RoomKeyStore`.
  - Broadcast packets remain unencrypted by design so all mesh participants can receive emergency alerts.
- **Consequences:**
  Provides authenticated, forward-secret end-to-end encryption with zero external dependencies or Protobuf runtime collisions across standard JVM and Android API 26+.


