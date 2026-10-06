# ADR 0004: Chat streaming transport: SSE

- Status: Accepted
- Date: 2026-10-06

## Context
Chat responses must stream token by token to the phone.

## Decision
Use Server-Sent Events over HTTPS for chat. Reserve WebSocket for future full-duplex realtime voice.

## Consequences
A chat turn is one request followed by a one-way stream. SSE is plain HTTP: auth headers, retries, proxies, HTTP/2 and stateless servers all work unchanged.

## Alternatives considered
WebSocket for everything: needs connection state, custom auth and reconnection logic for no benefit in text chat.
