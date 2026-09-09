# Karzoun FieldSync

FieldSync is an offline-first synchronization engine written in Kotlin for Android field applications that must continue accepting local work while connectivity is unreliable.

The engine focuses on explicit synchronization semantics instead of UI or backend framework code.

## Current v0.1 core

- atomic local mutation staging contract
- exact client mutation IDs
- bounded push batches
- accepted / retryable / permanent push outcomes
- cursor-based incremental pull
- exact remote mutation IDs for replay deduplication
- deterministic local-vs-server conflict policy
- atomic remote-page commit contract
- bounded pull pages
- deterministic offline/reconnect tests
- JVM 17-compatible bytecode
- CI on JDK 21 and JDK 25
- CodeQL Java/Kotlin

## Architecture

```text
Android host / field app
        |
        v
  FieldSyncEngine
   /          \
SyncStore   SyncTransport
   |            |
local state   app-defined API
+ queue       client
```

`SyncStore` is the durability boundary. This first milestone ships an in-memory reference store so engine semantics can be proved without pretending durability exists. A SQLite-backed store with restart recovery is the next durability milestone.

`SyncTransport` is deliberately abstract. Authentication, authorization, TLS policy and server API design belong to the host application.

## Conflict model

Two explicit reference policies are included:

- `LocalPendingWins`
- `ServerWins`

Applications can provide a custom `ConflictPolicy`.

FieldSync does not claim CRDT convergence, consensus, exactly-once delivery, global ordering, or server implementation.

## Build

Requirements:

- JDK 21+
- Gradle 9.4.1+

```bash
gradle clean check --warning-mode=fail
```

## Why this is Android-friendly

The library emits JVM 17-compatible bytecode and does not require an Android UI dependency. Android-specific scheduling and SQLite integration are planned as adapters, keeping the synchronization core testable on the JVM.

## License

Apache-2.0
