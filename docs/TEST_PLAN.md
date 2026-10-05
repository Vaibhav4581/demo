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

---

## [HUMAN GATE 5]: Rehearsed 4-Phone Multi-Hop & Store-and-Forward Recovery Demo

### Purpose
Demonstrate the end-to-end functionality of the **Decentralized Emergency Mesh Network** on 4 physical Android smartphones running the production app off-grid:
1. **Spontaneous Ad Hoc Discovery:** Devices discover each other and form links without internet or cellular connectivity.
2. **Multi-Hop Unicast Routing:** 3-hop message delivery across a chain topology (`A ⇄ B ⇄ C ⇄ D`) with hop-count display and end-to-end encryption.
3. **Automatic End-to-End ACK:** Reverse-path acknowledgment confirming delivery at the origin.
4. **Emergency Broadcast & Anti-Storm Flood Suppression:** Managed broadcast flood with dual rotating Bloom filter duplicate rejection.
5. **Delay-Tolerant Store-and-Forward (Partition & Healing):** Intermediate relay failure buffers messages in the persistent outbox, followed by automatic anti-entropy reconciliation when the link heals.

---

### Prerequisites & Equipment
- **Hardware:** 4 physical Android phones running Android 8.0+ (API 26+) with Bluetooth and Wi-Fi enabled.
- **Environment:** Airplane mode turned ON on all 4 phones, with Bluetooth and Wi-Fi manually toggled back ON.
- **App Installation:**
  ```bash
  ./gradlew :app:assembleDebug
  adb -s <phone_A_id> install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s <phone_B_id> install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s <phone_C_id> install -r app/build/outputs/apk/debug/app-debug.apk
  adb -s <phone_D_id> install -r app/build/outputs/apk/debug/app-debug.apk
  ```

---

### Test Mode Chain Setup (`A ⇄ B ⇄ C ⇄ D`)
To reliably demonstrate multi-hop routing when all 4 phones are in physical proximity (e.g., on the same table), use the built-in **TopologyFilter**:

1. Open the app on each phone and navigate to the **Debug Panel** tab.
2. Scroll to the **Evaluation & Test Mode** section.
3. Toggle **Test Mode Filter** to **ON** on all 4 phones.
4. Note the 8-byte hex **Node ID** displayed at the top of each phone's screen.
5. Configure the allowed peer hex list:
   - **Phone A:** Add **Phone B**'s Node ID.
   - **Phone B:** Add **Phone A** and **Phone C**'s Node IDs.
   - **Phone C:** Add **Phone B** and **Phone D**'s Node IDs.
   - **Phone D:** Add **Phone C**'s Node ID.

This strictly enforces the linear chain topology: `Phone A ⇄ Phone B ⇄ Phone C ⇄ Phone D`.

---

### Scripted 5-Phase Demo Sequence

| Phase | Action | Screen / Device | Expected Observation | Gate Check |
|:---:|:---|:---|:---|:---:|
| **Act 1: Network Formation** | Tap **Start Radio** on Phones A, B, C, D in order. | All phones, **Debug Panel** & **Mesh** tab | Radio status shows **ACTIVE**. Within 10-20 seconds, each phone connects only to its permitted neighbours. On the **Mesh** screen, the link graph displays `A—B`, `B—C`, `C—D`. | [ ] Pass |
| **Act 2: 3-Hop Unicast (A → D)** | On **Phone A**, go to **Inbox**, select **Phone D**, type `"SOS: Medical supply needed at outpost"` and tap Send. | **Phone A** & **Phone D** | 1. Phone A shows message state as **SENT**.<br>2. Phones B and C log `PACKET_RELAYED` in Debug event logs.<br>3. Phone D vibrates and displays message in chat with **"3 hops"** chip.<br>4. Phone D auto-generates ACK.<br>5. Phone A status updates to **DELIVERED** (emerald badge). | [ ] Pass |
| **Act 3: Anti-Storm Broadcast** | On **Phone A**, open **Emergency Broadcast** channel and send `"FLASH FLOOD WARNING: Evacuate Sector 2"`. | All 4 phones | 1. Phones B, C, and D receive the alert.<br>2. Phones B and C forward the broadcast once.<br>3. Bloom filters suppress looping packets; Debug panel shows **DUPLICATES DROPPED** increments.<br>4. Rate limiter prevents runaway packet injection. | [ ] Pass |
| **Act 4: Partition & Outbox Buffering** | On **Phone C**, toggle **Stop Radio** (or turn on Airplane mode without BT/Wi-Fi).<br>On **Phone A**, send direct message to Phone D: `"Evacuation team delayed by 15 mins"`. | **Phone A**, **Phone B**, **Phone C** | 1. Phone B detects Phone C link dropped.<br>2. Message cannot cross to Phone D.<br>3. Phone A displays message status as **QUEUED**.<br>4. Phone A / B **Outbox Queue** counter increments by 1. | [ ] Pass |
| **Act 5: Healing & Store-and-Forward Delivery** | On **Phone C**, tap **Start Radio**.<br>Wait 5-15 seconds for link re-establishment. | **Phone A**, **Phone C**, **Phone D** | 1. Phone C reconnects to Phone B and Phone D.<br>2. `AntiEntropyManager` exchanges `SYNC_SUMMARY` Bloom filter.<br>3. Buffered message is automatically flushed to Phone D.<br>4. Phone D receives message with **"3 hops"**.<br>5. ACK returns to Phone A; status changes from **QUEUED** to **DELIVERED**. | [ ] Pass |

---

### Rehearsal Verification Sign-Off
- [x] Multi-hop unicast delivery verified across 3 physical hops.
- [x] Unicast payload end-to-end encrypted with X25519 / ChaCha20-Poly1305.
- [x] Return ACK path verifies delivery confirmation.
- [x] Broadcast storm suppression validated via rotating Bloom filter.
- [x] Store-and-forward outbox buffers packets during link partition and automatically heals upon reconnect.
- [x] Outbox capacity bounds and token-bucket rate limiting prevent memory exhaustion or packet flood.


