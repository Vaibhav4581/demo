# Decentralized Emergency Mesh Network

An offline-first, peer-to-peer messaging system for Android in which smartphones communicate directly over Bluetooth Low Energy and Wi-Fi (Wi-Fi Direct / Wi-Fi Aware) without cellular service, internet access, or central servers.

Every device acts simultaneously as a sender, a receiver, and a **relay**, forwarding packets hop-by-hop across an ad hoc network topology.

---

## 📱 Automated Cloud Builds & Easy Testing

Whenever new updates are pushed to GitHub, GitHub Actions automatically builds the latest debug APK and publishes it:

- **Direct Download Link:** [Download app-debug.apk](https://github.com/Vaibhav4581/demo/releases/latest/download/app-debug.apk)
- **QR Code Webpage:** [https://vaibhav4581.github.io/demo/](https://vaibhav4581.github.io/demo/) (open on laptop screen & scan with phone camera)

---

## Architecture & Module Structure

The project is structured into 4 decoupled Gradle modules:

```
├── mesh-core/               # Pure Kotlin/JVM (Protocol, Router, Bloom Filter, Outbox, Transport interface)
├── mesh-sim/                # Pure Kotlin/JVM discrete-event network simulator (VirtualClock, metrics, CLI)
├── mesh-android/            # Android library (Nearby Connections Transport, Room + SQLCipher, MeshService)
├── app/                     # Jetpack Compose UI Application (Conversations, Mesh visualization, Debug)
├── gradle/                  # Gradle Version Catalog (libs.versions.toml) & Wrapper
├── docs/                    # Architecture Decision Records (DECISIONS.md), Protocol & Test plans
└── analysis/                # Python scripts for plotting simulation & real-device evaluation metrics
```

### Module Isolation Rules
1. **`mesh-core`** has zero Android dependencies and targets the plain JVM (JDK 17). It compiles and tests independently.
2. All radio hardware access occurs via the abstract `Transport` interface; the routing engine is never tightly coupled to Android or Nearby APIs.
3. **`mesh-sim`** depends only on `mesh-core` to run reproducible multi-hop network simulations in virtual time.

---

## Prerequisites

- **Java Development Kit**: JDK 17 or higher (or Android Studio bundled JetBrains Runtime / JBR).
- **Android SDK**: `compileSdk = 34`, `minSdk = 26`.
- **Android Studio**: Android Studio Koala / Ladybug or newer.

---

## Build & Test Commands

### Run Unit Tests on JVM Modules
```bash
./gradlew :mesh-core:test :mesh-sim:test
```

### Build Android App & Library
```bash
./gradlew assembleDebug
```

### Run Simulator CLI
```bash
./gradlew :mesh-sim:run --args="scenario=line hops=4"
```

### Full Project Build
```bash
./gradlew build
```

---

## Key Design Principles
- **Multi-Hop Delivery**: Managed flooding for broadcast and reverse-path route learning for unicast.
- **Loop & Storm Suppression**: Dual rotating Bloom filters + exact LRU cache.
- **Store-and-Forward**: Persistent outbox and anti-entropy Bloom filter exchange on peer contact.
- **Power Efficiency**: Adaptive duty cycling (`DutyCycleController`) to minimize radio drain during idle intervals.
