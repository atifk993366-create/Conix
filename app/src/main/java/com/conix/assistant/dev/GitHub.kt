package com.conix.assistant.dev

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class GitHub(private val token: String, val repo: String) {
    fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        val c = URL("https://api.github.com/repos/$repo$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        c.setRequestProperty("User-Agent", "KoriAI")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        return Pair(code, text)
    }

    /** Downloads a build artifact (GitHub answers with a redirect to a signed address). */
    fun downloadArtifact(artifactId: Long, dest: File) {
        val c = URL("https://api.github.com/repos/$repo/actions/artifacts/$artifactId/zip").openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15000
        c.readTimeout = 120000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "KoriAI")
        val code = c.responseCode
        if (code in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw IllegalStateException("No download address")
            c.disconnect()
            val d = URL(loc).openConnection() as HttpURLConnection
            d.connectTimeout = 15000
            d.readTimeout = 120000
            d.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            d.disconnect()
        } else if (code in 200..299) {
            c.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            c.disconnect()
        } else {
            throw IllegalStateException("HTTP $code while downloading the build")
        }
    }
}
