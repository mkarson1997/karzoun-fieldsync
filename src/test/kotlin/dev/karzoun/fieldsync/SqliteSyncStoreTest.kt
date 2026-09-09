package dev.karzoun.fieldsync

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

public class SqliteSyncStoreTest {
    @Test
    public fun `restart reconstructs pending record checkpoint and remote dedupe`(): Unit = runBlocking {
        val directory = Files.createTempDirectory("fieldsync-restart")
        val database = directory.resolve("fieldsync.db")
        val localKey = EntityKey("assets", "local-1")
        val remoteKey = EntityKey("jobs", "remote-1")

        SqliteSyncStore(database).use { store ->
            store.stageLocal(pending("m-1", localKey, """{"name":"local"}"""))
            store.markRetryable(MutationId("m-1"))
            store.commitRemotePage(
                plans = listOf(
                    RemoteApplyPlan(
                        mutation = RemoteMutation(
                            remoteMutationId = "r-1",
                            entity = remoteKey,
                            kind = MutationKind.UPSERT,
                            payload = """{"status":"assigned"}""",
                            serverVersion = 7,
                        ),
                        decision = ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL,
                    ),
                ),
                nextCursor = "cursor-7",
            )
        }

        SqliteSyncStore(database).use { reopened ->
            assertEquals(1, reopened.pendingCount())
            assertEquals(1, reopened.pending(10).single().attempts)
            assertEquals("""{"name":"local"}""", reopened.record(localKey)?.payload)
            assertEquals("cursor-7", reopened.checkpoint())
            assertTrue(reopened.wasRemoteApplied("r-1"))
            assertEquals("""{"status":"assigned"}""", reopened.record(remoteKey)?.payload)
            assertEquals(7, reopened.record(remoteKey)?.serverVersion)
        }
    }

    @Test
    public fun `duplicate mutation id is rejected without partial state`(): Unit = runBlocking {
        val database = Files.createTempDirectory("fieldsync-duplicate").resolve("fieldsync.db")
        val firstKey = EntityKey("assets", "a-1")
        val secondKey = EntityKey("assets", "a-2")

        SqliteSyncStore(database).use { store ->
            store.stageLocal(pending("m-1", firstKey, """{"v":1}"""))

            assertSuspendFailsWith<IllegalArgumentException> {
                store.stageLocal(pending("m-1", secondKey, """{"v":2}"""))
            }

            assertEquals(1, store.pendingCount())
            assertNotNull(store.record(firstKey))
            assertNull(store.record(secondKey))
        }
    }

    @Test
    public fun `server wins commit atomically drops local pending and survives restart`(): Unit = runBlocking {
        val database = Files.createTempDirectory("fieldsync-server-wins").resolve("fieldsync.db")
        val key = EntityKey("assets", "a-1")
        val remote = RemoteMutation(
            remoteMutationId = "r-9",
            entity = key,
            kind = MutationKind.UPSERT,
            payload = """{"name":"server"}""",
            serverVersion = 9,
        )

        SqliteSyncStore(database).use { store ->
            store.stageLocal(pending("m-local", key, """{"name":"local"}"""))
            store.commitRemotePage(
                plans = listOf(RemoteApplyPlan(remote, ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL)),
                nextCursor = "cursor-9",
            )

            assertEquals(0, store.pendingCount())
            assertEquals("""{"name":"server"}""", store.record(key)?.payload)
            assertEquals(9, store.record(key)?.serverVersion)
        }

        SqliteSyncStore(database).use { reopened ->
            assertEquals(0, reopened.pendingCount())
            assertEquals("""{"name":"server"}""", reopened.record(key)?.payload)
            assertEquals(9, reopened.record(key)?.serverVersion)
            assertEquals("cursor-9", reopened.checkpoint())
            assertTrue(reopened.wasRemoteApplied("r-9"))
        }
    }

    @Test
    public fun `remote replay remains idempotent while checkpoint advances`(): Unit = runBlocking {
        val database = Files.createTempDirectory("fieldsync-replay").resolve("fieldsync.db")
        val key = EntityKey("jobs", "j-1")
        val remote = RemoteMutation("r-1", key, MutationKind.UPSERT, """{"state":"open"}""", 1)

        SqliteSyncStore(database).use { store ->
            store.commitRemotePage(
                listOf(RemoteApplyPlan(remote, ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL)),
                "cursor-1",
            )
            store.commitRemotePage(
                listOf(RemoteApplyPlan(remote, ConflictDecision.APPLY_REMOTE_AND_DROP_LOCAL)),
                "cursor-2",
            )

            assertEquals("""{"state":"open"}""", store.record(key)?.payload)
            assertEquals(1, store.record(key)?.serverVersion)
            assertEquals("cursor-2", store.checkpoint())
            assertTrue(store.wasRemoteApplied("r-1"))
        }
    }

    @Test
    public fun `permanent failure bucket survives restart`(): Unit = runBlocking {
        val database = Files.createTempDirectory("fieldsync-failure").resolve("fieldsync.db")
        val key = EntityKey("assets", "a-1")

        SqliteSyncStore(database).use { store ->
            store.stageLocal(pending("m-1", key, """{"v":1}"""))
            store.markRetryable(MutationId("m-1"))
            store.markPermanentFailure(MutationId("m-1"), "validation")
            assertEquals(0, store.pendingCount())
            assertEquals(1, store.permanentFailureCount())
        }

        SqliteSyncStore(database).use { reopened ->
            assertEquals(0, reopened.pendingCount())
            assertEquals(1, reopened.permanentFailureCount())
            assertEquals("""{"v":1}""", reopened.record(key)?.payload)
        }
    }

    private fun pending(id: String, key: EntityKey, payload: String): PendingMutation = PendingMutation(
        mutationId = MutationId(id),
        entity = key,
        kind = MutationKind.UPSERT,
        payload = payload,
        createdAtEpochMillis = 1,
    )

    private suspend inline fun <reified T : Throwable> assertSuspendFailsWith(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("Expected ${T::class.simpleName}, caught ${error::class.simpleName}", error)
        }
        throw AssertionError("Expected ${T::class.simpleName} to be thrown")
    }
}
