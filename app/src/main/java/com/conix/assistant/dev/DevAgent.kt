package com.conix.assistant.dev

import android.content.Context
import android.util.Base64
import com.conix.assistant.core.KoriCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream

class Edit(val path: String, val find: String, val replace: String)
class Proposal(val summary: String, val edits: List<Edit>)
class RunInfo(val id: Long, val status: String, val conclusion: String, val url: String)

/**
 * Lets Kori read its own source on GitHub, ask the AI for a fix or a new feature, push it to a new
 * branch, start the cloud build, and install the result (Android always asks you to confirm the install).
 */
class DevAgent(private val core: KoriCore, private val ctx: Context) {

    private fun gh(): GitHub {
        val repo = core.prefs.repo
        val tok = core.secrets.get("github_token")
        if (repo.isBlank() || tok.isNullOrBlank()) throw IllegalStateException("Add your GitHub repo and token first.")
        return GitHub(tok, repo)
    }

    private fun defaultBranch(g: GitHub): String {
        val (c, t) = g.call("GET", "")
        if (c !in 200..299) throw IllegalStateException("GitHub: HTTP $c ${t.take(120)}")
        return JSONObject(t).optString("default_branch", "main")
    }

    private fun readFile(g: GitHub, path: String, ref: String): Pair<String, String> {
        val (c, t) = g.call("GET", "/contents/$path?ref=$ref")
        if (c !in 200..299) throw IllegalStateException("Could not read $path: HTTP $c")
        val o = JSONObject(t)
        return Pair(String(Base64.decode(o.getString("content"), Base64.DEFAULT), Charsets.UTF_8), o.getString("sha"))
    }

    private fun jsonObjectOf(t: String): JSONObject? {
        val s = t.indexOf('{')
        val e = t.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        return try { JSONObject(t.substring(s, e + 1)) } catch (_: Exception) { null }
    }

    private fun count(text: String, find: String): Int {
        var n = 0
        var i = text.indexOf(find)
        while (i >= 0) { n++; i = text.indexOf(find, i + find.length) }
        return n
    }

    suspend fun propose(request: String, crash: String?, log: (String) -> Unit): Proposal = withContext(Dispatchers.IO) {
        val g = gh()
        val base = defaultBranch(g)
        log("Reading the project...")
        val (ct, tt) = g.call("GET", "/git/trees/$base?recursive=1")
        if (ct !in 200..299) throw IllegalStateException("Could not read the file list: HTTP $ct")
        val paths = ArrayList<String>()
        val arr = JSONObject(tt).optJSONArray("tree") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val p = o.optString("path")
            val ok = p.endsWith(".kt") || p.endsWith(".kts") || p.endsWith(".xml") || p.endsWith(".yml")
            if (o.optString("type") == "blob" && ok && !p.contains("/build/")) paths.add(p)
        }
        val ask = "Request: $request\n" + (if (crash != null) "Crash log:\n${crash.take(3500)}\n" else "")
        log("Choosing the files to change...")
        val pick = core.brain.completeLong(
            "You are a senior Android (Kotlin, Jetpack Compose) engineer. From the file list choose the files that must be edited (at most 4). Reply ONLY with JSON: {\"files\":[\"path\",...]}",
            ask + "\nFiles:\n" + paths.joinToString("\n")
        )
        val fa = jsonObjectOf(pick)?.optJSONArray("files")
        val files = if (fa == null) emptyList() else List(fa.length()) { fa.getString(it) }.filter { it in paths }.take(4)
        if (files.isEmpty()) throw IllegalStateException("Could not decide which files to change.")
        val sources = LinkedHashMap<String, String>()
        for (f in files) sources[f] = readFile(g, f, base).first.take(70000 / files.size)

        log("Writing the change...")
        val system = "You are a senior Android (Kotlin, Jetpack Compose) engineer fixing or extending an existing app. Make the smallest correct change. " +
            "Reply ONLY with JSON: {\"summary\":\"one sentence\",\"edits\":[{\"path\":\"file\",\"find\":\"exact existing text, unique in that file\",\"replace\":\"new text\"}]}. " +
            "Every find must match the file exactly (including whitespace) and occur exactly once. The code must still compile: add needed imports as separate edits."
        val body = ask + "\n" + sources.entries.joinToString("\n") { "=== FILE: ${it.key} ===\n${it.value}" }
        var out = core.brain.completeLong(system, body)
        var lastProblems = ""
        for (attempt in 0..1) {
            val j = jsonObjectOf(out) ?: throw IllegalStateException("The AI did not return valid JSON.")
            val edits = ArrayList<Edit>()
            val problems = ArrayList<String>()
            val ea = j.optJSONArray("edits") ?: JSONArray()
            for (i in 0 until ea.length()) {
                val e = ea.getJSONObject(i)
                val path = e.optString("path")
                val find = e.optString("find")
                val src = sources[path]
                val n = if (src == null) -1 else count(src, find)
                when {
                    src == null -> problems.add("$path is not one of the provided files")
                    find.isEmpty() -> problems.add("empty find text in $path")
                    n != 1 -> problems.add("find text must occur exactly once in $path (found $n): ${find.take(60)}")
                    else -> edits.add(Edit(path, find, e.optString("replace")))
                }
            }
            lastProblems = problems.joinToString("; ")
            if (problems.isEmpty() && edits.isNotEmpty()) return@withContext Proposal(j.optString("summary", "Kori change"), edits)
            if (attempt == 1) {
                if (edits.isNotEmpty()) return@withContext Proposal(j.optString("summary", "Kori change"), edits)
                break
            }
            log("Fixing edits that did not match...")
            out = core.brain.completeLong(system, body + "\nYour previous answer had problems: " + lastProblems + "\nReturn the corrected JSON.")
        }
        throw IllegalStateException("The AI's edits did not match the code. " + lastProblems.take(300))
    }

    /** Pushes the change to a new branch and starts the cloud build. Returns the branch name. */
    suspend fun apply(p: Proposal, log: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val g = gh()
        val base = defaultBranch(g)
        val (cr, tr) = g.call("GET", "/git/ref/heads/$base")
        if (cr !in 200..299) throw IllegalStateException("Could not read $base: HTTP $cr")
        val sha = JSONObject(tr).getJSONObject("object").getString("sha")
        val branch = "kori-fix-" + SimpleDateFormat("MMdd-HHmmss", Locale.ENGLISH).format(Date())
        log("Creating branch $branch...")
        val (cb, tb) = g.call("POST", "/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        if (cb !in 200..299) throw IllegalStateException("Could not create the branch: HTTP $cb ${tb.take(120)}")
        for ((path, edits) in p.edits.groupBy { it.path }) {
            log("Updating $path...")
            val (text0, fileSha) = readFile(g, path, branch)
            var text = text0
            for (e in edits) {
                if (count(text, e.find) != 1) throw IllegalStateException("An edit no longer matches in $path")
                text = text.replace(e.find, e.replace)
            }
            val put = JSONObject()
                .put("message", "Kori: " + p.summary.take(70))
                .put("content", Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                .put("sha", fileSha)
                .put("branch", branch)
            val (cp, tp) = g.call("PUT", "/contents/$path", put)
            if (cp !in 200..299) throw IllegalStateException("Could not update $path: HTTP $cp ${tp.take(120)}")
        }
        log("Starting the build...")
        val (cd, td) = g.call("POST", "/actions/workflows/android.yml/dispatches", JSONObject().put("ref", branch))
        if (cd !in 200..299) throw IllegalStateException("Could not start the build: HTTP $cd ${td.take(120)}")
        branch
    }

    suspend fun latestRun(branch: String): RunInfo? = withContext(Dispatchers.IO) {
        val g = gh()
        val (c, t) = g.call("GET", "/actions/runs?branch=$branch&event=workflow_dispatch&per_page=1")
        if (c !in 200..299) throw IllegalStateException("HTTP $c")
        val r = JSONObject(t).optJSONArray("workflow_runs")?.optJSONObject(0) ?: return@withContext null
        RunInfo(r.getLong("id"), r.optString("status"), r.optString("conclusion"), r.optString("html_url"))
    }

    suspend fun downloadBuild(runId: Long): File = withContext(Dispatchers.IO) {
        val g = gh()
        val (c, t) = g.call("GET", "/actions/runs/$runId/artifacts")
        if (c !in 200..299) throw IllegalStateException("HTTP $c")
        val art = JSONObject(t).optJSONArray("artifacts")?.optJSONObject(0) ?: throw IllegalStateException("The build has no file yet.")
        val zip = File(ctx.cacheDir, "kori-build.zip")
        g.downloadArtifact(art.getLong("id"), zip)
        val out = File(ctx.filesDir, "update/kori-update.apk").also { it.parentFile?.mkdirs() }
        ZipInputStream(FileInputStream(zip)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (e.name.endsWith(".apk")) { FileOutputStream(out).use { z.copyTo(it) }; break }
                e = z.nextEntry
            }
        }
        zip.delete()
        if (!out.exists() || out.length() == 0L) throw IllegalStateException("No APK found in the build.")
        out
    }

    suspend fun merge(branch: String): String = withContext(Dispatchers.IO) {
        val g = gh()
        val base = defaultBranch(g)
        val (c, t) = g.call("POST", "/merges", JSONObject().put("base", base).put("head", branch).put("commit_message", "Merge $branch (Kori)"))
        when (c) {
            201 -> "Merged into $base."
            204 -> "Nothing to merge."
            else -> "Merge failed: HTTP $c ${t.take(160)}"
        }
    }
}
