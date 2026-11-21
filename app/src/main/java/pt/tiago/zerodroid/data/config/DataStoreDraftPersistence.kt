package pt.tiago.zerodroid.data.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.configDraftStore by preferencesDataStore("zerodroid-config")
private val DRAFT = stringPreferencesKey("config_draft")

/** Keeps the non-secret part of the draft across process death. */
class DataStoreDraftPersistence(private val context: Context) : DraftPersistence {
    override suspend fun load(): String? = context.configDraftStore.data.first()[DRAFT]
    override suspend fun save(json: String) {
        context.configDraftStore.edit { it[DRAFT] = json }
    }
}
