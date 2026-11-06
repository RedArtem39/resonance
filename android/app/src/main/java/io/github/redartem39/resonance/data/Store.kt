package io.github.redartem39.resonance.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Tiny key-value store (the counterpart of the desktop app's localStorage + profile files). */
object Store {
    private lateinit var sp: SharedPreferences
    fun init(ctx: Context) { sp = ctx.applicationContext.getSharedPreferences("resonance", Context.MODE_PRIVATE) }

    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun float(k: String, d: Float) = sp.getFloat(k, d)
    fun string(k: String, d: String) = sp.getString(k, d) ?: d
    fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    fun put(k: String, v: Float) = sp.edit().putFloat(k, v).apply()
    fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    fun strings(k: String): List<String> = runCatching {
        val a = JSONArray(sp.getString(k, "[]")); List(a.length()) { a.getString(it) }
    }.getOrDefault(emptyList())
    fun putStrings(k: String, v: Collection<String>) = sp.edit().putString(k, JSONArray(v.toList()).toString()).apply()

    fun floats(k: String, n: Int): FloatArray = runCatching {
        val a = JSONArray(sp.getString(k, "[]")); if (a.length() != n) FloatArray(n) else FloatArray(n) { a.getDouble(it).toFloat() }
    }.getOrDefault(FloatArray(n))
    fun putFloats(k: String, v: FloatArray) = sp.edit().putString(k, JSONArray(v.map { it.toDouble() }).toString()).apply()

    fun json(k: String): JSONObject = runCatching { JSONObject(sp.getString(k, "{}")) }.getOrDefault(JSONObject())
    fun putJson(k: String, v: JSONObject) = sp.edit().putString(k, v.toString()).apply()
}
