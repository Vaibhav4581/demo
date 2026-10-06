# Decentralized Emergency Mesh Network

An offline-first, peer-to-peer messaging system for Android in which smartphones communicate directly over Bluetooth Low Energy and Wi-Fi (Wi-Fi Direct / Wi-Fi Aware via Google Nearby Connections `P2P_CLUSTER`) without cellular service, internet access, or central servers.

Every device acts simultaneously as an endpoint (sender/receiver) and an **autonomous relay**, forwarding packets hop-by-hop across dynamic ad hoc network topologies.

---

## 📱 Automated Cloud Builds & Download

Whenever updates are pushed, GitHub Actions automatically builds the latest debug APK and publishes release assets:

- **Direct APK Download:** [Download app-debug.apk](https://github.com/Vaibhav4581/demo/releases/latest/download/app-debug.apk)
- **QR Code Webpage:** [https://vaibhav4581.github.io/demo/](https://vaibhav4581.github.io/demo/) (open on laptop screen & scan with smartphone camera)

---

## Repository Structure

```
├── routing-app/             # Android & Mesh Routing Project (Open this folder in Android Studio)
│   ├── app/                 # Jetpack Compose UI Application (Conversations, Mesh visualization, Debug Panel)
│   ├── mesh-android/        # Android library (Nearby Connections Transport, Room + SQLCipher, MeshService)
│   ├── mesh-core/           # Pure Kotlin/JVM (Protocol, Router, Bloom Filter, Outbox, RateLimiter, Crypto)
│   ├── mesh-sim/            # Pure Kotlin/JVM discrete-event simulator (VirtualClock, metrics, CLI)
│   ├── gradle/              # Gradle Version Catalog (libs.versions.toml) & Wrapper (Gradle 8.14)
│   └── build.gradle.kts     # Root build configuration for routing-app
├── emergency-mesh-web/      # Vercel Landing Page & Web Portal (Live at demo-emergency-mesh-web.vercel.app)
├── web/                     # Direct GitHub Pages hosting assets
├── docs/                    # Architecture Decision Records (DECISIONS.md), Protocol Specs & Test Plan
└── analysis/                # Python scripts for parsing simulation data and multi-device experiment CSVs
```

### Module Isolation Rules
1. **`mesh-core`** has **zero** Android dependencies (`android.*` is prohibited). It compiles and tests purely on JDK 17 / 21 JVM.
2. All physical radio access occurs behind the abstract `Transport` contract; the routing engine is never tightly coupled to Android or Nearby Connections APIs.
3. **`mesh-sim`** depends only on `mesh-core` to run reproducible multi-hop network simulations in virtual time.

---

## Prerequisites

- **Java Development Kit**: JDK 17 or higher (compatible with Android Studio JetBrains Runtime / JBR 21).
- **Android SDK**: `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26` (Android 8.0 Oreo or higher).
- **Android Studio**: Open the `routing-app/` folder in Android Studio.

---

## Build & Test Commands

To build or run tests, first navigate to `routing-app/`:

```bash
cd routing-app
```

### Run All Unit Tests
```bash
./gradlew :mesh-core:test :mesh-sim:test :mesh-android:testDebugUnitTest :app:testDebugUnitTest
```

### Build Android APK
```bash
./gradlew :app:assembleDebug
```
The compiled APK will be located at `routing-app/app/build/outputs/apk/debug/app-debug.apk`.

### Run Discrete-Event Simulator CLI
```bash
./gradlew :mesh-sim:run --args="scenario=line hops=4 duration=600"
./gradlew :mesh-sim:run --args="scenario=partition"
./gradlew :mesh-sim:run --args="scenario=relay_failure"
```

### Full Project Build
```bash
./gradlew build
```

---

## Installing to Connected Physical Devices

To flash the application to multiple test devices via ADB:

```bash
# List connected device serials
adb devices

# Install APK onto all connected devices
adb -s <DEVICE_SERIAL_A> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_SERIAL_B> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_SERIAL_C> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_SERIAL_D> install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 4-Phone Scripted Demo Rehearsal Guide [HUMAN GATE 5]

This demonstration proves 3-hop multi-hop message delivery, hop count visualization, end-to-end encryption, emergency broadcast storm suppression, and store-and-forward partition recovery on 4 physical Android smartphones.

### Setup: Linear Chain Topology (`A ⇄ B ⇄ C ⇄ D`)

1. **Prepare Devices:**
   - Turn **Airplane Mode ON** on all 4 phones.
   - Manually turn **Bluetooth and Wi-Fi back ON**.
   - Launch the **Emergency Mesh** app on each device and grant required permissions.

2. **Configure Test Mode Filter (Debug Panel):**
   When devices are located in close physical proximity, configure the `TopologyFilter` to simulate a spatial chain topology:
   - On each phone, navigate to the **Debug Panel** tab and scroll to **Evaluation & Test Mode**.
   - Toggle **Test Mode Filter** to **ON**.
   - Read the 8-byte hexadecimal **Node ID** displayed at the top of each phone.
   - Configure permitted neighbours:
     - **Phone A:** Add **Phone B**'s Node ID.
     - **Phone B:** Add **Phone A** and **Phone C**'s Node IDs.
     - **Phone C:** Add **Phone B** and **Phone D**'s Node IDs.
     - **Phone D:** Add **Phone C**'s Node ID.

### Demo Execution Walkthrough

```
┌─────────┐         ┌─────────┐         ┌─────────┐         ┌─────────┐
│ Phone A │ <=====> │ Phone B │ <=====> │ Phone C │ <=====> │ Phone D │
│ Sender  │  Hop 1  │ Relay 1 │  Hop 2  │ Relay 2 │  Hop 3  │Recipient│
└─────────┘         └─────────┘         └─────────┘         └─────────┘
```

#### Step 1: Radio Activation & Discovery
- Tap **Start Radio** on Phones A, B, C, and D.
- Status changes to **ACTIVE**. Within 10–20 seconds, each phone discovers and connects to its allowed neighbours.
- In the **Mesh screen**, observe the topology graph updating: `A—B`, `B—C`, and `C—D`.

#### Step 2: 3-Hop Unicast Delivery & Automatic ACK (A → D)
- On **Phone A**, open the **Inbox** tab and select **Phone D** from the direct peer conversations list.
- Send the message: `"URGENT: Search and rescue team requested at Sector 7"`.
- **Observations:**
  - Phone A marks message state as **SENT**.
  - Phones B and C log `PACKET_RELAYED` in their Debug event stream.
  - Phone D vibrates and displays the message with the **"3 hops"** chip.
  - Phone D automatically sends an encrypted `ACK` back across the reverse route (`D → C → B → A`).
  - Phone A's status chip transitions from **SENT** to **DELIVERED** (emerald badge).

#### Step 3: Anti-Storm Emergency Broadcast
- On **Phone A**, switch to the **Emergency Broadcast** channel and send: `"TSUNAMI WARNING: Evacuate coastal areas immediately"`.
- **Observations:**
  - Phones B, C, and D all receive and display the broadcast alert.
  - Intermediate relays forward the broadcast, but rotating Bloom filters reject duplicated packets.
  - In the **Debug Panel**, observe the **DUPLICATES DROPPED** counter incrementing.
  - The token-bucket **RateLimiter** prevents runaway broadcast storms.

#### Step 4: Link Failure & Outbox Store-and-Forward Buffering
- Simulate an intermediate relay going offline: tap **Stop Radio** on **Phone C** (or turn off Bluetooth/Wi-Fi).
- On **Phone A**, send another message to Phone D: `"Supply caravan departure delayed by 30 minutes"`.
- **Observations:**
  - Phone A forwards to Phone B, but Phone B cannot reach Phone C or D.
  - On Phone A, the message remains stored in **QUEUED** status.
  - In the **Debug Panel**, the **OUTBOX QUEUE** counter increments.

#### Step 5: Partition Healing & Anti-Entropy Recovery
- On **Phone C**, tap **Start Radio** to restore the link.
- **Observations:**
  - Within 5–15 seconds, Phone C re-establishes links with Phone B and Phone D.
  - `AntiEntropyManager` exchanges `SYNC_SUMMARY` Bloom filter summaries.
  - The queued message stored on Phone B is automatically flushed through Phone C to Phone D.
  - Phone D receives and displays the delayed message.
  - Phone D issues an ACK back to Phone A; Phone A updates the message status to **DELIVERED**.

---

## Core Protocol & Hardening Details

- **Wire Protocol**: Compact Google Protocol Buffers Lite (`proto3`) framing with strict 64 KiB ceiling (`PacketCodec.MAX_PACKET_SIZE`).
- **Routing Engine**: Opportunistic distance-vector route learning with split-horizon flooding fallback (`Router`).
- **Loop & Storm Suppression**: Dual rotating Bloom filters (sized for 10,000 message IDs with a 1% false positive rate) backed by a 1,000-item exact LRU cache (`SeenCache`).
- **Rate Limiting**: Token bucket rate limiter (`RateLimiter`) bounding broadcast generation (5 msg/s) and packet forwarding (20 pkts/s) to prevent radio saturation.
- **Bounded Outbox**: Persistent store-and-forward queue with configurable capacity (default 500 packets) that purges expired packets and evicts oldest items to prevent memory exhaustion.
- **End-to-End Encryption**: Authenticated RFC 7748 X25519 ephemeral-static key exchange, HKDF-SHA256, and ChaCha20-Poly1305 authenticated encryption (Bouncy Castle) for unicast traffic.
- **Adaptive Duty Cycling**: `DutyCycleController` scales BLE/Wi-Fi scanning based on battery levels (Active, Idle Sleeping, Idle Scanning) to prevent battery drain while screen is off.
- **Encrypted Local Storage**: SQLCipher-backed Room database securing messages, nodes, and keys at rest with 256-bit AES encryption.
