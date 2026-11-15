package af.shizuku.manager.doctor

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.ShizukuSettings.Keys
import af.shizuku.manager.database.RootCompatHelper
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Google Wallet and Messages (RCS) refuse to work when they see root traces. This finds the ones
 * Shizuku+ can cause and lets the user pick which to undo, then refreshes what those apps cached.
 */
object WalletRepair {
    enum class Step(
        val labelRes: Int,
    ) {
        REMOVE_SU_FILES(R.string.wallet_step_remove_su),
        SU_BRIDGE_OFF(R.string.wallet_step_su_bridge_off),
        MOCKING_OFF(R.string.wallet_step_mocking_off),
        SPOOF_OFF(R.string.wallet_step_spoof_off),
        ROOT_COMPAT_OFF(R.string.wallet_step_root_compat_off),
        REFRESH_INTEGRITY(R.string.wallet_step_refresh_integrity),
        RESTART_RCS(R.string.wallet_step_restart_rcs),
        CLEAR_WALLET(R.string.wallet_step_clear_wallet),
    }

    /** Steps that don't depend on a finding; the user's last choice for them is remembered. */
    private val ACTIONS = listOf(Step.REFRESH_INTEGRITY, Step.RESTART_RCS, Step.CLEAR_WALLET)
    private const val KEY_CHOSEN_ACTIONS = "wallet_repair_actions"

    data class Findings(
        val suFiles: Boolean,
        val suBridge: Boolean,
        val mocking: Boolean,
        val spoof: Boolean,
        val rootCompat: Boolean,
    ) {
        /** SU Bridge alone is the user's choice: worth showing, not a fault. */
        val hasFault get() = (suFiles && !suBridge) || mocking || spoof || rootCompat
        val any get() = hasFault || suBridge
    }

    data class Option(
        val step: Step,
        val checked: Boolean,
    )

    data class Result(
        val suFilesRemoved: Boolean?,
        val integrity: RootCompatHelper.WalletRefreshResult?,
        val rcsRestarted: Boolean?,
    )

    /** Asks Shizuku too: the app itself often can't see into /data/local. */
    suspend fun scan() =
        Findings(
            suFiles = RootCompatHelper.isBridgePresentInTmp(),
            suBridge = ShizukuSettings.isSuBridgeEnabled(),
            mocking = ShizukuSettings.isRootMagiskMockingEnabled() || ShizukuSettings.isRootBusyboxMockingEnabled(),
            spoof = ShizukuSettings.isSpoofDeviceEnabled() || ShizukuSettings.isRootBuildPropRedirectEnabled(),
            rootCompat =
                ShizukuSettings.isExperimentalRootCompatEnabled() ||
                    ShizukuSettings.isRootAutoGrantEnabled() ||
                    ShizukuSettings.isRootFileInterceptorEnabled(),
        )

    /** Found traces are pre-checked, except SU Bridge, which the user turned on on purpose. */
    fun options(f: Findings): List<Option> {
        val chosen = ShizukuSettings.getPreferences()?.getStringSet(KEY_CHOSEN_ACTIONS, null)

        fun remembered(
            step: Step,
            default: Boolean,
        ) = chosen?.contains(step.name) ?: default
        return buildList {
            // Always offered, for certainty: unchecked when nothing was found.
            if (!f.suBridge) add(Option(Step.REMOVE_SU_FILES, f.suFiles))
            if (f.suBridge) add(Option(Step.SU_BRIDGE_OFF, false))
            if (f.mocking) add(Option(Step.MOCKING_OFF, true))
            if (f.spoof) add(Option(Step.SPOOF_OFF, true))
            if (f.rootCompat) add(Option(Step.ROOT_COMPAT_OFF, true))
            add(Option(Step.REFRESH_INTEGRITY, remembered(Step.REFRESH_INTEGRITY, true)))
            add(Option(Step.RESTART_RCS, remembered(Step.RESTART_RCS, true)))
            // Signs the user out of Wallet, so never on unless they picked it before.
            add(Option(Step.CLEAR_WALLET, remembered(Step.CLEAR_WALLET, false)))
        }
    }

    /**
     * What "Fix all" runs unasked: undo the found faults only. Restarting other apps, SU Bridge and
     * Wallet's data are left to the dialog, where the user picks them.
     */
    fun defaultSteps(f: Findings) =
        options(f)
            .filter { it.checked && it.step !in ACTIONS && it.step != Step.SU_BRIDGE_OFF }
            .map { it.step }
            .toSet()

    suspend fun run(
        context: Context,
        steps: Set<Step>,
        remember: Boolean = true,
    ): Result =
        withContext(Dispatchers.IO) {
            val prefs = ShizukuSettings.getPreferences()
            if (remember) {
                prefs?.edit()?.putStringSet(KEY_CHOSEN_ACTIONS, steps.filter { it in ACTIONS }.map { it.name }.toSet())?.apply()
            }
            prefs
                ?.edit()
                ?.apply {
                    if (Step.SU_BRIDGE_OFF in steps) putBoolean(Keys.KEY_SU_BRIDGE_ENABLED, false)
                    if (Step.MOCKING_OFF in steps) {
                        putBoolean(Keys.KEY_ROOT_MAGISK_MOCKING_ENABLED, false)
                        putBoolean(Keys.KEY_ROOT_BUSYBOX_MOCKING_ENABLED, false)
                    }
                    if (Step.SPOOF_OFF in steps) {
                        putBoolean(Keys.KEY_SPOOF_DEVICE_ENABLED, false)
                        putBoolean(Keys.KEY_ROOT_BUILD_PROP_REDIRECT_ENABLED, false)
                    }
                    if (Step.ROOT_COMPAT_OFF in steps) {
                        putBoolean(Keys.KEY_EXPERIMENTAL_ROOT_COMPAT, false)
                        putBoolean(Keys.KEY_ROOT_AUTO_GRANT_ENABLED, false)
                        putBoolean(Keys.KEY_ROOT_FILE_INTERCEPTOR_ENABLED, false)
                    }
                }?.commit()
            ShizukuSettings.syncAllPlusFeaturesToServer()

            // Turning SU Bridge off without removing its file would leave the trace in place.
            val removeFiles = Step.REMOVE_SU_FILES in steps || Step.SU_BRIDGE_OFF in steps
            val removed = if (removeFiles) RootCompatHelper.removeSuResidue(bridgeTools = Step.SU_BRIDGE_OFF in steps) else null
            val integrity =
                if (Step.REFRESH_INTEGRITY in steps || Step.CLEAR_WALLET in steps) {
                    RootCompatHelper.refreshGoogleWalletAttestation(context, clearWallet = Step.CLEAR_WALLET in steps)
                } else {
                    null
                }
            val rcs = if (Step.RESTART_RCS in steps) RootCompatHelper.restartMessagingRcs() else null
            Result(removed, integrity, rcs)
        }
}
