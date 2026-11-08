package ir.hezarzist

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/** Device-only state. Epochs prevent an old ViewModel overwriting a restored backup. */
object LocalProgress {
    const val actor = "anonymous"
    var dataVersion by mutableIntStateOf(0)
        private set

    fun initialize(context: Context) {
        // The namespace intentionally retains the published offline version's preferences.
        context.getSharedPreferences("account_cache_epochs_v2", Context.MODE_PRIVATE)
    }

    fun namespace(@Suppress("UNUSED_PARAMETER") context: Context): String = actor

    fun profileEpoch(context: Context, namespace: String = actor): Long =
        context.getSharedPreferences("account_cache_epochs_v2", Context.MODE_PRIVATE).getLong(namespace, 0L)

    /** Invoke before replacing preferences. Epochs are deliberately excluded from backups. */
    @Synchronized fun invalidate(context: Context) {
        val prefs = context.getSharedPreferences("account_cache_epochs_v2", Context.MODE_PRIVATE)
        check(prefs.edit().putLong(actor, prefs.getLong(actor, 0L) + 1L).commit())
    }

    /** Invoke on the main thread once an atomic local import/migration has committed. */
    @Synchronized fun notifyChanged(@Suppress("UNUSED_PARAMETER") context: Context) { dataVersion++ }
}
