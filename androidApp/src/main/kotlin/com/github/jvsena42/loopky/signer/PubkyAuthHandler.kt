package com.github.jvsena42.loopky.signer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.github.jvsena42.loopky.MainActivity
import com.github.jvsena42.loopky.data.repository.SignerRepository
import com.github.jvsena42.loopky.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns the `pubkyauth://` handler (`.MainActivityPubkyAuth` in the manifest) on while Loopky
 * holds a registered key and off otherwise, so a Ring user's links keep going to Ring.
 *
 * The component state persists across launches, so a process killed mid-sign-out could leave it
 * on; that is safe, because approval itself checks the key ([SignerRepository.approve]).
 */
class PubkyAuthHandler(
    private val context: Context,
    private val signer: SignerRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alias = ComponentName(context, "${MainActivity::class.java.name}PubkyAuth")

    fun start() {
        scope.launch {
            signer.canApprove.collect(::setEnabled)
        }
    }

    private fun setEnabled(enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            context.packageManager.setComponentEnabledSetting(alias, state, PackageManager.DONT_KILL_APP)
        }.onFailure { Log.e(TAG, "setEnabled($enabled): FAILED — ${it.message}", it) }
    }

    private companion object {
        const val TAG = "Loopky/PubkyAuthHandler"
    }
}
