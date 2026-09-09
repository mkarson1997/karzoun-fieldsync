package dev.karzoun.fieldsync

/**
 * Client-generated mutation identifier. The engine treats this as an exact idempotency key.
 */
@JvmInline
public value class MutationId(public val value: String) {
    init {
        require(value.isNotBlank()) { "MutationId must not be blank" }
    }
}

/** Stable entity identity inside a logical collection. */
public data class EntityKey(
    public val collection: String,
    public val id: String,
) {
    init {
        require(collection.isNotBlank()) { "collection must not be blank" }
        require(id.isNotBlank()) { "id must not be blank" }
    }
}

public enum class MutationKind {
    UPSERT,
    DELETE,
}

/** Opaque serialized payload owned by the host application. */
public data class PendingMutation(
    public val mutationId: MutationId,
    public val entity: EntityKey,
    public val kind: MutationKind,
    public val payload: String?,
    public val createdAtEpochMillis: Long,
    public val attempts: Int = 0,
) {
    init {
        require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must be non-negative" }
        require(attempts >= 0) { "attempts must be non-negative" }
        require(kind != MutationKind.DELETE || payload == null) {
            "DELETE mutations must not contain payload"
        }
    }
}

public data class RemoteMutation(
    public val remoteMutationId: String,
    public val entity: EntityKey,
    public val kind: MutationKind,
    public val payload: String?,
    public val serverVersion: Long,
) {
    init {
        require(remoteMutationId.isNotBlank()) { "remoteMutationId must not be blank" }
        require(serverVersion >= 0) { "serverVersion must be non-negative" }
        require(kind != MutationKind.DELETE || payload == null) {
            "DELETE mutations must not contain payload"
        }
    }
}

public data class ReplicaRecord(
    public val entity: EntityKey,
    public val kind: MutationKind,
    public val payload: String?,
    public val serverVersion: Long?,
)

public sealed interface PushOutcome {
    public val mutationId: MutationId

    public data class Accepted(
        override val mutationId: MutationId,
        public val serverVersion: Long,
    ) : PushOutcome {
        init {
            require(serverVersion >= 0) { "serverVersion must be non-negative" }
        }
    }

    public data class RetryableFailure(
        override val mutationId: MutationId,
        public val reason: String,
    ) : PushOutcome {
        init {
            require(reason.isNotBlank()) { "reason must not be blank" }
        }
    }

    public data class PermanentFailure(
        override val mutationId: MutationId,
        public val reason: String,
    ) : PushOutcome {
        init {
            require(reason.isNotBlank()) { "reason must not be blank" }
        }
    }
}

public data class PullPage(
    public val mutations: List<RemoteMutation>,
    public val nextCursor: String?,
    public val hasMore: Boolean,
)

public enum class ConflictDecision {
    KEEP_LOCAL,
    APPLY_REMOTE_AND_DROP_LOCAL,
}

public data class RemoteApplyPlan(
    public val mutation: RemoteMutation,
    public val decision: ConflictDecision,
)

public data class SyncReport(
    public val pushed: Int,
    public val retryableFailures: Int,
    public val permanentFailures: Int,
    public val remoteSeen: Int,
    public val remoteApplied: Int,
    public val conflictsKeptLocal: Int,
    public val pulledPages: Int,
)
