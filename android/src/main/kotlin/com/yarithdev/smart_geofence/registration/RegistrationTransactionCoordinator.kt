package com.yarithdev.smart_geofence.registration

import android.content.Context
import com.yarithdev.smart_geofence.core.Constants
import java.util.UUID

internal interface RegistrationRevisionStore {
    fun read(): Long

    fun write(value: Long)
}

internal class SharedPreferencesRegistrationRevisionStore(context: Context) :
    RegistrationRevisionStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        Constants.PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun read(): Long = preferences.getLong(KEY_REVISION, 0L)

    override fun write(value: Long) {
        check(preferences.edit().putLong(KEY_REVISION, value).commit()) {
            "Failed to commit the smart_geofence registration revision."
        }
    }

    private companion object {
        const val KEY_REVISION = "registration_transaction_revision_v1"
    }
}

internal data class RegistrationTransactionGrant(
    val acquired: Boolean,
    val token: String? = null,
    val revision: Long? = null,
    val activeOperation: String? = null,
)

internal data class RegistrationTransactionValidation(
    val valid: Boolean,
    val reason: String? = null,
)

internal class RegistrationTransactionCoordinator(
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class ActiveTransaction(
        val ownerId: String,
        val token: String,
        val revision: Long,
        val operation: String,
        val cleanupFenceIds: Set<String>,
    )

    private var active: ActiveTransaction? = null

    @Synchronized
    fun hasActiveTransaction(): Boolean = active != null

    @Synchronized
    fun activeCleanupFenceIds(): Set<String> =
        active?.cleanupFenceIds?.toSet().orEmpty()

    @Synchronized
    fun begin(
        ownerId: String,
        operation: String,
        revisionStore: RegistrationRevisionStore,
        cleanupFenceIds: Set<String> = emptySet(),
    ): RegistrationTransactionGrant {
        val current = active
        if (current != null) {
            return RegistrationTransactionGrant(
                acquired = false,
                activeOperation = current.operation,
            )
        }
        val revision = revisionStore.read()
        val token = tokenFactory()
        active = ActiveTransaction(
            ownerId = ownerId,
            token = token,
            revision = revision,
            operation = operation,
            cleanupFenceIds = cleanupFenceIds.filterTo(linkedSetOf()) { it.isNotBlank() },
        )
        return RegistrationTransactionGrant(
            acquired = true,
            token = token,
            revision = revision,
        )
    }

    @Synchronized
    fun validate(
        ownerId: String,
        token: String,
        revision: Long,
        revisionStore: RegistrationRevisionStore,
    ): RegistrationTransactionValidation {
        val current = active
            ?: return RegistrationTransactionValidation(false, "no_active_transaction")
        if (current.ownerId != ownerId || current.token != token) {
            return RegistrationTransactionValidation(false, "transaction_not_owned")
        }
        if (current.revision != revision) {
            return RegistrationTransactionValidation(false, "snapshot_revision_mismatch")
        }
        if (revisionStore.read() != revision) {
            return RegistrationTransactionValidation(false, "persisted_revision_changed")
        }
        return RegistrationTransactionValidation(true)
    }

    @Synchronized
    fun commit(
        ownerId: String,
        token: String,
        revision: Long,
        revisionStore: RegistrationRevisionStore,
        advanceRevision: Boolean = true,
    ): Long {
        val validation = validate(ownerId, token, revision, revisionStore)
        check(validation.valid) {
            "Registration transaction is no longer current: ${validation.reason}."
        }
        val committedRevision = if (advanceRevision) Math.addExact(revision, 1L) else revision
        if (advanceRevision) revisionStore.write(committedRevision)
        active = null
        return committedRevision
    }

    @Synchronized
    fun abort(ownerId: String, token: String): Boolean {
        val current = active ?: return false
        if (current.ownerId != ownerId || current.token != token) return false
        active = null
        return true
    }

    @Synchronized
    fun releaseOwner(ownerId: String) {
        if (active?.ownerId == ownerId) active = null
    }
}

internal object SmartGeofenceRegistrationTransactions {
    val coordinator = RegistrationTransactionCoordinator()
}
