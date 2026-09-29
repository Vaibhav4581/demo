# Mesh Network Wire Protocol Specification (v1)

## 1. Overview

The Decentralized Emergency Mesh Network protocol defines an ad-hoc, multi-hop, packet-based communication format for Android and JVM nodes exchanging messages directly over local radio links (Bluetooth Low Energy, Wi-Fi Direct, Nearby Connections) without cellular, internet, or centralized servers.

All messages are encoded in binary using **Protocol Buffers v3** (`mesh.v1.Packet`).

---

## 2. Packet Definition (`packet.proto`)

```proto
syntax = "proto3";

package mesh.v1;

option java_package = "mesh.protocol";
option java_multiple_files = true;

enum PacketType {
  PACKET_TYPE_UNSPECIFIED = 0;
  HELLO = 1;
  DATA = 2;
  ACK = 3;
  SYNC_SUMMARY = 4;
}

message Packet {
  bytes      msg_id        = 1;  // 16 random bytes
  bytes      origin        = 2;  // 8-byte node id
  bytes      dest          = 3;  // 8-byte node id; empty = broadcast
  PacketType type          = 4;  // Packet type enum
  uint32     ttl           = 5;  // Remaining hops (default: 8)
  uint32     hop_count     = 6;  // Hops travelled so far (default: 0)
  uint64     created_at_ms = 7;  // Unix epoch millisecond timestamp
  uint64     expires_at_ms = 8;  // Expiration epoch ms (default: +24h)
  bytes      payload       = 9;  // Payload contents (variable by type)
}
```

---

## 3. Fields & Semantics

| Field | Protobuf Type | Wire Bytes | Description |
|---|---|---|---|
| `msg_id` | `bytes` | Exactly 16 bytes | Cryptographically strong pseudo-random identifier uniquely identifying this transmission. Used by nodes for deduplication (SeenCache and Bloom filter) and ACK referencing. |
| `origin` | `bytes` | Exactly 8 bytes | `NodeId` of the originating author node. Does not change as the packet hops through intermediate relays. |
| `dest` | `bytes` | 0 or 8 bytes | `NodeId` of target recipient node. When empty (0 bytes), the packet is a **broadcast** message intended for all reachable nodes. When 8 bytes, it is a **unicast** message routed directly. |
| `type` | `PacketType` | varint | Enum indicating message class: `HELLO`, `DATA`, `ACK`, or `SYNC_SUMMARY`. `PACKET_TYPE_UNSPECIFIED` is rejected. |
| `ttl` | `uint32` | varint | Time-To-Live (remaining hops). Starts at `DEFAULT_TTL = 8`. Decremented by 1 at each forwarding hop. When `ttl == 0`, packet forwarding halts. |
| `hop_count` | `uint32` | varint | Hop count travelled from origin. Starts at 0 at the origin, incremented by 1 at each forwarding relay. Used by downstream nodes to learn distance-vector route costs (`route(origin) = (neighbour, hop_count, now)`). |
| `created_at_ms` | `uint64` | varint (fixed64) | Unix epoch timestamp in milliseconds when the packet was created. |
| `expires_at_ms` | `uint64` | varint (fixed64) | Expiration timestamp in milliseconds (`created_at_ms + 24 hours` by default). Packets past expiration are dropped and evicted from store-and-forward outboxes. |
| `payload` | `bytes` | 0 to 65,280 bytes | Packet body whose format is determined by `type`. |

---

## 4. Addressing & Node Identifiers (`NodeId`)

- Every node has a cryptographic identity keypair.
- A **`NodeId`** is exactly **8 bytes** (`64 bits`), derived by hashing the node's public key with **SHA-256** and taking the first 8 bytes:
  $$\text{NodeId} = \text{SHA-256}(\text{PublicKey})[0..7]$$
- On wire:
  - Unicast: `dest = <8 bytes>`
  - Broadcast: `dest = <0 bytes>` (empty `ByteString`)
- String representation: Lowercase 16-character hexadecimal string (e.g. `4a1f8c02b3e4d567`).

---

## 5. Packet Types & Payload Formats

### 5.1 `HELLO` (1)
- **Purpose:** Periodic local discovery between immediate radio neighbours (1 hop, `ttl = 1`).
- **Destination:** Broadcast (`dest = empty`).
- **Payload Structure (`HelloPayload`):**
  - Bytes `[0..1]`: Unsigned 16-bit big-endian integer $L$ indicating display name byte length.
  - Bytes `[2 .. 2+L-1]`: UTF-8 encoded human-readable display name.
  - Bytes `[2+L .. end]`: Node public key bytes.
- **Default TTL:** `1`.
- **Default Lifetime:** 60 seconds.

### 5.2 `DATA` (2)
- **Purpose:** Carries user chat or emergency message text.
- **Destination:** Unicast (`dest` set to peer's 8-byte `NodeId`) or Broadcast (`dest` empty).
- **Payload:** Message content. When encryption is active, payload contains the ciphertext and initialization vector; otherwise raw message bytes.
- **Default TTL:** `8`.
- **Default Lifetime:** 24 hours (`86,400,000 ms`).

### 5.3 `ACK` (3)
- **Purpose:** End-to-end delivery confirmation for direct (unicast) DATA packets.
- **Origin:** The receiving node confirming delivery.
- **Destination:** The original author node that sent the DATA packet.
- **Payload:** Exactly 16 bytes containing the `msg_id` of the acknowledged DATA packet.
- **Default TTL:** `8`.

### 5.4 `SYNC_SUMMARY` (4)
- **Purpose:** Anti-entropy reconciliation upon new peer connection.
- **Destination:** Direct to connected neighbour (`ttl = 1`).
- **Payload:** Serialized bytes of the node's held message ID Bloom filter.

---

## 6. Limits & Wire Safety

- **Maximum Packet Size:** `64 KiB` (`65,536 bytes`).
- **Maximum Payload Size:** `65,280 bytes` (`MAX_PACKET_SIZE - 256 bytes reserved for headers`).
- **Validation Rules on Decode:**
  1. Serialized length $\le 65,536$ bytes.
  2. Byte buffer cannot be empty.
  3. `msg_id` must be exactly 16 bytes.
  4. `origin` must be exactly 8 bytes.
  5. `dest` must be either 0 bytes (broadcast) or 8 bytes (unicast).
  6. `type` must be known and not `PACKET_TYPE_UNSPECIFIED`.
  7. `expires_at_ms >= created_at_ms`.
  8. `payload.size() <= MAX_PAYLOAD_SIZE`.
  Any packet violating these rules is rejected before processing.

---

## 7. Hop-by-Hop Forwarding Invariants

1. **Deduplication Check:** If `msg_id` is present in `SeenCache` or `RotatingBloom`, drop immediately.
2. **Local Delivery:** If `dest` matches current `NodeId` or is empty (broadcast), deliver to local application.
3. **ACK Generation:** If packet was unicast to this node, generate and route an `ACK` packet back to `origin`.
4. **Relay Check:** If `dest` is not this node and `ttl > 1`, decrement `ttl` by 1, increment `hop_count` by 1, and forward according to routing table. If `ttl <= 1`, drop packet.
5. **Split Horizon / No Loopback:** A packet is never transmitted back to the peer from which it was received.
