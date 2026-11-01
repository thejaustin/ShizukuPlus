package af.shizuku.manager.adb

import af.shizuku.manager.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

class FakeAdbPairingActivity : Activity() {
    companion object {
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_FINGERPRINT = "fingerprint"
        private const val PROMPT_TIMEOUT_SECONDS = 60L

        private class Request {
            val latch = CountDownLatch(1)

            @Volatile var allowed = false
        }

        // Each prompt answers only its own request, so a second connection can't take the
        // user's tap meant for the first one.
        private val pending = ConcurrentHashMap<String, Request>()

        // One prompt at a time; a connection that arrives while one is showing is refused.
        private val promptLock = ReentrantLock()

        /** Blocks until the user answers, for at most a minute; anything but Allow is a no. */
        fun requestPairingSync(
            context: Context,
            pubKeyStr: String,
        ): Boolean {
            if (!promptLock.tryLock()) return false
            val id = UUID.randomUUID().toString()
            val request = Request()
            pending[id] = request
            try {
                context.startActivity(
                    Intent(context, FakeAdbPairingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(EXTRA_REQUEST_ID, id)
                        putExtra(EXTRA_FINGERPRINT, fingerprint(pubKeyStr))
                    },
                )
                return request.latch.await(PROMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && request.allowed
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } finally {
                pending.remove(id)
                promptLock.unlock()
            }
        }

        /** MD5 of the key, colon-separated, the same form Android's USB debugging prompt shows. */
        fun fingerprint(pubKeyStr: String): String {
            val keyBytes =
                runCatching { Base64.decode(pubKeyStr.substringBefore(' '), Base64.DEFAULT) }
                    .getOrDefault(pubKeyStr.toByteArray())
            return MessageDigest
                .getInstance("MD5")
                .digest(keyBytes)
                .joinToString(":") { "%02X".format(it) }
        }

        private fun answer(
            id: String?,
            allowed: Boolean,
        ) {
            val request = id?.let { pending[it] } ?: return
            // First answer wins: onDestroy's fallback Deny must not overwrite a tap on Allow.
            if (request.latch.count == 0L) return
            request.allowed = allowed
            request.latch.countDown()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_REQUEST_ID)
        // A request that already timed out (or a recreated activity) has nothing to answer.
        if (id == null || !pending.containsKey(id)) {
            finish()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fake_adb_pairing_title)
            .setMessage(getString(R.string.fake_adb_pairing_message, intent.getStringExtra(EXTRA_FINGERPRINT).orEmpty()))
            .setPositiveButton(R.string.fake_adb_pairing_allow) { _, _ ->
                answer(id, true)
                finish()
            }.setNegativeButton(R.string.fake_adb_pairing_deny) { _, _ ->
                answer(id, false)
                finish()
            }.setOnCancelListener {
                answer(id, false)
                finish()
            }.setCancelable(false)
            .show()
    }

    override fun onDestroy() {
        // Leaving the prompt any other way (back, recents, rotation) counts as Deny.
        if (isFinishing) answer(intent.getStringExtra(EXTRA_REQUEST_ID), false)
        super.onDestroy()
    }
}
