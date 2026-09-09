package dev.karzoun.fieldsync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Deterministic reference store used for engine tests and host integration examples.
 * It is intentionally not durable. SQLite/Android persistence is a later milestone.
 */
public class InMemorySyncStore : SyncStore {
    private val mutex: Mutex = Mutex()
    private val pending: LinkedHashMap<MutationId, PendingMutation> = linkedMapOf()
    private val permanentFailures: LinkedHashMap<MutationId, Pair<PendingMutation, String>> = linkedMapOf()
    private val records: LinkedHashMap<EntityKey, ReplicaRecord> = linkedMapOf()
    private val appliedRemoteIds: MutableSet<String> = linkedSetOf()
    private var checkpoint: String? = null

    override suspend fun stageLocal(mutation: PendingMutation): Unit = mutex.withLock {
        require(!pending.containsKey(mutation.mutationId)) {
            "Duplicate mutation ID: ${mutation.mutationId.value}"
        }

        pending[mutation.mutationId] = mutation
        records[mutation.entity] = ReplicaRecord(
            entity = mutation.entity,
            kind = mutation.kind,
            payload = mutation.payload,
            serverVersion = records[mutation.entity]?.serverVersion,
        )
    }

    override suspend fun pending(limit: Int): List<PendingMutation> = mutex.withLock {
        require(limit > 0) { "limit must be positive" }
        pending.values.take(limit)
    }

    override suspend fun pendingFor(entity: EntityKey): PendingMutation? = mutex.withLock {
        pending.values.lastOrNull { it.entity == entity }
    }

    override suspend fun markAccepted(mutationId: MutationId, serverVersion: Long): Unit = mutex.withLock {
        require(serverVersion >= 0) { "serverVersion must be non-negative" }
        val mutation = pending.remove(mutationId) ?: return@withLock
        val current = records[mutation.entity]

        if (current != null) {
            records[mutation.entity] = current.copy(serverVersion = serverVersion)
        }
    }

    override suspend fun markRetryable(mutationId: MutationId): Unit = mutex.withLock {
        val mutation = pending[mutationId] ?: return@withLock
        pending[mutationId] = mutation.copy(attempts = mutation.attempts + 1)
    }

    override suspend fun markPermanentFailure(mutationId: MutationId, reason: String): Unit = mutex.withLock {
        require(reason.isNotBlank()) { "reason must not be blank" }
        val mutation = pending.remove(mutationId) ?: return@withLock
        permanentFailures[mutationId] = mutation to reason
    }

    override suspend fun checkpoint(): String? = mutex.withLock { checkpoint }

    override suspend fun wasRemoteApplied(remoteMutationId: String): Boolean = mutex.withLock {
        appliedRemoteIds.contains(remoteMutationId)
    }

    override suspend fun commitRemotePage(
        plans: List<RemoteApplyPlan>,
        nextCursor: String?,
    ): Unit = mutex.withLock {
        for (plan in plans) {
            val remote = plan.mutation

            if (appliedRemoteIds.contains(remote.remoteMutationId)) {
                continue
            }

            when (plan.decision) {
                ConflictDecision.KEEP_LOCAL -> Unit

                ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL -> {
                    val localIds = pending.values
                        .filter { it.entity == remote.entity }
                        .map { it.mutationId }

                    for (localId in localIds) {
                        pending.remove(localId)
                    }

                    records[remote.entity] = ReplicaRecord(
                        entity = remote.entity,
                        kind = remote.kind,
                        payload = remote.payload,
                        serverVersion = remote.serverVersion,
                    )
                }
            }

            appliedRemoteIds += remote.remoteMutationId
        }

        checkpoint = nextCursor
    }

    override suspend fun record(entity: EntityKey): ReplicaRecord? = mutex.withLock {
        records[entity]
    }

    override suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    override suspend fun permanentFailureCount(): Int = mutex.withLock {
        permanentFailures.size
    }
}
