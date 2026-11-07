package com.storm.shijuan

import com.storm.shijuan.data.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.*
import org.junit.Test

class SecurityAndCloudflareChannelTest {

    @Test
    fun testCloudflareChallengeDetection() {
        // Cloudflare 403 Challenge Page
        val cfHtml = """
            <!DOCTYPE html>
            <html>
            <head><title>Just a moment...</title></head>
            <body>
                <div class="cf-browser-verification cf-im-under-attack">
                    <h1>Just a moment...</h1>
                    <p>Checking your browser before accessing wenku8.cc.</p>
                </div>
            </body>
            </html>
        """.trimIndent()
        assertTrue("Cloudflare 403 should be detected as challenge", Net.isCloudflareChallenge(403, cfHtml))
        assertTrue("Cloudflare 503 should be detected as challenge", Net.isCloudflareChallenge(503, cfHtml))

        // Normal 200 response
        val normalHtml = "<html><head><title>轻小说文库</title></head><body>欢迎访问</body></html>"
        assertFalse("200 OK should not be challenge", Net.isCloudflareChallenge(200, normalHtml))

        // Plain 404
        assertFalse("404 Not Found should not be challenge", Net.isCloudflareChallenge(404, "404 Not Found"))
    }

    @Test
    fun testCookieSyncAndJarInjection() {
        val testHost = "wenku8.cc"
        val testCookieHeader = "cf_clearance=mock_clearance_12345; __cf_bm=mock_bm_67890; jieqiUserInfo=test"

        Net.syncCookies(testHost, testCookieHeader)

        val u = "https://www.wenku8.cc/index.php".toHttpUrlOrNull()!!
        val cookies = Net.cookieJar.loadForRequest(u)

        assertTrue("Cookies should contain cf_clearance", cookies.any { it.name == "cf_clearance" && it.value == "mock_clearance_12345" })
        assertTrue("Cookies should contain __cf_bm", cookies.any { it.name == "__cf_bm" && it.value == "mock_bm_67890" })
        assertTrue("Cookies should contain jieqiUserInfo", cookies.any { it.name == "jieqiUserInfo" })

        val headerStr = Net.getCookiesHeader("www.wenku8.cc")
        assertTrue("Cookie header should format correctly", headerStr.contains("cf_clearance=mock_clearance_12345"))
    }

    @Test
    fun testClassifyStatus() {
        assertEquals(
            SourceStatus.ACCESS_RESTRICTED,
            BookRepo.classifyStatus(false, 403, "被站点拦截 403", 0)
        )
        assertEquals(
            SourceStatus.ACCESS_RESTRICTED,
            BookRepo.classifyStatus(false, 403, "访问受限（触发 Cloudflare 浏览器安全质询，需网页验证）", 0)
        )
        assertEquals(
            SourceStatus.NEEDS_LOGIN,
            BookRepo.classifyStatus(false, 401, "要登录 401", 0)
        )
        assertEquals(
            SourceStatus.NEEDS_LOGIN,
            BookRepo.classifyStatus(false, 200, "这章是源站的付费/VIP 章节，得先在源站登录购买才能看", 0)
        )
        assertEquals(
            SourceStatus.SITE_ERROR,
            BookRepo.classifyStatus(false, 502, "站点自身出错 502", 0)
        )
        assertEquals(
            SourceStatus.SITE_ERROR,
            BookRepo.classifyStatus(false, 0, "连接被拒绝", 0)
        )
        assertEquals(
            SourceStatus.RULE_INVALID,
            BookRepo.classifyStatus(false, 200, "站点改版，列表 12 项但取不到书名", 0)
        )
        assertEquals(
            SourceStatus.NORMAL,
            BookRepo.classifyStatus(true, 200, "", 5)
        )
    }

    @Test
    fun testDeepHealthResultDestructuring() {
        val res = DeepHealthResult(
            ok = false,
            detail = "访问受限（触发 Cloudflare 浏览器安全质询，需网页验证）",
            status = SourceStatus.ACCESS_RESTRICTED,
            httpCode = 403,
        )
        val (ok, detail, status, code) = res
        assertFalse(ok)
        assertEquals("访问受限（触发 Cloudflare 浏览器安全质询，需网页验证）", detail)
        assertEquals(SourceStatus.ACCESS_RESTRICTED, status)
        assertEquals(403, code)
    }
}
