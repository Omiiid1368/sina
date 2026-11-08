package ir.hezarzist

import android.content.Context

data class LocalProfile(val name: String = "", val avatarId: String = "leaf")
data class LocalAvatar(val id: String, val symbol: String, val title: String)

/** All avatars ship in the APK. A local profile has no account or contact fields. */
val localAvatars = listOf(
    LocalAvatar("leaf", "❧", "برگ"), LocalAvatar("cell", "◉", "یاخته"),
    LocalAvatar("dna", "⌘", "ژن"), LocalAvatar("neuron", "✧", "نورون")
)

class LocalProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val profile: LocalProfile get() = LocalProfile(
        normalizeName(prefs.getString("name", "").orEmpty()),
        prefs.getString("avatarId", "leaf").takeIf { candidate -> localAvatars.any { it.id == candidate } } ?: "leaf"
    )

    fun save(displayName: String, avatarId: String) {
        require(localAvatars.any { it.id == avatarId }) { "نماد محلی معتبر نیست." }
        check(prefs.edit().putString("name", normalizeName(displayName)).putString("avatarId", avatarId).commit()) {
            "ذخیرهٔ نمایه انجام نشد. دوباره تلاش کنید."
        }
    }

    companion object {
        const val PREFS = "local_profile_v1"
        const val MAX_NAME_LENGTH = 40
        fun normalizeName(value: String): String = value.filter { ch ->
            !ch.isISOControl() && ch !in '\u202a'..'\u202e' && ch !in '\u2066'..'\u2069'
        }.trim().take(MAX_NAME_LENGTH)
    }
}
