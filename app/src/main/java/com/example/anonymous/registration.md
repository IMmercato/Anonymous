# 🔐 Anonymous QR Code-Based Login System – Summary Guide

This document outlines a secure and modern approach to implementing anonymous user registration and login using **UUIDs**, **QR codes**, and **time-sensitive tokens**.

---

## ✅ Technologies Used

- **ZXing** (QR Code generation and scanning on Android)
- **UUIDv4** (for anonymous identity)
- **JWT** or time-based tokens (for secure login)
- **Public/Private Key Pairs** (optional, for stronger identity verification)

---

## 🔑 Registration Flow

1. **Client-side (App)**:
   - Generates a **UUIDv4**
   - Optionally creates a **public/private key pair**
   - Displays the **UUID** as a **QR code**

2. **Server-side**:
   - Receives UUID (and optional public key)
   - Stores it securely:  
     `UUID → Public Key` or user record

---

## 🔐 Login Flow (QR-Based Challenge)

### Phase 1: UUID Scan
- Another device scans the UUID QR.
- Server verifies the UUID exists.

### Phase 2: Secure Challenge QR
- Server generates a **time-sensitive token** (e.g., JWT) containing:
  ```json
  {
    "uuid": "user-uuid",
    "exp": "timestamp"
  }
  ```
- Token is signed (HMAC or RSA) to prevent tampering.
- Server encodes token as a new QR code.

### Phase 3: Token Scan and Authentication
- User scans the challenge QR code.
- App sends it to server to complete login.
- Server verifies:
    - Token is valid
    - Token has not expired
    - UUID matches

---

## 🔐 Security Best Practices
| Concern             | Recommendation                                |
| ------------------- | --------------------------------------------- |
| QR Token Lifetime   | 30–60 seconds max (ephemeral QR codes)        |
| UUID Security       | Use only **UUIDv4** (random, 122-bit entropy) |
| Token Signing       | Sign tokens with **HMAC** (JWT) or **RSA**    |
| QR Reuse            | **Avoid static QR codes** for login           |
| Optional Encryption | Encrypt token contents if extra sensitive     |
| Transport Security  | Always use **HTTPS (TLS 1.3)**                |

---

## ✅ Tools/Libraries
- ZXing (Android QR generation and scanning)
- Java UUID, Kotlin's UUID.randomUUID()
- JWT libraries (Java, Kotlin, Node.js, Python)
- libsodium / Tink (if using cryptographic signatures or key pairs)

---

## 📝 Notes
- UUID = identity
- QR code = transport
- Token = authentication challenge

This approach balances anonymity, security, and usability.

---