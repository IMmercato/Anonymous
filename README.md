# Anonymous
A Next-Generation Anonymous Social Network

Anonymous is a social network platform built for users who value privacy above all. Our platform creates a space for individuals to interact, share messages, and exchange media without revealing any personally identifiable information. Every user is represented solely by a unique identifier, ensuring that interactions remain completely decoupled from real-world identities.

> **The entire platform operates exclusively over the [I2P network](https://geti2p.net/) via [i2pd (PurpleI2P)](https://github.com/PurpleI2P/i2pd). There are no central servers, no open API endpoints, and absolutely zero data collection — by design, not by policy.**

---

## Overview
In today's increasingly connected world, privacy is a precious commodity. Many users feel pressured to share more about themselves than they'd like. Anonymous tackles this challenge by focusing entirely on anonymity and security.

**Core Objective:**  
Empower individuals with the freedom to communicate openly while protecting personal identity. By using a system that requires no personal data for registration, users can join and enjoy a social network without compromising their privacy.

**What Sets Us Apart:**  
Instead of using traditional credentials like usernames and passwords, Anonymous assigns a unique, auto-generated identifier to every user. The platform runs entirely within the I2P darknet — there is no clearnet presence, no traceable IP, and no infrastructure capable of collecting user data, even if compelled.

---

## Key Features

### 1. Anonymous Registration
- **Unique User IDs:**  
  Unique User IDs — I2P B32 Address:
  When a user first joins the platform, no UUID is manually generated. Instead, the user's identity is the Base32 (B32) destination address cryptographically assigned by the I2P network through the local i2pd router. This address — derived from the user's I2P destination key pair — acts as the sole marker of identity within the app. It is mathematically tied to the user's local router keys, requires no registration with any authority, and is intrinsically anonymous.
- **Optional Display Names:**  
Users can choose a nickname or alias solely for display purposes. No personal information is ever requested, stored, or inferred.

### 2. Innovative Login System
- **QR Code Authentication:**  
  Traditional passwords are replaced by a real-time, dynamic QR code system. Each QR code — tied to the user's UUID and a timestamp — is generated on the fly and remains valid only briefly. This guarantees secure sessions even when accessing from a new device.
- **Cross-Device Seamlessness:**  
  Transition effortlessly between devices without friction or risk of exposing sensitive data. All handshakes occur within the I2P tunnel layer.

### 3. Robust Session Management
- **Local Encrypted Storage:**  
  The user's unique identifier is stored in a secure, encrypted manner on the device, enabling auto-login for returning users without any server-side record.
- **Token-Based Sessions:**  
  After authentication, a temporary token is issued locally to maintain session continuity. Tokens feature built-in expiry times to minimize unauthorized access. No token is ever logged or stored outside the user's device.

### 4. Data Security & Zero Data Collection
- **NO DATA COLLECTION: 0**  
  The platform collects absolutely nothing. No metadata, no usage statistics, no identifiers, no IP addresses — nothing. The architecture is physically incapable of data collection by design.
- **Privacy by Architecture:**  
  Anonymous is built on a strict "zero data" philosophy. Since all traffic is routed through I2P tunnels and there is no centralized backend, there is no point in the system where data could be harvested — even by the developers.
- **End-to-End Encryption:**  
  All communications — whether text messages or media — are secured with robust encryption. I2P provides a native layer of end-to-end encryption at the network level, supplemented by application-layer encryption for all stored content.

### 5. Modern User Interface
- **Futuristic & Minimalistic Design:**  
  Using Jetpack Compose, the app offers a clean, intuitive interface with reactive UI components, built for a seamless experience within I2P-connected environments.
- **Consistent Experience:**  
  Whether on Android mobile or a compatible device running i2pd, users enjoy a consistently private and secure interface.

---

## Technical Specifications

### Network Layer — I2P via i2pd

The entire application operates exclusively within the **Invisible Internet Project (I2P)** network. There is no clearnet fallback, no open API, and no DNS-resolvable hostname.

- **I2P Implementation:** [`i2pd` (PurpleI2P/i2pd)](https://github.com/PurpleI2P/i2pd) — a full-featured, standalone C++ I2P router
- **Android Integration:** [`i2pd-android` (PurpleI2P/i2pd-android)](https://github.com/PurpleI2P/i2pd-android) — native I2P router embedded directly in the Android app
- **Transport:** All messages and media are routed through I2P tunnels, providing multi-layer encryption and sender/receiver anonymity at the network level
- **Addressing:** Services are exposed as I2P destinations (`.i2p` addresses / Base32/Base64 destinations) — never as IP addresses or domain names
- **Tunnel Architecture:** Garlic routing encrypts and bundles messages across multiple hops, preventing traffic analysis
- **No Servers:** There are no traditional servers. Peer-to-peer communication flows exclusively through I2P's distributed router network
- **No Open API:** The previously planned open REST API has been removed entirely. No external API surface exists

### Frontend
- **Language & Environment:**
    - Kotlin
    - Android Studio
- **UI Framework:**
    - Jetpack Compose — declarative, modern UI toolkit
- **Embedded I2P Router:**
    - `i2pd-android` runs as an in-process or companion service, establishing and managing I2P tunnels locally on the device without requiring a separate app

### Communication
- **Protocol:**
    - All peer communication travels over I2P tunnels (no raw TCP/UDP to clearnet hosts)
- **Data Exchange Format:**
    - JSON payloads, transmitted exclusively within encrypted I2P streams
- **No Retrofit/Ktor to clearnet:**
    - Any HTTP-like communication is strictly scoped to `localhost` tunnel endpoints exposed by the local i2pd router — never to external hosts

### Infrastructure & Security

- **Encryption Standards:**  
  I2P provides ElGamal/AES + ECIES (Ratchet) at the tunnel layer. Application-layer encryption covers all stored data using modern symmetric encryption (e.g., AES-256-GCM).
- **Session & Authentication Protocols:**  
  Dynamic QR codes and token-based sessions are resolved locally. No authentication request ever leaves the device to a central authority.
- **Threat Model:**  
  Since there is no central server, there is no single point of compromise, subpoena, or data breach. The system is resistant to server seizure, legal data requests, and bulk surveillance by design.

---

## Getting Started (For Developers)

### Prerequisites

- Android Studio (latest stable) with Kotlin support
- `i2pd` installed locally for development/testing: [https://github.com/PurpleI2P/i2pd](https://github.com/PurpleI2P/i2pd)

### Installation & Setup

#### Clone the Repository
```bash
git clone https://github.com/IMmercato/Anonymous.git
cd Anonymous
```

#### Running the App
Launch the Android emulator or connect a physical Android device. Run via Android Studio. The embedded `i2pd-android` router will initialize automatically and establish I2P connectivity before the app becomes fully functional.

### Contribution Guidelines
- **Pull Requests:** All contributions must follow the project's coding and security standards. Any networking change must preserve I2P exclusivity — no clearnet fallback may be introduced.
- **Issues:** Open an issue on the GitHub repository for bugs or suggestions.
- **Security Principle:** Any contribution that introduces data collection, clearnet communication, or logging of user activity will be rejected outright.

---

## Future Vision

Anonymous is not just about creating a secure social network — it's about reimagining how we interact online in a world where privacy is a fundamental right, not a premium feature.

- **Enhanced I2P Integration:** Deeper use of I2P's native streaming and datagram APIs for lower-latency messaging
- **Community Moderation Tools:** Decentralized, anonymous moderation mechanisms that maintain a safe environment without any identity exposure
- **Cross-Platform I2P Clients:** Web interface accessible exclusively via an `.i2p` address, requiring an I2P-enabled browser or proxy
- **Open Protocol (not Open API):** A documented, open communication protocol that developers can implement independently — without any central API dependency

---

## Conclusion

Anonymous stands at the intersection of privacy, innovation, and secure communication. By building entirely on I2P and i2pd — and collecting zero data by architecture — we ensure that anonymity is not a feature that can be switched off, overridden, or compromised. There is no server to seize, no database to breach, no API to surveil.

In this platform, the principle of anonymity isn't just a feature — it's the foundation upon which every function is built.

**Your anonymity is your strength. And here, it is structurally guaranteed.**