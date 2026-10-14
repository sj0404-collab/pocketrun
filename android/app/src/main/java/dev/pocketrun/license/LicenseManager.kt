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
 *
 * [sharedKey] is the key the build itself carries: it is tried first, so a
 * published release works for everyone without anyone pasting anything. The
 * activation screen is still there for a key of one's own, and one that verifies
 * is stored and used instead of the shared one.
 *
 * Verification uses [MonotonicClock], so turning the device clock back cannot
 * bring an expired key back to life.
 */
class LicenseManager(
    context: Context,
    private val publicKey: String,
    private val sharedKey: String = "",
    private val clock: MonotonicClock = MonotonicClock.forContext(context),
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
            val isShared: Boolean = false,
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
        return when (val outcome = LicenseVerifier.verify(key, publicKey, clock.now())) {
            is LicenseVerifier.Outcome.Valid -> {
                prefs.edit().putString(KEY, key).apply()
                _state.value = outcome.toActive(shared = false)
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
        _state.value = sharedState()
    }

    private fun evaluate(): LicenseState {
        val stored = prefs.getString(KEY, null)
        if (stored.isNullOrBlank()) return sharedState()
        return when (val outcome = LicenseVerifier.verify(LicenseFormat.normalize(stored), publicKey, clock.now())) {
            is LicenseVerifier.Outcome.Valid -> outcome.toActive(shared = false)

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

    /**
     * The key the build carries. A build whose shared key does not match its own
     * public key is broken, and that must not lock the user out of the activation
     * screen - so an unusable shared key is simply not used.
     */
    private fun sharedState(): LicenseState {
        val key = LicenseFormat.normalize(sharedKey)
        if (key.isEmpty()) return LicenseState.NeedsActivation
        val outcome = LicenseVerifier.verify(key, publicKey, clock.now())
        return if (outcome is LicenseVerifier.Outcome.Valid) {
            outcome.toActive(shared = true)
        } else {
            LicenseState.NeedsActivation
        }
    }

    private fun LicenseVerifier.Outcome.Valid.toActive(shared: Boolean): LicenseState.Active = LicenseState.Active(
        claims = claims,
        fingerprint = keyFingerprint,
        daysRemaining = LicenseVerifier.daysRemaining(claims, clock.now()),
        isShared = shared,
    )

    companion object {
        private const val KEY = "key"
        private val AGENT_PLANS = setOf("pro", "team", "lifetime")
    }
}
