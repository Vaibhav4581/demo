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

