package af.shizuku.manager.utils

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import af.shizuku.manager.ShizukuSettings

object SettingsBackupManager {

    private const val VERSION_KEY = "_version"
    private const val BACKUP_VERSION = 1

    private val EXCLUDED_KEYS = setOf(
        ShizukuSettings.Keys.KEY_LAST_DB_UPDATE,
        ShizukuSettings.Keys.KEY_REMOTE_DB_JSON,
        ShizukuSettings.Keys.KEY_LAST_UPDATE_CHECK,
        ShizukuSettings.Keys.KEY_LAST_CHECK_FAILED,
        ShizukuSettings.Keys.KEY_MIGRATION_OFFERED,
    )

    fun export(context: Context): String {
        val prefs: SharedPreferences = context.getSharedPreferences(ShizukuSettings.NAME, Context.MODE_PRIVATE)
        val json = JSONObject()
        json.put(VERSION_KEY, BACKUP_VERSION)
        for ((key, value) in prefs.all) {
            if (key in EXCLUDED_KEYS) continue
            when (value) {
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value)
                is String -> json.put(key, value)
                is Set<*> -> json.put(key, org.json.JSONArray(value.toList()))
            }
        }
        return json.toString(2)
    }

    fun import(context: Context, json: String): Boolean {
        return try {
            val obj = JSONObject(json)
            val version = obj.optInt(VERSION_KEY, -1)
            if (version < 1) return false

            val prefs = context.getSharedPreferences(ShizukuSettings.NAME, Context.MODE_PRIVATE)
            val editor = prefs.edit()

            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key == VERSION_KEY || key in EXCLUDED_KEYS) continue
                when (val v = obj.get(key)) {
                    is Boolean -> editor.putBoolean(key, v)
                    is Int -> editor.putInt(key, v)
                    is Long -> editor.putLong(key, v)
                    is Double -> editor.putFloat(key, v.toFloat())
                    is String -> editor.putString(key, v)
                    is org.json.JSONArray -> {
                        val set = (0 until v.length()).map { v.getString(it) }.toSet()
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.apply()
            true
        } catch (e: Exception) {
            false
        }
    }
}
