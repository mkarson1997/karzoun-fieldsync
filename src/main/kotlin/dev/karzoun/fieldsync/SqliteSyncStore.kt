package dev.karzoun.fieldsync

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * File-backed JVM reference implementation of [SyncStore].
 *
 * This class proves durable queue/checkpoint/replay semantics with SQLite JDBC.
 * Android hosts should use an Android-native SQLite/Room adapter implementing the same contract.
 */
public class SqliteSyncStore(
    databasePath: Path,
) : SyncStore, AutoCloseable {
    private val mutex: Mutex = Mutex()
    private val connection: Connection = DriverManager.getConnection(
        "jdbc:sqlite:${databasePath.toAbsolutePath()}",
    )

    init {
        configureConnection()
        migrateSchema()
    }

    public override suspend fun stageLocal(mutation: PendingMutation): Unit = locked {
        transaction {
            require(!pendingIdExists(mutation.mutationId)) {
                "Duplicate mutation ID: ${mutation.mutationId.value}"
            }

            connection.prepareStatement(
                """
                INSERT INTO pending_mutations(
                    mutation_id, collection_name, entity_id, kind, payload, created_at, attempts
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, mutation.mutationId.value)
                statement.setString(2, mutation.entity.collection)
                statement.setString(3, mutation.entity.id)
                statement.setString(4, mutation.kind.name)
                statement.setString(5, mutation.payload)
                statement.setLong(6, mutation.createdAtEpochMillis)
                statement.setInt(7, mutation.attempts)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                INSERT INTO replica_records(collection_name, entity_id, kind, payload, server_version)
                VALUES (?, ?, ?, ?, NULL)
                ON CONFLICT(collection_name, entity_id) DO UPDATE SET
                    kind = excluded.kind,
                    payload = excluded.payload
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, mutation.entity.collection)
                statement.setString(2, mutation.entity.id)
                statement.setString(3, mutation.kind.name)
                statement.setString(4, mutation.payload)
                statement.executeUpdate()
            }
        }
    }

    public override suspend fun pending(limit: Int): List<PendingMutation> = locked {
        require(limit > 0) { "limit must be positive" }

        connection.prepareStatement(
            """
            SELECT mutation_id, collection_name, entity_id, kind, payload, created_at, attempts
            FROM pending_mutations
            ORDER BY created_at ASC, mutation_id ASC
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, limit)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(result.toPendingMutation())
                    }
                }
            }
        }
    }

    public override suspend fun pendingFor(entity: EntityKey): PendingMutation? = locked {
        connection.prepareStatement(
            """
            SELECT mutation_id, collection_name, entity_id, kind, payload, created_at, attempts
            FROM pending_mutations
            WHERE collection_name = ? AND entity_id = ?
            ORDER BY created_at DESC, mutation_id DESC
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, entity.collection)
            statement.setString(2, entity.id)
            statement.executeQuery().use { result ->
                if (result.next()) result.toPendingMutation() else null
            }
        }
    }

    public override suspend fun markAccepted(
        mutationId: MutationId,
        serverVersion: Long,
    ): Unit = locked {
        require(serverVersion >= 0) { "serverVersion must be non-negative" }

        transaction {
            val entity = findPendingEntity(mutationId) ?: return@transaction

            connection.prepareStatement(
                "DELETE FROM pending_mutations WHERE mutation_id = ?",
            ).use { statement ->
                statement.setString(1, mutationId.value)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                """
                UPDATE replica_records
                SET server_version = ?
                WHERE collection_name = ? AND entity_id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, serverVersion)
                statement.setString(2, entity.collection)
                statement.setString(3, entity.id)
                statement.executeUpdate()
            }
        }
    }

    public override suspend fun markRetryable(mutationId: MutationId): Unit = locked {
        connection.prepareStatement(
            """
            UPDATE pending_mutations
            SET attempts = attempts + 1
            WHERE mutation_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, mutationId.value)
            statement.executeUpdate()
        }
    }

    public override suspend fun markPermanentFailure(
        mutationId: MutationId,
        reason: String,
    ): Unit = locked {
        require(reason.isNotBlank()) { "reason must not be blank" }

        transaction {
            val mutation = findPending(mutationId) ?: return@transaction

            connection.prepareStatement(
                """
                INSERT INTO permanent_failures(
                    mutation_id, collection_name, entity_id, kind, payload, created_at, attempts, reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, mutation.mutationId.value)
                statement.setString(2, mutation.entity.collection)
                statement.setString(3, mutation.entity.id)
                statement.setString(4, mutation.kind.name)
                statement.setString(5, mutation.payload)
                statement.setLong(6, mutation.createdAtEpochMillis)
                statement.setInt(7, mutation.attempts)
                statement.setString(8, reason)
                statement.executeUpdate()
            }

            connection.prepareStatement(
                "DELETE FROM pending_mutations WHERE mutation_id = ?",
            ).use { statement ->
                statement.setString(1, mutationId.value)
                statement.executeUpdate()
            }
        }
    }

    public override suspend fun checkpoint(): String? = locked {
        connection.prepareStatement(
            "SELECT value FROM sync_meta WHERE key = 'checkpoint'",
        ).use { statement ->
            statement.executeQuery().use { result ->
                if (result.next()) result.getString("value") else null
            }
        }
    }

    public override suspend fun wasRemoteApplied(remoteMutationId: String): Boolean = locked {
        require(remoteMutationId.isNotBlank()) { "remoteMutationId must not be blank" }

        connection.prepareStatement(
            "SELECT 1 FROM applied_remote_mutations WHERE remote_mutation_id = ?",
        ).use { statement ->
            statement.setString(1, remoteMutationId)
            statement.executeQuery().use { result -> result.next() }
        }
    }

    public override suspend fun commitRemotePage(
        plans: List<RemoteApplyPlan>,
        nextCursor: String?,
    ): Unit = locked {
        transaction {
            for (plan in plans) {
                val remote = plan.mutation
                if (remoteIdExists(remote.remoteMutationId)) {
                    continue
                }

                if (plan.decision == ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL) {
                    connection.prepareStatement(
                        """
                        DELETE FROM pending_mutations
                        WHERE collection_name = ? AND entity_id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, remote.entity.collection)
                        statement.setString(2, remote.entity.id)
                        statement.executeUpdate()
                    }

                    connection.prepareStatement(
                        """
                        INSERT INTO replica_records(
                            collection_name, entity_id, kind, payload, server_version
                        ) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT(collection_name, entity_id) DO UPDATE SET
                            kind = excluded.kind,
                            payload = excluded.payload,
                            server_version = excluded.server_version
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, remote.entity.collection)
                        statement.setString(2, remote.entity.id)
                        statement.setString(3, remote.kind.name)
                        statement.setString(4, remote.payload)
                        statement.setLong(5, remote.serverVersion)
                        statement.executeUpdate()
                    }
                }

                connection.prepareStatement(
                    "INSERT INTO applied_remote_mutations(remote_mutation_id) VALUES (?)",
                ).use { statement ->
                    statement.setString(1, remote.remoteMutationId)
                    statement.executeUpdate()
                }
            }

            if (nextCursor == null) {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM sync_meta WHERE key = 'checkpoint'")
                }
            } else {
                connection.prepareStatement(
                    """
                    INSERT INTO sync_meta(key, value) VALUES ('checkpoint', ?)
                    ON CONFLICT(key) DO UPDATE SET value = excluded.value
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, nextCursor)
                    statement.executeUpdate()
                }
            }
        }
    }

    public override suspend fun record(entity: EntityKey): ReplicaRecord? = locked {
        connection.prepareStatement(
            """
            SELECT kind, payload, server_version
            FROM replica_records
            WHERE collection_name = ? AND entity_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, entity.collection)
            statement.setString(2, entity.id)
            statement.executeQuery().use { result ->
                if (!result.next()) {
                    null
                } else {
                    val version = result.getLong("server_version").let { value ->
                        if (result.wasNull()) null else value
                    }
                    ReplicaRecord(
                        entity = entity,
                        kind = MutationKind.valueOf(result.getString("kind")),
                        payload = result.getString("payload"),
                        serverVersion = version,
                    )
                }
            }
        }
    }

    public override suspend fun pendingCount(): Int = locked {
        scalarCount("SELECT COUNT(*) FROM pending_mutations")
    }

    public override suspend fun permanentFailureCount(): Int = locked {
        scalarCount("SELECT COUNT(*) FROM permanent_failures")
    }

    public override fun close(): Unit {
        connection.close()
    }

    private suspend fun <T> locked(block: () -> T): T = mutex.withLock {
        withContext(Dispatchers.IO) { block() }
    }

    private fun configureConnection() {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }

    private fun migrateSchema() {
        val version = connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next()) { "SQLite did not return user_version" }
                result.getInt(1)
            }
        }

        require(version in 0..SCHEMA_VERSION) {
            "Database schema version $version is newer than supported version $SCHEMA_VERSION"
        }

        if (version == SCHEMA_VERSION) {
            return
        }

        transaction {
            connection.createStatement().use { statement ->
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS replica_records(
                        collection_name TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        payload TEXT,
                        server_version INTEGER,
                        PRIMARY KEY(collection_name, entity_id)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS pending_mutations(
                        mutation_id TEXT PRIMARY KEY,
                        collection_name TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        payload TEXT,
                        created_at INTEGER NOT NULL,
                        attempts INTEGER NOT NULL CHECK(attempts >= 0)
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS permanent_failures(
                        mutation_id TEXT PRIMARY KEY,
                        collection_name TEXT NOT NULL,
                        entity_id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        payload TEXT,
                        created_at INTEGER NOT NULL,
                        attempts INTEGER NOT NULL,
                        reason TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS applied_remote_mutations(
                        remote_mutation_id TEXT PRIMARY KEY
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS sync_meta(
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                statement.executeUpdate(
                    """
                    CREATE INDEX IF NOT EXISTS idx_pending_entity
                    ON pending_mutations(collection_name, entity_id, created_at, mutation_id)
                    """.trimIndent(),
                )
                statement.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }
    }

    private fun pendingIdExists(mutationId: MutationId): Boolean = connection.prepareStatement(
        "SELECT 1 FROM pending_mutations WHERE mutation_id = ?",
    ).use { statement ->
        statement.setString(1, mutationId.value)
        statement.executeQuery().use { result -> result.next() }
    }

    private fun remoteIdExists(remoteMutationId: String): Boolean = connection.prepareStatement(
        "SELECT 1 FROM applied_remote_mutations WHERE remote_mutation_id = ?",
    ).use { statement ->
        statement.setString(1, remoteMutationId)
        statement.executeQuery().use { result -> result.next() }
    }

    private fun findPendingEntity(mutationId: MutationId): EntityKey? = connection.prepareStatement(
        """
        SELECT collection_name, entity_id
        FROM pending_mutations
        WHERE mutation_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, mutationId.value)
        statement.executeQuery().use { result ->
            if (result.next()) {
                EntityKey(
                    collection = result.getString("collection_name"),
                    id = result.getString("entity_id"),
                )
            } else {
                null
            }
        }
    }

    private fun findPending(mutationId: MutationId): PendingMutation? = connection.prepareStatement(
        """
        SELECT mutation_id, collection_name, entity_id, kind, payload, created_at, attempts
        FROM pending_mutations
        WHERE mutation_id = ?
        """.trimIndent(),
    ).use { statement ->
        statement.setString(1, mutationId.value)
        statement.executeQuery().use { result ->
            if (result.next()) result.toPendingMutation() else null
        }
    }

    private fun ResultSet.toPendingMutation(): PendingMutation = PendingMutation(
        mutationId = MutationId(getString("mutation_id")),
        entity = EntityKey(
            collection = getString("collection_name"),
            id = getString("entity_id"),
        ),
        kind = MutationKind.valueOf(getString("kind")),
        payload = getString("payload"),
        createdAtEpochMillis = getLong("created_at"),
        attempts = getInt("attempts"),
    )

    private fun scalarCount(sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            check(result.next()) { "COUNT query returned no row" }
            result.getInt(1)
        }
    }

    private fun <T> transaction(block: () -> T): T {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}
