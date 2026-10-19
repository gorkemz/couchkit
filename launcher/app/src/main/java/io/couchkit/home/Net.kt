// SPDX-License-Identifier: Apache-2.0
package io.couchkit.home

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTPS GET used by all data sources. Call from a background thread. */
object Net {
    class Resp(val code: Int, val body: ByteArray?, val headers: Map<String, String> = emptyMap()) {
        val text get() = body?.decodeToString()
    }

    fun get(url: String, headers: Map<String, String> = emptyMap(), readTimeout: Int = 10_000): Resp = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = readTimeout
            c.setRequestProperty("Accept", "application/json, image/*")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            val code = c.responseCode
            val h = HashMap<String, String>()
            for ((k, v) in c.headerFields) if (k != null && v.isNotEmpty()) h[k.lowercase()] = v[0]
            Resp(code, if (code == 200) c.inputStream.use { it.readBytes() } else null, h)
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "GET ${url.substringBefore('?')}: ${e.message}"); Resp(-1, null)
    }

    /** Body of a 200 response, or null. */
    fun bytes(url: String, headers: Map<String, String> = emptyMap()) = get(url, headers, 15_000).body
    fun text(url: String, headers: Map<String, String> = emptyMap()) = get(url, headers).text
}
