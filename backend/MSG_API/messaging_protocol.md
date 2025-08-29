# Goal and threat model

Anonymous accounts, QR-based contact exchange, and end-to-end encryption on ECC. The server should never see plaintext and shouldn’t be able to impersonate users. Contacts must be established securely even if the network is hostile.

---

# Keys, identities, and what data to store

## Client-side key material
- **Identity key (IK):** Long-term Ed25519 signing keypair. Lives on device. Public part is the user’s stable identity fingerprint.
- **Signed prekey (SPK):** Medium-term X25519 keypair, rotated periodically (e.g., every 7–30 days). Public SPK is signed by IK.
- **One-time prekeys (OPKs):** Batch of X25519 public keys, consumed one-per-new-contact to enable asynchronous starts.
- **Ephemeral DH keys (EK):** Fresh X25519 key generated per session start.

## Server-stored data (public only)
- **User record:** uuid, deviceId (if multi-device), IK public key, SPK public key, SPK signature (by IK), list of OPK public keys.
- **Message queue:** ciphertext, headers, metadata required for delivery only.
- **No plaintext:** Never store plaintext message content on the server.

## Client-stored data
- **Contacts:** uuid → verified IK public key + trust state (verified, unverified, changed).
- **Session state:** Double Ratchet state per peer (root chain key, sending/receiving chains, counters).
- **Audit:** Safety number (fingerprint) for each contact.

---

# What goes in the QR and what does not

QR should be an “out-of-band trust anchor.” Don’t put a raw JWT alone; you need a cryptographic binding to the user’s identity key.

Include:
- **uuid:** Stable user identifier.
- **deviceId:** If you support multiple devices per account.
- **IK public key:** Ed25519 public key (base64/hex).
- **SPK public key:** X25519 public key (base64/hex).
- **SPK signature:** Signature over SPK by IK (Ed25519).
- **Attestation (optional but strong):** Server-signed assertion binding uuid ↔ IK, with expiry (like a short-lived “user card” signed by the server’s public key).
- **Fingerprint / safety code:** Short human-readable checksum of IK (e.g., 60-bit SAS, displayed as words/digits).

Do not include:
- **JWT as the only proof:** JWT is for server auth, not peer identity. If you include a JWT, treat it as optional, short-lived, and never as identity.
- **Private keys or session secrets:** Never.

Example QR payload (signed-bundle JSON, minified for size):

```json
{
  "v": 1,
  "uuid": "b2f2c1e0-...-...",
  "deviceId": "d1",
  "ik_pub": "base64-Ed25519",
  "spk_pub": "base64-X25519",
  "spk_sig": "base64-Ed25519(sig over spk_pub)",
  "att": {
    "exp": 1699999999,
    "sig": "base64-server-signature-over{uuid,ik_pub,spk_pub,exp}"
  },
  "fp": "safety-code-words-or-hex"
}
```

---

# Contact establishment flows

You have two robust options. Support both for better UX.

## A) Online (Signal-style) with server key bundles
1. **Discovery:**
    - **Scanner → Server:** Send the scanned user’s uuid.
    - **Server → Scanner:** Return key bundle {ik_pub, spk_pub, spk_sig, one OPK_pub}, consuming that OPK atomically.
2. **Verification:**
    - **Scanner client:** Verify spk_sig with ik_pub. If QR attestation was scanned earlier, verify server attestation or compare IK fingerprint to QR.
3. **X3DH shared secret:**
    - Compute DHs: DH1=EK×IK, DH2=EK×SPK, DH3=IK_self×SPK (and DH4=EK×OPK if used).
    - Derive master secret via HKDF to get the initial root key.
4. **Start Double Ratchet:**
    - Initialize sending chain, include your current ratchet public key in the first message header.
5. **First message:**
    - Send an initial prekey message with:
        - Header: protocol version, your ratchet public key, counters, and the identifiers for which SPK/OPK were used.
        - Ciphertext: AEAD over your payload.
6. **Receiver online/offline:**
    - Server queues message if offline. Receiver, upon fetching, runs X3DH using its SPK and the consumed OPK, then advances to Double Ratchet.

Security properties:
- **Asynchronous start** works while the peer is offline.
- **Server can’t decrypt** and can’t forge SPK because of IK signature.
- **MITM mitigation** if you verify IK via QR fingerprint or server attestation.

## B) Offline mutual QR verification (high-trust pairing)
1. **Show codes:** Each user shows their QR bundle (above). Optionally scan both ways for mutual authentication.
2. **Verify fingerprints:** Both users compare the safety code verbally or visually. Mark contact as “verified” if matched.
3. **Fetch OPK:** Scanner contacts server to fetch a one-time prekey for the peer (or embed an OPK in a short-lived dynamic QR if you absolutely must operate fully offline).
4. **Run X3DH → Double Ratchet:** As in flow A.

Security properties:
- **Strong MITM resistance** via out-of-band IK verification.
- **Usable even with a hostile network** once QR is scanned.

---

# Messaging flow (end-to-end)

## Send
- **Encrypt:** Use AEAD (ChaCha20-Poly1305 or AES-256-GCM) with keys derived from the Double Ratchet sending chain.
- **Header (unencrypted but authenticated as AD):** protocol version, sender ratchet public key, message number, previous chain length, and optional metadata (all included as AEAD associated data).
- **Submit:** Server receives only ciphertext + header + routing metadata (sender uuid, receiver uuid, timestamps).

## Receive
- **Ratchet step:** Use header’s ratchet public key to advance the Double Ratchet, derive correct message key (handle skipped-message keys).
- **Decrypt:** AEAD verify and decrypt.
- **Ack/read:** Send a separate small encrypted receipt if you want read status. Don’t store read status in plaintext.

---

# Backend changes you should make

## GraphQL schema (remove plaintext, add headers and AD)
```graphql
type Message {
  id: ID!
  senderId: ID!
  receiverId: ID!
  header: String!          # base64 JSON: {v, dh_pub, pn, n, skippeds, ...}
  ciphertext: String!      # base64
  createdAt: String!
  deliveredAt: String
  readAt: String
  version: Int!
}

extend type Query {
  getInbox: [Message!]!                 # messages where receiverId = me
  getThread(peerId: ID!): [Message!]!   # both directions, only ciphertext+headers
}

extend type Mutation {
  sendCiphertext(
    receiverId: ID!
    header: String!
    ciphertext: String!
  ): Message!

  markDelivered(messageId: ID!): Message!
  markRead(messageId: ID!): Message!
}
```

- **Why:** The server must not see plaintext. Keep only encrypted payloads and minimal routing metadata.

## Access control fixes
- **Inbox:** Filter by receiverId = current user.
- **Thread:** Return messages where (senderId = me AND receiverId = peer) OR (senderId = peer AND receiverId = me).
- **Authorize on server:** Don’t rely on client to provide correct receiverId for fetches.

## Key bundle endpoints
- **POST /keys/register:** Auth with JWT; upload ik_pub, spk_pub, spk_sig, OPKs.
- **GET /keys/bundle/:uuid:** Return ik_pub, spk_pub, spk_sig, and atomically pop one OPK_pub. Add deviceId if needed.
- **Rotation:** Allow clients to rotate SPK periodically and top up OPKs.

## WebSocket gateway
- **Auth:** Keep JWT verification at connection handshake; drop UseGuards on event handlers (it doesn’t protect WS payloads). Trust only the mapped user from the handshake.
- **Payload:** Only accept encrypted payloads; never “content” plaintext.
- **Rate limiting / abuse controls:** Per-sender message rate caps; disconnect on abuse.

## Message service
- **No server-side encryption:** Encryption must be fully client-side.
- **Metadata-only processing:** Store, route, and delete messages per retention policy. Optional “sealed sender”-like mode where even senderId is hidden (advanced).

---

# Secure verifications to perform

- **SPK signature check:** Always verify spk_sig with ik_pub on the client before using a bundle.
- **Attestation check (if used):** Verify server-signed attestation expiry and signature against a pinned server public key.
- **Key change alerts:** If a known contact’s IK changes, flag a safety warning and block sending until user re-verifies.
- **OPK consumption:** Server must consume OPKs atomically to avoid reuse and race conditions.
- **JWT scope:** Use JWT only to authorize writes (upload keys, send messages) and reads (fetch my inbox). Keep TTL short and rotate refresh tokens securely.
- **Replay protection:** Nonces are derived via ratchet; AEAD covers header as AD. Reject duplicates via message IDs/counters retained per-session.
- **Time and versioning:** Include protocol version, timestamps, and optional expiry in headers; reject wildly skewed or unsupported versions.

---

# Crypto choices and parameters

- **Curves:** X25519 for ECDH, Ed25519 for signatures.
- **KDF:** HKDF-SHA-256 for X3DH and Double Ratchet key derivations.
- **AEAD:** ChaCha20-Poly1305 (mobile friendly) or AES-256-GCM with unique nonces.
- **Randomness:** Cryptographically secure RNG for keys and nonces.
- **Encoding:** Base64URL for compact transport; keep JSON small.

If you don’t want to re-invent, aim to mirror the Signal protocol stack (X3DH + Double Ratchet) and its message headers. Even if you don’t adopt the whole library, following its structures avoids subtle pitfalls.

---

# Open questions so I can tailor this to your code

- **JWT claims:** What claims are inside your JWT now? sub/uuid, exp, iat, deviceId?
- **Multi-device:** Do you plan multiple devices per user? If yes, each device needs its own IK/SPK/OPKs and its own QR.
- **Groups:** Do you need group messaging soon? That changes key management (sender keys).
- **QR UX:** Do you prefer one-way scan (simpler) or mutual scan with safety code (more secure)?
- **Storage:** Are you okay removing plaintext content field and migrating existing data?