# Real-Time Messaging Architecture

## Current System Overview

The existing system uses a **GraphQL server with Prisma** to manage client communication. Messages are exchanged through a **message pooling mechanism**, which results in high request volume and latency.

### Architecture Diagram

```
                GraphQL Server + Prisma
                           |
┌──────────┐          ┌────────┐          ┌──────────┐
| Client A | <------> | Node 1 | <------> | Client B |
└──────────┘          └────────┘          └──────────┘
     |                     |                    |
GraphQL Req         Message Pooling        GraphQL Req
```

### Message Flow

1. Client A encrypts a message  
2. Sends it to the server  
3. Server forwards it to Client B  
4. Client B decrypts the message

This setup relies entirely on **message polling**, which leads to:

- High frequency of GraphQL requests  
- Increased server load  
- Delayed message delivery  

## Planned Improvements

To improve performance, scalability, and decentralization, the system will adopt a **hybrid real-time architecture** that combines:

- **GraphQL Queries/Mutations** for standard operations  
- **GraphQL Subscriptions (WebSocket)** for real-time messaging  
- **Message pooling fallback** in case of WebSocket failure  

### Benefits

- Reduced polling and fewer redundant requests  
- Lower latency and faster message delivery  
- Resilient fallback to polling when real-time channels are unavailable  

### Updated Architecture

```
                GraphQL Server + Prisma
                           |
┌──────────┐          ┌────────┐          ┌──────────┐
| Client A | <=======>| Node 1 |<=======> | Client B |
└──────────┘          └────────┘          └──────────┘
     |                     |                    |
GraphQL Req     GraphQL Subscriptions     GraphQL Req
                   (WebSocket)
```

## Technical Notes

- **Encryption**: End-to-end encryption remains unchanged. Clients handle encryption and decryption locally.  
- **Resilience**: If WebSocket fails due to network issues or client limitations, the system automatically switches to message polling.  
- **Scalability**: The hybrid model reduces server strain and improves performance for large-scale deployments.  

## Decentralization Goals

The long-term goal of this implementation is to support **Tor-based real-time communication**, enabling:

- Decentralized message routing  
- Privacy-preserving identity management  
- Elimination of centralized message brokers  

This shift from a centralized to a decentralized architecture is a core principle of the application, aligning with its mission to empower users with secure, autonomous communication.

## Future Considerations

- Implement connection health checks for WebSocket stability  
- Add retry logic and offline queuing for message delivery  
- Explore peer-to-peer fallback for ultra-low-latency scenarios  
- Integrate Tor routing and onion services for full decentralization  