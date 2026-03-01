package com.sidespot.history

import android.accounts.Account
import android.accounts.AccountManager
import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PlayHistoryEntry(
    val contextUri: String,
    val contextName: String,
    val contextType: String, // "album", "playlist", "show"
    val artistName: String = "",
    val imageUrl: String? = null,
    val playedAtMs: Long,
)

class PlayHistoryManager(context: Context) {

    companion object {
        private const val ACCOUNT_TYPE = "com.sidespot.auth"
        private const val ACCOUNT_NAME = "Spotify"
        private const val KEY_ENTRIES = "play_history"
        private const val MAX_ENTRIES = 200
    }

    private val am = AccountManager.get(context.applicationContext)
    private val account = Account(ACCOUNT_NAME, ACCOUNT_TYPE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun recordPlay(entry: PlayHistoryEntry) {
        val entries = loadEntries().toMutableList()
        entries.removeAll { it.contextUri == entry.contextUri }
        entries.add(0, entry)
        if (entries.size > MAX_ENTRIES) {
            entries.subList(MAX_ENTRIES, entries.size).clear()
        }
        val serialized = json.encodeToString(entries)
        am.setUserData(account, KEY_ENTRIES, serialized)
    }

    @Synchronized
    fun loadEntries(): List<PlayHistoryEntry> {
        val raw = am.getUserData(account, KEY_ENTRIES) ?: return emptyList()
        return try {
            json.decodeFromString<List<PlayHistoryEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
