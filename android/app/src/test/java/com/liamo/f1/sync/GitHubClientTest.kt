package com.liamo.f1.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class GitHubClientTest {
    private val server = MockWebServer()
    private lateinit var client: GitHubClient

    @Before fun setUp() {
        server.start()
        client = GitHubClient("me", "watch", "secret-token", baseUrl = server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    private fun reply(code: Int = 200, body: String = "{}") = server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test fun missingBranchIsNull() = runTest {
        reply(404, """{"message":"Not Found"}""")
        assertNull(client.branchSha("phone-sync"))
        val req = server.takeRequest()
        assertEquals("/repos/me/watch/git/ref/heads/phone-sync", req.path)
        assertEquals("Bearer secret-token", req.getHeader("Authorization"))
    }

    @Test fun branchSha() = runTest {
        reply(body = """{"ref":"refs/heads/main","object":{"sha":"abc123","type":"commit"}}""")
        assertEquals("abc123", client.branchSha("main"))
    }

    @Test fun createTreeSendsInlineFiles() = runTest {
        reply(201, """{"sha":"tree2"}""")
        val sha = client.createTree("tree1", mapOf("data/readings/2026/10/2026-10-14.json" to "{}\n"))
        assertEquals("tree2", sha)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("tree1", body["base_tree"]!!.jsonPrimitive.content)
        val entry = body["tree"]!!.jsonArray.single().jsonObject
        assertEquals("100644", entry["mode"]!!.jsonPrimitive.content)
        assertEquals("{}\n", entry["content"]!!.jsonPrimitive.content)
    }

    @Test fun openPullQueriesHeadBranch() = runTest {
        reply(body = """[{"number":7,"node_id":"PR_x","html_url":"https://github.com/me/watch/pull/7"}]""")
        val pr = client.openPull("phone-sync")
        assertEquals(PullRef(7, "PR_x", "https://github.com/me/watch/pull/7"), pr)
        assertTrue(server.takeRequest().path!!.contains("head=me%3Aphone-sync"))
    }

    @Test fun noOpenPull() = runTest {
        reply(body = "[]")
        assertNull(client.openPull("phone-sync"))
    }

    @Test fun fileTextMissingIsNull() = runTest {
        reply(404)
        assertNull(client.fileText("data/readings/2026/10/2026-10-14.json", "phone-sync"))
    }

    @Test fun expiredTokenHasAClearMessage() = runTest {
        reply(401, """{"message":"Bad credentials"}""")
        try {
            client.branchSha("main")
            fail("expected an exception")
        } catch (e: GitHubException) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("expired"))
            assertTrue("token must never appear in errors", !e.message!!.contains("secret-token"))
        }
    }

    @Test fun autoMergeGraphQlErrorsThrow() = runTest {
        reply(body = """{"data":null,"errors":[{"message":"Auto merge is not allowed for this repository"}]}""")
        try {
            client.enableAutoMerge("PR_x")
            fail("expected an exception")
        } catch (e: GitHubException) {
            assertTrue(e.message!!.contains("not allowed"))
        }
        assertEquals("/graphql", server.takeRequest().path)
    }
}
