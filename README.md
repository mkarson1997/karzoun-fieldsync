# Karzoun FieldSync

FieldSync is an offline-first synchronization engine written in Kotlin for field applications that must continue accepting local work while connectivity is unreliable.

The engine focuses on explicit synchronization semantics instead of UI or backend framework code.

## Current v0.1 capabilities

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
- file-backed SQLite reference store
- versioned schema initialization
- WAL + `synchronous=FULL`
- persistent pending queue, retry attempts, failure bucket, replay IDs and checkpoint
- close/reopen restart reconstruction tests
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

`SyncStore` is the persistence boundary. FieldSync includes two reference implementations:

- `InMemorySyncStore` for deterministic core tests and host integration examples
- `SqliteSyncStore` for JVM file-backed durability and restart recovery evidence

`SqliteSyncStore` uses SQLite JDBC. It proves durable synchronization semantics on the JVM, but it is **not** presented as an Android-native storage adapter. Android applications should implement the same `SyncStore` contract using Android SQLite/Room or another Android-native persistence layer.

`SyncTransport` is deliberately abstract. Authentication, authorization, TLS policy and server API design belong to the host application.

## Conflict model

Two explicit reference policies are included:

- `LocalPendingWins`
- `ServerWins`

Applications can provide a custom `ConflictPolicy`.

FieldSync does not claim CRDT convergence, consensus, exactly-once delivery, global ordering, or a bundled server implementation.

## Build

Requirements:

- JDK 21+
- Gradle 9.4.1+

```bash
gradle clean check --warning-mode=fail
```

## Why this is Android-oriented

The synchronization core emits JVM 17-compatible bytecode and has no Android UI dependency. Android-native scheduling and persistence adapters remain separate so the synchronization semantics stay directly testable on the JVM.

## License

Apache-2.0
