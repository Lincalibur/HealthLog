package com.liamo.f1.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GitHubException(val code: Int, message: String) : Exception(message)

data class PullRef(val number: Int, val nodeId: String, val htmlUrl: String)

/**
 * The handful of GitHub REST calls the sync needs, plus one GraphQL mutation (REST
 * has no endpoint for auto-merge). Uses a fine-grained token limited to one repo with
 * Contents and Pull requests read/write. The token only ever goes into the header.
 */
class GitHubClient(
    private val owner: String,
    private val repo: String,
    private val token: String,
    private val baseUrl: String = "https://api.github.com",
    private val http: OkHttpClient = defaultClient,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val repoPath = "repos/$owner/$repo"

    /** SHA a branch points at, or null if the branch doesn't exist. */
    suspend fun branchSha(branch: String): String? =
        call("GET", "$repoPath/git/ref/heads/$branch", allow404 = true)?.obj("object")?.str("sha")

    suspend fun createBranch(branch: String, sha: String) {
        call("POST", "$repoPath/git/refs", buildJsonObject {
            put("ref", "refs/heads/$branch")
            put("sha", sha)
        })
    }

    suspend fun moveBranch(branch: String, sha: String, force: Boolean = false) {
        call("PATCH", "$repoPath/git/refs/heads/$branch", buildJsonObject {
            put("sha", sha)
            put("force", force)
        })
    }

    suspend fun treeOfCommit(commitSha: String): String =
        call("GET", "$repoPath/git/commits/$commitSha")!!.obj("tree")!!.str("sha")!!

    /** Raw text of a file on [ref], or null if it doesn't exist there. */
    suspend fun fileText(path: String, ref: String): String? = withContext(Dispatchers.IO) {
        val url = url("$repoPath/contents/$path").newBuilder().addQueryParameter("ref", ref).build()
        val req = request(url.toString()).header("Accept", "application/vnd.github.raw+json").get().build()
        http.newCall(req).execute().use { res ->
            when {
                res.code == 404 -> null
                !res.isSuccessful -> throw error(res.code, res.body?.string())
                else -> res.body?.string()
            }
        }
    }

    /** One tree on top of [baseTree] with every file inline (mode 100644). */
    suspend fun createTree(baseTree: String, files: Map<String, String>): String =
        call("POST", "$repoPath/git/trees", buildJsonObject {
            put("base_tree", baseTree)
            putJsonArray("tree") {
                for ((path, content) in files) add(buildJsonObject {
                    put("path", path)
                    put("mode", "100644")
                    put("type", "blob")
                    put("content", content)
                })
            }
        })!!.str("sha")!!

    suspend fun createCommit(message: String, tree: String, parent: String): String =
        call("POST", "$repoPath/git/commits", buildJsonObject {
            put("message", message)
            put("tree", tree)
            put("parents", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(parent)) })
        })!!.str("sha")!!

    suspend fun openPull(branch: String): PullRef? {
        val path = "$repoPath/pulls"
        val url = url(path).newBuilder()
            .addQueryParameter("head", "$owner:$branch")
            .addQueryParameter("state", "open")
            .build()
        val body = send(request(url.toString()).get().build(), allow404 = false) ?: return null
        return (body as JsonArray).firstOrNull()?.jsonObject?.toPull()
    }

    suspend fun createPull(title: String, branch: String, base: String, body: String): PullRef =
        call("POST", "$repoPath/pulls", buildJsonObject {
            put("title", title)
            put("head", branch)
            put("base", base)
            put("body", body)
        })!!.toPull()

    suspend fun updatePullTitle(number: Int, title: String) {
        call("PATCH", "$repoPath/pulls/$number", buildJsonObject { put("title", title) })
    }

    /** Squash auto-merge, so the PR merges itself once the `validate` check passes. */
    suspend fun enableAutoMerge(pullNodeId: String) {
        val res = call("POST", "graphql", buildJsonObject {
            put(
                "query",
                "mutation(\$id: ID!) { enablePullRequestAutoMerge(input: {pullRequestId: \$id, mergeMethod: SQUASH}) { clientMutationId } }",
            )
            putJsonObject("variables") { put("id", pullNodeId) }
        })
        val errors = res?.jsonObject?.get("errors")?.jsonArray
        if (!errors.isNullOrEmpty()) {
            throw GitHubException(200, "auto-merge: " + errors.joinToString { it.jsonObject.str("message") ?: "?" })
        }
    }

    // ---- plumbing ---------------------------------------------------------------------

    private suspend fun call(method: String, path: String, body: JsonObject? = null, allow404: Boolean = false): JsonObject? {
        val rb = body?.toString()?.toRequestBody(JSON)
        val req = request(url(path).toString()).method(method, rb).build()
        return send(req, allow404)?.jsonObject
    }

    private suspend fun send(req: Request, allow404: Boolean): JsonElement? = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            when {
                res.code == 404 && allow404 -> null
                !res.isSuccessful -> throw error(res.code, text)
                text.isBlank() -> JsonObject(emptyMap())
                else -> json.parseToJsonElement(text)
            }
        }
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "healthlog-android")

    private fun url(path: String) = "${baseUrl.trimEnd('/')}/$path".toHttpUrl()

    private fun error(code: Int, body: String?): GitHubException {
        val msg = runCatching { json.parseToJsonElement(body ?: "").jsonObject.str("message") }.getOrNull()
        return when (code) {
            401 -> GitHubException(code, "GitHub rejected the token: it may have expired. Paste a new one in Settings.")
            403 -> GitHubException(code, "GitHub refused (403): check the token has Contents and Pull requests write access. $msg")
            404 -> GitHubException(code, "Not found (404): check owner/repo, and that the token can see that repo.")
            else -> GitHubException(code, "GitHub error $code: ${msg ?: body?.take(200)}")
        }
    }

    private fun JsonObject.toPull() = PullRef(
        number = get("number")!!.jsonPrimitive.content.toInt(),
        nodeId = str("node_id")!!,
        htmlUrl = str("html_url")!!,
    )

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
private fun JsonObject.str(key: String): String? = (get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
