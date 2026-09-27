package dev.pocketrun.license

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the activated key and exposes it as observable state. The key is
 * re-verified on every launch and whenever the user pastes a new one; nothing is
 * cached as "already validated".
 */
class LicenseManager(
    context: Context,
    private val publicKey: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("license", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(evaluate())
    val state: StateFlow<LicenseState> = _state.asStateFlow()

    sealed interface LicenseState {
        data object NeedsActivation : LicenseState
        data class Active(
            val claims: LicenseFormat.Claims,
            val fingerprint: String,
            val daysRemaining: Long?,
        ) : LicenseState {
            val canUseAgent: Boolean get() = claims.plan in AGENT_PLANS
        }

        data class Rejected(val message: String) : LicenseState
    }

    /** Accepts a pasted key. Returns true when it verified. */
    fun activate(rawKey: String): Boolean {
        val key = LicenseFormat.normalize(rawKey)
        if (key.isEmpty()) {
            _state.value = LicenseState.Rejected("Enter a license key.")
            return false
        }
        return when (val outcome = LicenseVerifier.verify(key, publicKey, now())) {
            is LicenseVerifier.Outcome.Valid -> {
                prefs.edit().putString(KEY, key).apply()
                _state.value = LicenseState.Active(
                    claims = outcome.claims,
                    fingerprint = outcome.keyFingerprint,
                    daysRemaining = LicenseVerifier.daysRemaining(outcome.claims, now()),
                )
                true
            }

            is LicenseVerifier.Outcome.Expired -> {
                _state.value = LicenseState.Rejected("This license expired.")
                false
            }

            is LicenseVerifier.Outcome.NotYetValid -> {
                _state.value = LicenseState.Rejected("This license is not valid yet.")
                false
            }

            is LicenseVerifier.Outcome.Rejected -> {
                _state.value = LicenseState.Rejected(outcome.reason)
                false
            }
        }
    }

    fun forget() {
        prefs.edit().remove(KEY).apply()
        _state.value = LicenseState.NeedsActivation
    }

    private fun evaluate(): LicenseState {
        val stored = prefs.getString(KEY, null)
        if (stored.isNullOrBlank()) return LicenseState.NeedsActivation
        return when (val outcome = LicenseVerifier.verify(stored, publicKey, now())) {
            is LicenseVerifier.Outcome.Valid -> LicenseState.Active(
                claims = outcome.claims,
                fingerprint = outcome.keyFingerprint,
                daysRemaining = LicenseVerifier.daysRemaining(outcome.claims, now()),
            )

            is LicenseVerifier.Outcome.Expired ->
                LicenseState.Rejected(
                    "This license expired on " +
                        java.time.Instant.ofEpochSecond(outcome.claims.expiresAt) + ".",
                )

            is LicenseVerifier.Outcome.NotYetValid ->
                LicenseState.Rejected("This license is not valid yet.")

            is LicenseVerifier.Outcome.Rejected -> LicenseState.Rejected(outcome.reason)
        }
    }

    companion object {
        private const val KEY = "key"
        private val AGENT_PLANS = setOf("pro", "team", "lifetime")
    }
}
