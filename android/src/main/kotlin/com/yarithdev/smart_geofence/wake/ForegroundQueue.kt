package com.yarithdev.smart_geofence.wake

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import com.yarithdev.smart_geofence.core.safeLong
import com.yarithdev.smart_geofence.core.safeString
import com.yarithdev.smart_geofence.logging.SmartGeofenceLogger
import org.json.JSONArray
import org.json.JSONObject

enum class ForegroundWorkKind {
    CONFIRM_PROXIMITY,
    CONFIRM_OUTSIDE,
    CONFIRM_INSIDE;

    companion object {
        fun fromPersistedName(name: String): ForegroundWorkKind? =
            when (name) {
                "CONFIRM_NEAREST", "CONFIRM_NEAR" -> CONFIRM_PROXIMITY
                else -> runCatching { valueOf(name) }.getOrNull()
            }
    }
}

internal fun normalizedConfirmDedupeKey(kind: ForegroundWorkKind): String =
    when (kind) {
        ForegroundWorkKind.CONFIRM_PROXIMITY -> "confirm:proximity"
        ForegroundWorkKind.CONFIRM_OUTSIDE -> "confirm:outside"
        ForegroundWorkKind.CONFIRM_INSIDE -> "confirm:inside"
    }

internal fun proximityConfirmDedupeKey(source: String): String {
    val baseKey = normalizedConfirmDedupeKey(ForegroundWorkKind.CONFIRM_PROXIMITY)
    return when (source) {
        Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
        Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS,
        -> "$baseKey:owner:pulse"
        else -> baseKey
    }
}

internal fun shouldSuppressRunningPulseEnqueue(
    source: String,
    foregroundWorkRunning: Boolean,
    runningDedupeKey: String?,
): Boolean = foregroundWorkRunning &&
    isPulseConfirmSource(source) &&
    runningDedupeKey == proximityConfirmDedupeKey(source)

internal fun nativeConfirmDedupeKey(
    kind: ForegroundWorkKind,
    fenceIds: Collection<String>,
): String {
    val ids = normalizedNativeFenceIds(fenceIds)
        .joinToString("|") { fenceId -> "${fenceId.length}:$fenceId" }
    return "${normalizedConfirmDedupeKey(kind)}:native:$ids"
}

private fun normalizedNativeFenceIds(fenceIds: Collection<String>): List<String> =
    fenceIds.asSequence()
        .filter { it.isNotBlank() }
        .distinct()
        .sorted()
        .toList()

private fun validatedNativeFenceIds(
    kind: ForegroundWorkKind,
    dedupeKey: String,
    fenceIds: Collection<String>,
): List<String> {
    val normalized = normalizedNativeFenceIds(fenceIds)
    return normalized.takeIf { ids ->
        ids.isEmpty() || dedupeKey == nativeConfirmDedupeKey(kind, ids)
    }.orEmpty()
}

private fun normalizedNativeTransitionInstances(
    instances: Map<String, String>,
): Map<String, String> = linkedMapOf<String, String>().apply {
    instances.entries
        .asSequence()
        .filter { (fenceId, instanceId) -> fenceId.isNotBlank() && instanceId.isNotBlank() }
        .sortedBy { it.key }
        .forEach { (fenceId, instanceId) -> put(fenceId, instanceId) }
}

private fun validatedNativeTransitionInstances(
    kind: ForegroundWorkKind,
    dedupeKey: String,
    fenceIds: Collection<String>,
    instances: Map<String, String>,
): Map<String, String> {
    val normalizedIds = normalizedNativeFenceIds(fenceIds)
    val normalizedInstances = normalizedNativeTransitionInstances(instances)
    return normalizedInstances.takeIf {
        normalizedIds.isNotEmpty() &&
            it.keys == normalizedIds.toSet() &&
            dedupeKey == nativeConfirmDedupeKey(kind, normalizedIds)
    }.orEmpty()
}

internal fun mergedConfirmNotBefore(
    existingNotBeforeMillis: Long,
    requestedNotBeforeMillis: Long,
    preserveExistingDeadline: Boolean,
    preservePendingRetryDeadline: Boolean = false,
): Long =
    when {
        preservePendingRetryDeadline -> existingNotBeforeMillis
        preserveExistingDeadline -> minOf(existingNotBeforeMillis, requestedNotBeforeMillis)
        else -> requestedNotBeforeMillis
    }

internal fun coalesceConfirmWork(items: List<ForegroundWorkItem>): List<ForegroundWorkItem> {
    val coalesced = linkedMapOf<String, ForegroundWorkItem>()
    items.forEach { item ->
        val existing = coalesced[item.dedupeKey]
        if (existing == null) {
            coalesced[item.dedupeKey] = item
        } else {
            val newest = if (item.createdAtMillis >= existing.createdAtMillis) item else existing
            val nativeFenceIds = normalizedNativeFenceIds(
                existing.nativeFenceIds + item.nativeFenceIds,
            )
            val candidateInstances = newest.nativeTransitionInstances.ifEmpty {
                existing.nativeTransitionInstances
            }
            val merged = newest.copy(
                source = mergedForegroundWorkSource(existing.source, item.source),
                notBeforeMillis = minOf(existing.notBeforeMillis, item.notBeforeMillis),
                sessionStartedAtMillis = minNonZero(
                    existing.sessionStartedAtMillis,
                    item.sessionStartedAtMillis,
                ),
                attemptCount = maxOf(existing.attemptCount, item.attemptCount),
                refreshCount = maxOf(existing.refreshCount, item.refreshCount),
                lastRefreshAtMillis = maxOf(existing.lastRefreshAtMillis, item.lastRefreshAtMillis),
                lastAttemptAtMillis = maxOf(existing.lastAttemptAtMillis, item.lastAttemptAtMillis),
                lastFailureReason = newest.lastFailureReason ?: existing.lastFailureReason,
                parkedReason = newest.parkedReason ?: existing.parkedReason,
                traceId = newest.traceId ?: existing.traceId,
                mutationVersion = maxOf(existing.mutationVersion, item.mutationVersion),
                nativeFenceIds = nativeFenceIds,
                nativeTransitionInstances = validatedNativeTransitionInstances(
                    newest.kind,
                    newest.dedupeKey,
                    nativeFenceIds,
                    candidateInstances,
                ),
            )
            coalesced.remove(item.dedupeKey)
            coalesced[item.dedupeKey] = merged
        }
    }
    return coalesced.values.toList()
}

data class ForegroundWorkItem(
    val id: Long,
    val kind: ForegroundWorkKind,
    val source: String,
    val fenceId: String?,
    val createdAtMillis: Long,
    val notBeforeMillis: Long,
    val dedupeKey: String,
    val sessionStartedAtMillis: Long = createdAtMillis,
    val attemptCount: Int = 0,
    val refreshCount: Int = 0,
    val lastRefreshAtMillis: Long = createdAtMillis,
    val lastAttemptAtMillis: Long = 0L,
    val lastFailureReason: String? = null,
    val parkedReason: String? = null,
    val traceId: String? = null,
    val mutationVersion: Long = 0L,
    val nativeFenceIds: List<String> = emptyList(),
    val nativeTransitionInstances: Map<String, String> = emptyMap(),
) {
    val isProximityConfirm: Boolean
        get() = kind == ForegroundWorkKind.CONFIRM_PROXIMITY

    val isOutsideConfirm: Boolean
        get() = kind == ForegroundWorkKind.CONFIRM_OUTSIDE

    val isInsideConfirm: Boolean
        get() = kind == ForegroundWorkKind.CONFIRM_INSIDE

    val isParked: Boolean
        get() = parkedReason != null

    fun isReadyAt(nowMillis: Long): Boolean = !isParked && notBeforeMillis <= nowMillis

    fun sessionAgeAt(nowMillis: Long): Long =
        (nowMillis - sessionStartedAtMillis).coerceAtLeast(0L)
}

internal data class ForegroundWorkIdentity(
    val id: Long,
    val createdAtMillis: Long,
    val dedupeKey: String,
    val mutationVersion: Long,
)

internal enum class ForegroundQueueMutationStatus {
    REPLACED,
    CLAIMED,
    REMOVED,
    MISSING,
    SUPERSEDED,
}

internal data class ForegroundQueueMutationResult(
    val status: ForegroundQueueMutationStatus,
    val item: ForegroundWorkItem? = null,
    val currentIdentity: ForegroundWorkIdentity? = null,
)

internal data class ParkedForegroundWorkSummary(
    val count: Int,
    val earliestSessionStartedAtMillis: Long?,
)

internal fun ForegroundWorkItem.identity(): ForegroundWorkIdentity =
    ForegroundWorkIdentity(
        id = id,
        createdAtMillis = createdAtMillis,
        dedupeKey = dedupeKey,
        mutationVersion = mutationVersion,
    )

object ForegroundQueue {
    private const val TAG = "ForegroundQueue"
    internal const val KEY_QUEUE = "foreground_work_queue"
    internal const val KEY_NEXT_ID = "foreground_work_next_id"
    private const val MAX_PENDING_WORK = 32

    @Synchronized
    fun enqueueProximityConfirm(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        traceId: String? = null,
    ): ForegroundWorkItem =
        enqueue(
            context = context,
            kind = ForegroundWorkKind.CONFIRM_PROXIMITY,
            source = source,
            fenceId = null,
            notBeforeMillis = notBeforeMillis,
            dedupeKey = proximityConfirmDedupeKey(source),
            preserveExistingDeadline = preserveExistingDeadline,
            parkedReason = null,
            traceId = traceId,
        )

    @Synchronized
    fun enqueueOutsideConfirm(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        dedupeKey: String = normalizedConfirmDedupeKey(ForegroundWorkKind.CONFIRM_OUTSIDE),
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        traceId: String? = null,
    ): ForegroundWorkItem =
        enqueue(
            context = context,
            kind = ForegroundWorkKind.CONFIRM_OUTSIDE,
            source = source,
            fenceId = null,
            notBeforeMillis = notBeforeMillis,
            dedupeKey = dedupeKey,
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = nativeTransitionInstances,
            preserveExistingDeadline = preserveExistingDeadline,
            parkedReason = null,
            traceId = traceId,
        )

    @Synchronized
    fun enqueueInsideConfirm(
        context: Context,
        source: String,
        notBeforeMillis: Long,
        preserveExistingDeadline: Boolean = true,
        dedupeKey: String = normalizedConfirmDedupeKey(ForegroundWorkKind.CONFIRM_INSIDE),
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        traceId: String? = null,
    ): ForegroundWorkItem =
        enqueue(
            context = context,
            kind = ForegroundWorkKind.CONFIRM_INSIDE,
            source = source,
            fenceId = null,
            notBeforeMillis = notBeforeMillis,
            dedupeKey = dedupeKey,
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = nativeTransitionInstances,
            preserveExistingDeadline = preserveExistingDeadline,
            parkedReason = null,
            traceId = traceId,
        )

    @Synchronized
    internal fun rearmIfUnchanged(
        context: Context,
        item: ForegroundWorkItem,
        notBeforeMillis: Long,
        attemptCount: Int,
        failureReason: String,
    ): ForegroundQueueMutationResult {
        val current = currentForMutation(context.applicationContext, item)
        if (current.status != null) return current.toResult()
        val replacement = enqueue(
            context = context,
            kind = item.kind,
            source = item.source,
            fenceId = item.fenceId,
            notBeforeMillis = notBeforeMillis,
            dedupeKey = item.dedupeKey,
            nativeFenceIds = item.nativeFenceIds,
            nativeTransitionInstances = item.nativeTransitionInstances,
            preserveExistingDeadline = false,
            parkedReason = null,
            sessionStartedAtMillis = item.sessionStartedAtMillis,
            attemptCount = attemptCount,
            lastFailureReason = failureReason,
            lastAttemptAtMillis = System.currentTimeMillis(),
            refreshCount = item.refreshCount,
            lastRefreshAtMillis = item.lastRefreshAtMillis,
            traceId = item.traceId,
        )
        return ForegroundQueueMutationResult(
            status = ForegroundQueueMutationStatus.REPLACED,
            item = replacement,
            currentIdentity = replacement.identity(),
        )
    }

    @Synchronized
    internal fun parkIfUnchanged(
        context: Context,
        item: ForegroundWorkItem,
        attemptCount: Int,
        parkedReason: String,
        failureReason: String,
    ): ForegroundQueueMutationResult {
        val current = currentForMutation(context.applicationContext, item)
        if (current.status != null) return current.toResult()
        val replacement = enqueue(
            context = context,
            kind = item.kind,
            source = item.source,
            fenceId = item.fenceId,
            notBeforeMillis = Long.MAX_VALUE,
            dedupeKey = item.dedupeKey,
            nativeFenceIds = item.nativeFenceIds,
            nativeTransitionInstances = item.nativeTransitionInstances,
            preserveExistingDeadline = false,
            parkedReason = parkedReason,
            sessionStartedAtMillis = item.sessionStartedAtMillis,
            attemptCount = attemptCount,
            lastFailureReason = failureReason,
            lastAttemptAtMillis = System.currentTimeMillis(),
            refreshCount = item.refreshCount,
            lastRefreshAtMillis = item.lastRefreshAtMillis,
            traceId = item.traceId,
        )
        return ForegroundQueueMutationResult(
            status = ForegroundQueueMutationStatus.REPLACED,
            item = replacement,
            currentIdentity = replacement.identity(),
        )
    }

    @Synchronized
    internal fun claimIfUnchanged(
        context: Context,
        item: ForegroundWorkItem,
    ): ForegroundQueueMutationResult {
        val appContext = context.applicationContext
        val queue = read(appContext).toMutableList()
        val currentIndex = queue.indexOfFirst { it.dedupeKey == item.dedupeKey }
        if (currentIndex < 0) {
            return ForegroundQueueMutationResult(ForegroundQueueMutationStatus.MISSING)
        }
        val current = queue[currentIndex]
        if (current.identity() != item.identity()) {
            return ForegroundQueueMutationResult(
                status = ForegroundQueueMutationStatus.SUPERSEDED,
                currentIdentity = current.identity(),
            )
        }
        queue.removeAt(currentIndex)
        persist(appContext, queue)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Claimed foreground work id=${item.id} dedupe=${item.dedupeKey} " +
                "mutation=${item.mutationVersion} pending=${queue.size}.",
        )
        return ForegroundQueueMutationResult(
            status = ForegroundQueueMutationStatus.CLAIMED,
            item = current,
        )
    }

    @Synchronized
    fun markReady(
        context: Context,
        item: ForegroundWorkItem,
        readyAtMillis: Long,
    ): ForegroundWorkItem? {
        val appContext = context.applicationContext
        val queue = read(appContext)
        val index = queue.indexOfFirst { it.identity() == item.identity() }
        if (index < 0) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Could not mark foreground work ready; item changed id=${item.id} " +
                    "kind=${item.kind} source=${item.source}.",
            )
            return null
        }
        val updated = queue[index].copy(
            notBeforeMillis = readyAtMillis,
            mutationVersion = nextMutationVersion(queue[index].mutationVersion),
        )
        persist(appContext, queue.toMutableList().also { it[index] = updated })
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Marked foreground work ready id=${updated.id} kind=${updated.kind} " +
                "source=${updated.source} notBefore=${updated.notBeforeMillis} " +
                "attempts=${updated.attemptCount} refreshes=${updated.refreshCount}.",
        )
        return updated
    }

    @Synchronized
    fun unparkByReason(
        context: Context,
        parkedReason: String,
        notBeforeMillisFor: (ForegroundWorkKind) -> Long,
    ): Int {
        val appContext = context.applicationContext
        val queue = read(appContext)
        var changed = 0
        val updated = queue.map { item ->
            if (item.parkedReason != parkedReason) return@map item
            changed += 1
            val now = System.currentTimeMillis()
            item.copy(
                createdAtMillis = now,
                notBeforeMillis = notBeforeMillisFor(item.kind),
                parkedReason = null,
                lastRefreshAtMillis = now,
                refreshCount = item.refreshCount + 1,
                mutationVersion = nextMutationVersion(item.mutationVersion),
            )
        }
        if (changed > 0) {
            persist(appContext, updated)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Unparked $changed foreground confirm task(s) reason=$parkedReason.",
            )
        }
        return changed
    }

    @Synchronized
    fun peekReady(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): ForegroundWorkItem? =
        read(context.applicationContext).firstOrNull { it.isReadyAt(nowMillis) }

    @Synchronized
    fun nextReadyAtMillis(context: Context): Long? =
        read(context.applicationContext)
            .filterNot { it.isParked }
            .minOfOrNull { it.notBeforeMillis }

    @Synchronized
    internal fun removeIfUnchanged(
        context: Context,
        completedItem: ForegroundWorkItem,
    ): ForegroundQueueMutationResult {
        val appContext = context.applicationContext
        val queue = read(appContext).toMutableList()
        val currentIndex = queue.indexOfFirst { it.dedupeKey == completedItem.dedupeKey }
        if (currentIndex < 0) {
            return ForegroundQueueMutationResult(ForegroundQueueMutationStatus.MISSING)
        }
        val current = queue[currentIndex]
        if (current.identity() != completedItem.identity()) {
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Kept superseding foreground work currentId=${current.id} " +
                    "completedId=${completedItem.id} dedupe=${completedItem.dedupeKey}.",
            )
            return ForegroundQueueMutationResult(
                status = ForegroundQueueMutationStatus.SUPERSEDED,
                currentIdentity = current.identity(),
            )
        }
        queue.removeAt(currentIndex)
        persist(appContext, queue)
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Removed completed foreground work id=${completedItem.id} pending=${queue.size}.",
        )
        return ForegroundQueueMutationResult(
            status = ForegroundQueueMutationStatus.REMOVED,
            item = current,
        )
    }

    @Synchronized
    fun clearConfirmWork(context: Context) {
        val appContext = context.applicationContext
        val queue = read(appContext)
        persist(appContext, emptyList())
        SmartGeofenceLogger.d(appContext, TAG, "Cleared ${queue.size} pending foreground confirm task(s).")
    }

    @Synchronized
    fun removeBySource(context: Context, source: String): Int {
        val appContext = context.applicationContext
        val queue = read(appContext)
        val remaining = removeWorkByExactSource(queue, source)
        val removed = queue.size - remaining.size
        if (removed > 0) {
            persist(appContext, remaining)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Removed $removed foreground work item(s) source=$source pending=${remaining.size}.",
            )
        }
        return removed
    }

    @Synchronized
    fun removeBySourcePrefix(context: Context, sourcePrefix: String): Int {
        val appContext = context.applicationContext
        val queue = read(appContext)
        val remaining = queue.filterNot { it.source.startsWith(sourcePrefix) }
        val removed = queue.size - remaining.size
        if (removed > 0) {
            persist(appContext, remaining)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Removed $removed foreground work item(s) sourcePrefix=$sourcePrefix " +
                    "pending=${remaining.size}.",
            )
        }
        return removed
    }

    @Synchronized
    fun count(context: Context): Int =
        read(context.applicationContext).count { !it.isParked }

    @Synchronized
    fun totalCount(context: Context): Int = read(context.applicationContext).size

    @Synchronized
    fun parkedCount(context: Context): Int =
        read(context.applicationContext).count { it.isParked }

    @Synchronized
    internal fun parkedSummary(
        context: Context,
        parkedReason: String,
    ): ParkedForegroundWorkSummary {
        val matching = read(context.applicationContext)
            .filter { it.parkedReason == parkedReason }
        return ParkedForegroundWorkSummary(
            count = matching.size,
            earliestSessionStartedAtMillis = matching
                .map { it.sessionStartedAtMillis }
                .minOrNull(),
        )
    }

    @Synchronized
    fun countReady(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): Int = read(context.applicationContext).count { it.isReadyAt(nowMillis) }

    @Synchronized
    fun diagnosticItems(
        context: Context,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Map<String, Any?>> =
        read(context.applicationContext).map { item ->
            linkedMapOf(
                "id" to item.id,
                "kind" to item.kind.name.lowercase(),
                "source" to item.source,
                "fenceId" to item.fenceId,
                "nativeFenceIds" to item.nativeFenceIds,
                "nativeTransitionInstanceCount" to item.nativeTransitionInstances.size,
                "createdAtMillis" to item.createdAtMillis,
                "notBeforeMillis" to item.notBeforeMillis,
                "ready" to item.isReadyAt(nowMillis),
                "sessionStartedAtMillis" to item.sessionStartedAtMillis,
                "sessionAgeMillis" to item.sessionAgeAt(nowMillis),
                "attemptCount" to item.attemptCount,
                "refreshCount" to item.refreshCount,
                "lastRefreshAtMillis" to item.lastRefreshAtMillis,
                "lastAttemptAtMillis" to item.lastAttemptAtMillis.takeIf { it > 0L },
                "lastFailureReason" to item.lastFailureReason,
                "parkedReason" to item.parkedReason,
                "dedupeKey" to item.dedupeKey,
                "traceId" to item.traceId,
                "mutationVersion" to item.mutationVersion,
            )
        }

    private fun enqueue(
        context: Context,
        kind: ForegroundWorkKind,
        source: String,
        fenceId: String?,
        notBeforeMillis: Long,
        dedupeKey: String,
        nativeFenceIds: Collection<String> = emptyList(),
        nativeTransitionInstances: Map<String, String> = emptyMap(),
        preserveExistingDeadline: Boolean,
        parkedReason: String?,
        sessionStartedAtMillis: Long? = null,
        attemptCount: Int? = null,
        lastFailureReason: String? = null,
        lastAttemptAtMillis: Long? = null,
        refreshCount: Int? = null,
        lastRefreshAtMillis: Long? = null,
        traceId: String? = null,
    ): ForegroundWorkItem {
        val appContext = context.applicationContext
        val queue = read(appContext).toMutableList()
        val prefs = prefs(appContext)
        val nextId = prefs.safeLong(KEY_NEXT_ID, 0L) + 1L
        val now = System.currentTimeMillis()
        val existingIndex = queue.indexOfFirst { it.dedupeKey == dedupeKey }
        if (existingIndex >= 0) {
            val existing = queue.removeAt(existingIndex)
            val validatedFenceIds = validatedNativeFenceIds(
                kind,
                dedupeKey,
                nativeFenceIds.ifEmpty { existing.nativeFenceIds },
            )
            val validatedInstances = validatedNativeTransitionInstances(
                kind,
                dedupeKey,
                validatedFenceIds,
                nativeTransitionInstances.ifEmpty { existing.nativeTransitionInstances },
            )
            val resetExhaustedSession =
                parkedReason == null &&
                    attemptCount == null &&
                    existing.parkedReason ==
                    Constants.CONFIRM_PARKED_REASON_TRANSIENT_FAILURES_EXHAUSTED
            val preservePendingPulseRetryDeadline =
                kind == ForegroundWorkKind.CONFIRM_PROXIMITY &&
                    isPulseConfirmSource(existing.source) &&
                    existing.attemptCount > 0 &&
                    existing.parkedReason == null &&
                    existing.notBeforeMillis > now &&
                    attemptCount == null
            val item = existing.copy(
                id = nextId,
                source = mergedForegroundWorkSource(existing.source, source),
                fenceId = fenceId,
                nativeFenceIds = validatedFenceIds,
                nativeTransitionInstances = validatedInstances,
                createdAtMillis = now,
                notBeforeMillis = mergedConfirmNotBefore(
                    existing.notBeforeMillis,
                    notBeforeMillis,
                    preserveExistingDeadline,
                    preservePendingPulseRetryDeadline,
                ),
                sessionStartedAtMillis = sessionStartedAtMillis
                    ?: if (resetExhaustedSession) now else existing.sessionStartedAtMillis,
                attemptCount = attemptCount
                    ?: if (resetExhaustedSession) 0 else existing.attemptCount,
                refreshCount = refreshCount ?: (existing.refreshCount + 1),
                lastRefreshAtMillis = lastRefreshAtMillis ?: now,
                lastAttemptAtMillis = lastAttemptAtMillis
                    ?: if (resetExhaustedSession) 0L else existing.lastAttemptAtMillis,
                lastFailureReason = lastFailureReason
                    ?: if (resetExhaustedSession) null else existing.lastFailureReason,
                parkedReason = parkedReason,
                traceId = traceId ?: existing.traceId,
                mutationVersion = nextMutationVersion(existing.mutationVersion),
            )
            queue.add(item)
            persist(appContext, queue, nextId)
            SmartGeofenceLogger.d(
                appContext,
                TAG,
                "Replaced duplicate foreground work with id=${item.id} kind=$kind " +
                    "source=$source fence=$fenceId notBefore=${item.notBeforeMillis} " +
                    "attempts=${item.attemptCount} refreshes=${item.refreshCount} " +
                    "parked=${item.parkedReason}."
            )
            return item
        }

        val validatedFenceIds = validatedNativeFenceIds(kind, dedupeKey, nativeFenceIds)
        val item = ForegroundWorkItem(
            id = nextId,
            kind = kind,
            source = source,
            fenceId = fenceId,
            createdAtMillis = now,
            notBeforeMillis = notBeforeMillis,
            dedupeKey = dedupeKey,
            sessionStartedAtMillis = sessionStartedAtMillis ?: now,
            attemptCount = attemptCount ?: 0,
            refreshCount = refreshCount ?: 0,
            lastRefreshAtMillis = lastRefreshAtMillis ?: now,
            lastAttemptAtMillis = lastAttemptAtMillis ?: 0L,
            lastFailureReason = lastFailureReason,
            parkedReason = parkedReason,
            traceId = traceId,
            nativeFenceIds = validatedFenceIds,
            nativeTransitionInstances = validatedNativeTransitionInstances(
                kind,
                dedupeKey,
                validatedFenceIds,
                nativeTransitionInstances,
            ),
        )
        queue.add(item)
        val trimmed = queue.takeLast(MAX_PENDING_WORK)
        persist(appContext, trimmed, nextId)
        if (trimmed.size != queue.size) {
            SmartGeofenceLogger.w(
                appContext,
                TAG,
                "Dropped ${queue.size - trimmed.size} old foreground work item(s); limit=$MAX_PENDING_WORK."
            )
        }
        SmartGeofenceLogger.d(
            appContext,
            TAG,
            "Queued foreground work id=${item.id} kind=$kind source=$source " +
                "fence=$fenceId notBefore=${item.notBeforeMillis} " +
                "attempts=${item.attemptCount} refreshes=${item.refreshCount} " +
                "parked=${item.parkedReason} pending=${trimmed.count { !it.isParked }}."
        )
        return item
    }

    private fun read(context: Context): List<ForegroundWorkItem> {
        LegacyWakeQueueMigration.migrate(context)
        val raw = prefs(context).safeString(KEY_QUEUE) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            coalesceConfirmWork((0 until array.length()).mapNotNull { index ->
                try {
                    fromJson(array.getJSONObject(index))
                } catch (e: Throwable) {
                    SmartGeofenceLogger.w(context, TAG, "Skipping malformed foreground work: ${e.message}", e)
                    null
                }
            })
        } catch (e: Throwable) {
            SmartGeofenceLogger.w(context, TAG, "Failed to parse foreground queue: ${e.message}", e)
            emptyList()
        }
    }

    private fun persist(
        context: Context,
        items: List<ForegroundWorkItem>,
        nextId: Long? = null,
    ) {
        val array = JSONArray()
        items.forEach { array.put(toJson(it)) }
        val editor = prefs(context).edit().putString(KEY_QUEUE, array.toString())
        if (nextId != null) editor.putLong(KEY_NEXT_ID, nextId)
        editor.apply()
    }

    internal fun toJson(item: ForegroundWorkItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("kind", item.kind.name)
        put("source", item.source)
        put("createdAt", item.createdAtMillis)
        put("notBefore", item.notBeforeMillis)
        put("dedupeKey", item.dedupeKey)
        put("sessionStartedAt", item.sessionStartedAtMillis)
        put("attemptCount", item.attemptCount)
        put("refreshCount", item.refreshCount)
        put("lastRefreshAt", item.lastRefreshAtMillis)
        put("lastAttemptAt", item.lastAttemptAtMillis)
        if (item.lastFailureReason != null) put("lastFailureReason", item.lastFailureReason)
        if (item.parkedReason != null) put("parkedReason", item.parkedReason)
        if (item.fenceId != null) put("fenceId", item.fenceId)
        if (item.nativeFenceIds.isNotEmpty()) {
            put("nativeFenceIds", JSONArray(item.nativeFenceIds))
        }
        if (item.nativeTransitionInstances.isNotEmpty()) {
            put(
                "nativeTransitionInstances",
                JSONArray().apply {
                    item.nativeTransitionInstances.forEach { (fenceId, instanceId) ->
                        put(
                            JSONObject()
                                .put("fenceId", fenceId)
                                .put("instanceId", instanceId),
                        )
                    }
                },
            )
        }
        if (item.traceId != null) put("traceId", item.traceId)
        put("mutationVersion", item.mutationVersion)
    }

    internal fun fromJson(value: JSONObject): ForegroundWorkItem? {
        val source = value.optString("source").takeIf { it.isNotBlank() } ?: return null
        val kind = ForegroundWorkKind.fromPersistedName(value.optString("kind")) ?: return null
        val fenceId = value.optString("fenceId").takeIf { it.isNotBlank() }
        val persistedNativeFenceIds = value.optJSONArray("nativeFenceIds")?.let { array ->
            normalizedNativeFenceIds(
                (0 until array.length()).mapNotNull { index ->
                    (array.opt(index) as? String)?.takeIf { it.isNotBlank() }
                },
            )
        }.orEmpty()
        val persistedDedupeKey = value.optString("dedupeKey")
            .takeIf { it.isValidFor(kind) }
            ?: normalizedConfirmDedupeKey(kind)
        val dedupeKey = if (kind == ForegroundWorkKind.CONFIRM_PROXIMITY &&
            isPulseConfirmSource(source)
        ) {
            proximityConfirmDedupeKey(source)
        } else {
            persistedDedupeKey
        }
        val nativeFenceIds = validatedNativeFenceIds(kind, dedupeKey, persistedNativeFenceIds)
        val persistedNativeTransitionInstances =
            value.optJSONArray("nativeTransitionInstances")?.let { array ->
                linkedMapOf<String, String>().apply {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val persistedFenceId = item.opt("fenceId") as? String ?: continue
                        val instanceId = item.opt("instanceId") as? String ?: continue
                        if (persistedFenceId.isNotBlank() && instanceId.isNotBlank()) {
                            put(persistedFenceId, instanceId)
                        }
                    }
                }
            }.orEmpty()
        return ForegroundWorkItem(
            id = value.getLong("id"),
            kind = kind,
            source = source,
            fenceId = fenceId,
            createdAtMillis = value.optLong("createdAt", 0L),
            notBeforeMillis = value.optLong("notBefore", 0L),
            dedupeKey = dedupeKey,
            sessionStartedAtMillis = value.optLong(
                "sessionStartedAt",
                value.optLong("createdAt", 0L),
            ),
            attemptCount = value.optInt("attemptCount", 0),
            refreshCount = value.optInt("refreshCount", 0),
            lastRefreshAtMillis = value.optLong(
                "lastRefreshAt",
                value.optLong("createdAt", 0L),
            ),
            lastAttemptAtMillis = value.optLong("lastAttemptAt", 0L),
            lastFailureReason = value.optString("lastFailureReason").takeIf { it.isNotBlank() },
            parkedReason = value.optString("parkedReason").takeIf { it.isNotBlank() },
            traceId = value.optString("traceId").takeIf { it.isNotBlank() },
            mutationVersion = value.optLong("mutationVersion", 0L),
            nativeFenceIds = nativeFenceIds,
            nativeTransitionInstances = validatedNativeTransitionInstances(
                kind,
                dedupeKey,
                nativeFenceIds,
                persistedNativeTransitionInstances,
            ),
        )
    }

    private fun currentForMutation(
        context: Context,
        item: ForegroundWorkItem,
    ): CurrentForegroundMutation {
        val current = read(context).firstOrNull { it.dedupeKey == item.dedupeKey }
            ?: return CurrentForegroundMutation(ForegroundQueueMutationStatus.MISSING, null)
        return if (current.identity() == item.identity()) {
            CurrentForegroundMutation(null, current)
        } else {
            CurrentForegroundMutation(ForegroundQueueMutationStatus.SUPERSEDED, current)
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
}

private data class CurrentForegroundMutation(
    val status: ForegroundQueueMutationStatus?,
    val current: ForegroundWorkItem?,
) {
    fun toResult(): ForegroundQueueMutationResult =
        ForegroundQueueMutationResult(
            status = checkNotNull(status),
            currentIdentity = current?.identity(),
        )
}

private fun nextMutationVersion(current: Long): Long =
    if (current == Long.MAX_VALUE) 0L else current + 1L

internal fun removeWorkByExactSource(
    items: List<ForegroundWorkItem>,
    source: String,
): List<ForegroundWorkItem> = items.filterNot { it.source == source }

private fun String.isValidFor(kind: ForegroundWorkKind): Boolean {
    val baseKey = normalizedConfirmDedupeKey(kind)
    val nativePrefix = "$baseKey:native:"
    val ownedProximityKeys = if (kind == ForegroundWorkKind.CONFIRM_PROXIMITY) {
        setOf(
            proximityConfirmDedupeKey(
                Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM,
            ),
            "$baseKey:owner:pulse_boundary",
            "$baseKey:owner:fused_liveness",
        )
    } else {
        emptySet()
    }
    return this == baseKey ||
        this in ownedProximityKeys ||
        (startsWith(nativePrefix) && length > nativePrefix.length)
}

private fun mergedForegroundWorkSource(
    existingSource: String,
    incomingSource: String,
): String {
    val existingNative = isNativeTransitionConfirmSource(existingSource)
    val incomingNative = isNativeTransitionConfirmSource(incomingSource)
    return when {
        existingNative && !incomingNative -> existingSource
        incomingNative && !existingNative -> incomingSource
        existingSource == Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM &&
            incomingSource == Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS ->
            existingSource
        incomingSource == Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM ->
            incomingSource
        else -> incomingSource
    }
}

internal fun isPulseConfirmSource(source: String): Boolean =
    source == Constants.EVENT_SOURCE_SMART_GEOFENCE_PROXIMITY_PULSE_CONFIRM ||
        source == Constants.EVENT_SOURCE_SMART_GEOFENCE_FUSED_LIVENESS

private fun isNativeTransitionConfirmSource(source: String): Boolean =
    source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_EXIT_CONFIRM) ||
        source.startsWith(Constants.EVENT_SOURCE_SMART_GEOFENCE_NATIVE_ENTER_CONFIRM)

private fun minNonZero(a: Long, b: Long): Long =
    when {
        a <= 0L -> b
        b <= 0L -> a
        else -> minOf(a, b)
    }
