package com.flugel.server

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Yalniz HTTPS, dogrulanan sunucu sertifikasi ve bearer token. Yönlendirme kapalı. */
class ApiClient(private val base: String, private val token: String) {
    fun get(path: String): JSONObject = request("GET", path)
    fun post(path: String): JSONObject = request("POST", path)

    private fun request(method: String, path: String): JSONObject {
        require(base.startsWith("https://") && path.startsWith("/v1/"))
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.requestMethod = method
        connection.connectTimeout = 7000
        connection.readTimeout = 12000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/json")
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "{}"
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            return JSONObject(body)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        fun segment(value: String): String = Uri.encode(value)
    }
}
