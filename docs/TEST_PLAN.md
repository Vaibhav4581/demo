# Test Plan & Verification Procedures

This document outlines human verification procedures and automated test protocols for the **Decentralized Emergency Mesh Network**.

---

## [HUMAN GATE 1]: Nearby Connections Two-Phone Direct Discovery & Packet Exchange

### Purpose
Verify that two physical Android devices running the app discover each other over local radios (BLE / Wi-Fi Direct) using Google Nearby Connections in `P2P_CLUSTER` mode without cellular or internet, automatically accept the connection, and exchange raw test packets.

### Prerequisites
- Two physical Android phones (running Android 8.0 / API 26 or higher, ideally Android 12+).
- Bluetooth and Wi-Fi enabled on both devices.
- Airplane mode enabled on both devices (with Bluetooth and Wi-Fi turned back on) or SIM cards removed / cellular data turned off to confirm pure off-grid communication.
- APK installed on both devices:
  ```bash
  ./gradlew :app:assembleDebug
  adb -s <device_1_serial> install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s <device_2_serial> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

---

### Step-by-Step Verification Checklist

| Step | Action | Expected Result | Pass / Fail |
|:---:|:---|:---|:---:|
| **1** | Open the **Emergency Mesh** app on **Device A** and **Device B**. | Both apps show "Local Node Identity" with Keystore-derived 8-byte `Node ID` hex and unique Display Name. | [ ] |
| **2** | If the "Permissions Required" banner appears, tap **Grant Mesh Permissions** on both devices. | System dialogs prompt for Bluetooth scan/advertise and Nearby Wi-Fi permissions. Grant all requested permissions. | [ ] |
| **3** | On **Device A**, tap **Start Radio**. | Badge changes to green **ACTIVE**. Link layer log shows `ADVERTISING_STARTED` and `DISCOVERY_STARTED`. | [ ] |
| **4** | On **Device B**, tap **Start Radio**. | Badge changes to green **ACTIVE**. Link layer log shows `ADVERTISING_STARTED` and `DISCOVERY_STARTED`. | [ ] |
| **5** | Keep both devices within 2-5 meters of each other and observe the screen for 5-15 seconds. | Both devices show `CONNECTION_INITIATED` and auto-accept. Within seconds, both screens log `PEER_CONNECTED` and display each other's Node ID under **Connected Peers (1)**. | [ ] |
| **6** | On **Device A**, tap **Send Test Ping**. | Device A logs `PACKET_SENT` and displays Toast "Sent test packet to 1 peers". Device B logs `PACKET_RECEIVED` with packet details. | [ ] |
| **7** | On **Device B**, tap **Send Test Ping**. | Device B logs `PACKET_SENT`. Device A logs `PACKET_RECEIVED` with matching message ID. | [ ] |
| **8** | On **Device A**, tap **Stop Radio**. | Device A shows radio `STOPPED`. Device B logs `PEER_DISCONNECTED`, and its Connected Peers count updates to 0. | [ ] |

---

### Failure Diagnosis & Troubleshooting
- **No discovery after 30 seconds:** Ensure Location Services (GPS) are toggled ON in Android system settings (some versions of Android require location services enabled for BLE beacon scanning).
- **Connection rejected or dropped:** Confirm both devices have unique Node IDs (check the hex displayed on screen).
- **Log inspection:** Check `adb logcat -s MeshTransport` on both devices to inspect structured link events.

---

## [HUMAN GATE 2]: 10-Minute Screen-Off Background Relay Verification

### Purpose
Verify that the `MeshService` foreground service (connected-device type) maintains active radio links and continues relaying messages between nearby nodes while the screen is completely turned off and the device is idle for at least 10 minutes.

### Prerequisites
- Three Android devices (Device A = Sender, Device B = Relay, Device C = Receiver) or Two devices (Device A and Device B).
- Android OS battery optimisation exempted or prompted for the app.
- Devices running `MeshService` in foreground service mode.

---

### Step-by-Step Verification Checklist

| Step | Action | Expected Result | Pass / Fail |
|:---:|:---|:---|:---:|
| **1** | Start **MeshService** on all test devices by tapping **Start Service**. | Notification drawer displays persistent notification: *"Emergency Mesh Active — Connected peers: X"*. | [ ] |
| **2** | Confirm peer connections form between the devices. | All devices show active connected peer counts. | [ ] |
| **3** | Turn the screen **OFF** on **Device B** (the relay phone) using its physical power button. | Device B screen is blank. Device B enters background execution. | [ ] |
| **4** | Keep Device B with screen off and idle for **10 minutes**. | Android OS does not kill `MeshService` due to foreground service exemption. | [ ] |
| **5** | After 10 minutes (with Device B screen still OFF), send a test packet from **Device A**. | Device A transmits packet. Device B relays the packet in background. Device C (or Device B if direct) receives the message. | [ ] |
| **6** | Turn on Device B screen and inspect the **Link Layer Event Log**. | Device B log confirms packet was received and processed while screen was OFF with no crash or disconnection. | [ ] |
| **7** | Verify battery consumption: inspect battery drain over the 10-minute idle window. | Idle battery drain remains below ~1-2% per hour due to `DutyCycleController` sleep cycling. | [ ] |

---

## [HUMAN GATE 3]: Full Jetpack Compose User Interface Walkthrough

### Purpose
Verify the end-to-end user experience on real physical devices across all screens:
1. Onboarding, permissions banner, and profile display name editing.
2. Emergency broadcast messaging vs 1-on-1 direct peer conversation.
3. Real-time per-message status chips (`QUEUED`, `SENT`, `RELAYED`, `DELIVERED`, `EXPIRED`) and hop-count badges (`0 hops (Broadcast)`, `1 hop (Direct)`, `N hops`).
4. Mesh Topology screen (1-hop direct neighbours, distance-vector routing table, and visual topology canvas).
5. Debug panel (anti-storm deduplication counters, outbox queue length, retransmission metrics, duty-cycle state, and link event logs).

---

### Prerequisites
- Two or three physical Android devices with Bluetooth and Wi-Fi enabled.
- Fresh debug APK installed:
  ```bash
  ./gradlew :app:assembleDebug
  adb -s <device_serial> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

---

### Step-by-Step Verification Checklist

| Step | Screen / Feature | Action | Expected Result | Pass / Fail |
|:---:|:---|:---|:---|:---:|
| **1** | **Onboarding & Identity** | Launch app. Tap on display name in the top header card. | Onboarding modal opens showing current display name, cryptographic Node ID, and security note. Change name to "Alpha-1" and tap "Save Profile". Header immediately updates to "Alpha-1". | [ ] |
| **2** | **Permissions Banner** | If permissions are missing, tap the red permissions banner at top. | Runtime dialog prompts for Bluetooth & Nearby Wi-Fi permissions. Grant permissions. Red banner immediately disappears. | [ ] |
| **3** | **Radio Activation** | Navigate to the **Debug** tab. Tap **Start Service**. | Persistent foreground notification appears. Mesh status badge in header switches to green **MESH ON**. Duty cycle card shows **RADIO ACTIVE**. | [ ] |
| **4** | **Emergency Broadcast** | In **Inbox** tab, tap the top crimson card: **Emergency Broadcast Channel**. Type "MAYDAY: Medical kit needed at Sector 4" and tap Send. | Message appears in chat with teal bubble. Status chip displays **QUEUED** then updates to **DELIVERED** with **📡 Broadcast** chip. | [ ] |
| **5** | **Broadcast Reception** | Inspect **Device B**. | Device B receives the broadcast immediately. Opening Emergency Broadcast shows incoming message from Device A's Node ID with crimson bubble and timestamp. | [ ] |
| **6** | **Peer Discovery & 1:1 Chat** | In Device A's **Inbox**, wait 5-15 seconds for BLE/Wi-Fi discovery. | Device B appears under **Direct 1:1 Conversations** with an avatar and green **⚡ 1-HOP DIRECT** badge. | [ ] |
| **7** | **Direct Unicast & ACK** | Tap Device B's conversation row on Device A. Type "Direct status check" and tap Send. | Device A message shows **SENT** then transitions to **DELIVERED** upon receiving ACK. Hop count chip displays **⚡ Direct (1 hop)**. | [ ] |
| **8** | **Mesh Topology Screen** | Tap the **Mesh** tab at the bottom navigation bar. | Screen displays: <br>• **1-HOP PEERS** counter (1) and **MULTI-HOP** counter.<br>• Interactive **Visual Mesh Topology** canvas rendering local node (Cyan) linked to peer (Emerald).<br>• Direct 1-hop card showing peer ID and "Chat" button. | [ ] |
| **9** | **Debug & Health Panel** | Tap the **Debug** tab at the bottom navigation bar. | Screen displays 2x2 health metrics grid: <br>• **DUPLICATES DROPPED** (anti-storm filter)<br>• **RETRANSMISSIONS**<br>• **OUTBOX QUEUE**<br>• **BATTERY LEVEL**<br>• Scrolling **LINK LAYER LOG** with millisecond timestamps. | [ ] |
| **10** | **Manual Diagnostics** | In Debug panel, tap **Force HELLO** and **Purge Expired**. | Toast notifications confirm HELLO broadcast and expired store purge with zero crashes. | [ ] |

---

## [HUMAN GATE 4]: Real-Device Evaluation Experiments (E1 to E6)

### Purpose
Execute the experimental evaluations defined in **Section 6 of `PROJECT_REPORT.md`** across physical Android phones (3 to 6 devices). Generate real measured CSV logs and process them using Python tools to populate the report tables. **Never fabricate or estimate results.**

---

### Experiment Protocols

#### **E1: Line Topology (Delivery & Latency vs Hop Count)**
- **Setup:** 4 phones on desk (A, B, C, D).
- **TopologyFilter:** Enable on each device:
  - Phone A allows [B]
  - Phone B allows [A, C]
  - Phone C allows [B, D]
  - Phone D allows [C]
- **Procedure:**
  1. Verify chain forms: A <-> B <-> C <-> D.
  2. Send 20 direct messages from A to B (1 hop), record latency and delivery.
  3. Send 20 direct messages from A to C (2 hops), record latency and delivery.
  4. Send 20 direct messages from A to D (3 hops), record latency and delivery.
  5. In Debug Panel, tap **Export CSV**.
  6. Pull logs: `adb pull /sdcard/Android/data/org.mesh.emergency/files/experiments/ ./analysis/data/E1/`

#### **E2: Node Failure & Routing Recovery**
- **Setup:** Grid / Redundant topology (A connects to B and C; D connects to B and C).
- **Procedure:**
  1. Start traffic from A to D at 1 pkt/sec.
  2. Kill Phone B (turn off radio or exit app).
  3. Observe A switching route to D via C.
  4. Measure routing convergence time (time until next successful ACK).
  5. Export CSV and pull to `./analysis/data/E2/`.

#### **E3: Redundant Paths (Learned Routes vs Flooding)**
- **Procedure:** Compare transmissions per delivered packet with dynamic route learning enabled versus pure flooding.

#### **E4: Deduplication Effectiveness (Bloom Filter On vs Off)**
- **Procedure:**
  1. Broadcast 50 messages across 4 connected devices.
  2. Note **Duplicates Dropped** counter in Debug panel.
  3. Verify zero duplicate deliveries in user inbox.
  4. Export CSV and pull to `./analysis/data/E4/`.

#### **E5: Delay-Tolerant Store-and-Forward (Partition & Heal)**
- **Procedure:**
  1. Separate Phone D out of range of Phone C (or disconnect link via TopologyFilter).
  2. Send 5 messages from A to D.
  3. Observe status remains **QUEUED** in Outbox.
  4. Reconnect Phone D.
  5. Observe anti-entropy sync summary exchange and all 5 messages transition to **DELIVERED** upon ACK.

#### **E6: Power & Battery Consumption (Duty Cycling Test)**
- **Duration:** 1 hour per run, screen OFF.
- **Run 1 (Duty Cycling ON):** `MeshService` running with `DutyCycleController` active.
- **Run 2 (Duty Cycling OFF):** Continuous BLE/Wi-Fi scanning without sleep.
- **Measurements:**
  - Record battery percentage before and after.
  - Run `adb shell dumpsys batterystats org.mesh.emergency > batterystats_e6.txt`.

---

### Log Aggregation & Report Generation
Once experiment CSVs are pulled:
```bash
python analysis/merge_device_experiments.py analysis/data/
```
This produces the formatted summary tables to paste into Section 6.3 of `PROJECT_REPORT.md`.


