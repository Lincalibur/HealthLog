package com.liamo.f1.sync

import androidx.room.withTransaction
import com.liamo.f1.data.Store
import java.time.LocalDate

data class SyncResult(val message: String, val pullRequestUrl: String? = null, val warning: String? = null)

/**
 * One sync, as in the plan:
 * 1. Rebuild every day file touched by unexported rows, from all rows of that day.
 * 2. Make sure branch `phone-sync` exists (fresh from main when no PR is open).
 * 3. One tree + one commit holding all files, then move the branch.
 * 4. Make sure a pull request into main exists, with auto-merge on.
 * 5. Only then mark the rows exported, in one transaction. Any failure before this
 *    leaves everything unexported, and the next run retries the same data.
 */
class GitHubSync(
    private val store: Store,
    private val gitHub: GitHubClient,
    private val exporter: JsonExporter = JsonExporter(),
    private val branch: String = BRANCH,
    private val base: String = "main",
) {
    suspend fun run(): SyncResult {
        val db = store.db
        val readings = db.readings().unexported()
        val attempts = db.attempts().unexported()
        if (readings.isEmpty() && attempts.isEmpty()) return SyncResult("Nothing new to sync.")

        val days = (readings.map { exporter.dayOf(it.takenAt) } + attempts.map { exporter.dayOf(it.startedAt) })
            .toSortedSet()
        val firmware = store.state().firmware

        // Branch: reuse it while its PR is open; otherwise start again from main, so a
        // merged (or stale) branch never causes conflicts.
        var pull = gitHub.openPull(branch)
        val head: String
        if (pull != null) {
            head = gitHub.branchSha(branch) ?: error("branch $branch vanished")
        } else {
            head = gitHub.branchSha(base) ?: error("base branch $base not found")
            if (gitHub.branchSha(branch) == null) gitHub.createBranch(branch, head) else gitHub.moveBranch(branch, head, force = true)
        }

        val files = LinkedHashMap<String, String>()
        for (day in days) {
            val (from, to) = exporter.bounds(day)
            val dayReadings = db.readings().between(from, to)
            if (dayReadings.isNotEmpty()) {
                val path = exporter.readingsPath(day)
                files[path] = exporter.readingsFile(day, firmware, dayReadings, gitHub.fileText(path, branch))
            }
            val dayAttempts = db.attempts().between(from, to)
            if (dayAttempts.isNotEmpty()) {
                val path = exporter.attemptsPath(day)
                files[path] = exporter.attemptsFile(day, dayAttempts, gitHub.fileText(path, branch))
            }
        }

        val baseTree = gitHub.treeOfCommit(head)
        val tree = gitHub.createTree(baseTree, files)
        val changed = tree != baseTree
        if (changed) {
            val commit = gitHub.createCommit(commitMessage(days), tree, head)
            gitHub.moveBranch(branch, commit)
        }

        var warning: String? = null
        if (pull == null && changed) {
            pull = gitHub.createPull(
                title = "Watch data ${days.last()}",
                branch = branch,
                base = base,
                body = "Readings and attempts synced from the phone app. Merges itself once the `validate` check passes.",
            )
        } else if (pull != null && changed) {
            runCatching { gitHub.updatePullTitle(pull.number, "Watch data ${days.first()}…${days.last()}") }
        }
        if (pull != null) {
            // Not fatal: the data is safely in the PR even if auto-merge can't be switched on
            // (e.g. "Allow auto-merge" is off in the repo settings). Merge it by hand then.
            warning = runCatching { gitHub.enableAutoMerge(pull.nodeId) }.exceptionOrNull()?.message
                ?.takeUnless { it.contains("already", ignoreCase = true) }
        }

        db.withTransaction {
            readings.map { it.id }.chunked(500).forEach { db.readings().markExported(it) }
            attempts.map { it.id }.chunked(500).forEach { db.attempts().markExported(it) }
        }
        store.updateState {
            it.copy(lastExport = System.currentTimeMillis(), lastPullRequestUrl = pull?.htmlUrl ?: it.lastPullRequestUrl, lastExportError = warning)
        }
        store.pruneExported()

        val what = "${readings.size} readings, ${attempts.size} attempts over ${days.size} day(s)"
        return when {
            pull != null -> SyncResult("Synced $what.", pull.htmlUrl, warning)
            else -> SyncResult("Already up to date on GitHub ($what).")
        }
    }

    private fun commitMessage(days: Set<LocalDate>) =
        if (days.size == 1) "Watch data ${days.first()}" else "Watch data ${days.first()} to ${days.last()}"

    companion object {
        const val BRANCH = "phone-sync"
    }
}
