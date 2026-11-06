package io.github.redartem39.resonance.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Cookie import. Browser extensions export JSON (Cookie-Editor style) or Netscape cookies.txt.
 * Only YouTube/Google cookies are kept — nothing else is needed, and nothing else should sit in our profile.
 * Stored as Netscape text; [header] turns it into a Cookie header for requests to YouTube.
 */
object Cookies {
    private val KEEP = Regex("(^|\\.)(youtube\\.com|google\\.com)$", RegexOption.IGNORE_CASE)

    class Result(val text: String, val count: Int)

    private fun fromJson(list: JSONArray): Result {
        val lines = arrayListOf("# Netscape HTTP Cookie File", "# Converted by Resonance — contains login session secrets, keep private", "")
        var count = 0
        for (i in 0 until list.length()) {
            val c = list.optJSONObject(i) ?: continue
            val domain = c.optString("domain"); val name = c.optString("name")
            if (name.isEmpty() || !KEEP.containsMatchIn(domain.removePrefix("."))) continue
            val hostOnly = c.optBoolean("hostOnly")
            val dom = if (hostOnly) domain.removePrefix(".") else if (domain.startsWith(".")) domain else ".$domain"
            val expiry = if (c.optBoolean("session") || !c.has("expirationDate")) 0 else c.optDouble("expirationDate").toLong()
            val value = c.optString("value").replace(Regex("[\\r\\n\\t]"), "")
            lines += "${if (c.optBoolean("httpOnly")) "#HttpOnly_" else ""}$dom\t${if (hostOnly) "FALSE" else "TRUE"}\t${c.optString("path", "/")}\t${if (c.optBoolean("secure")) "TRUE" else "FALSE"}\t$expiry\t$name\t$value"
            count++
        }
        return Result(lines.joinToString("\n") + "\n", count)
    }

    /** Accepts either the JSON export or an already-Netscape file. Throws a readable error. */
    fun normalize(raw: String): Result {
        val s = raw.removePrefix("﻿").trim()
        if (s.startsWith("[") || s.startsWith("{")) {
            val list = try {
                if (s.startsWith("[")) JSONArray(s) else JSONObject(s).optJSONArray("cookies")
            } catch (_: Exception) { error("Файл похож на JSON, но не читается") } ?: error("В JSON нет списка cookies")
            val out = fromJson(list)
            if (out.count == 0) error("В файле нет cookies youtube.com / google.com")
            return out
        }
        val rows = s.split(Regex("\\r?\\n")).filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) && it.split('\t').size >= 7 }
        val kept = rows.filter { KEEP.containsMatchIn(it.removePrefix("#HttpOnly_").split('\t')[0].removePrefix(".")) }
        if (kept.isEmpty()) error("В файле нет cookies youtube.com / google.com")
        return Result("# Netscape HTTP Cookie File\n" + kept.joinToString("\n") + "\n", kept.size)
    }

    fun count(text: String): Int = text.split(Regex("\\r?\\n")).count { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }

    fun header(text: String): String =
        text.split(Regex("\\r?\\n")).filter { it.isNotEmpty() && (!it.startsWith("#") || it.startsWith("#HttpOnly_")) }
            .mapNotNull { l -> l.split('\t').takeIf { it.size >= 7 }?.let { "${it[5]}=${it[6]}" } }.joinToString("; ")
}
