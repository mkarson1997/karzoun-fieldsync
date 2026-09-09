# Karzoun FieldSync v0.1.0

FieldSync v0.1.0 establishes the first public synchronization-engine release for unreliable-connectivity field workflows.

## What is included

- bounded local push and incremental remote pull cycles
- exact mutation IDs and replay deduplication
- explicit retryable and permanent failure handling
- deterministic conflict-policy extension point
- in-memory reference store
- file-backed JVM SQLite store with restart-safe queue, checkpoint, replay, and failure state
- atomic local staging and remote-page commit contracts
- deterministic offline/reconnect and restart tests
- JDK 21/25 CI and CodeQL Java/Kotlin analysis

## Distribution

The release publishes:

- main JAR
- sources JAR
- Maven POM
- `SHA256SUMS.txt`
- build provenance attestation for JAR artifacts

## Important boundaries

FieldSync does not bundle a backend server and does not claim exactly-once delivery, consensus, global ordering, or CRDT convergence.

`SqliteSyncStore` is the JVM SQLite/JDBC reference implementation. Android-native SQLite/Room and scheduling adapters are intentionally outside this release.
