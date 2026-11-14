package com.storm.shijuan

import com.storm.shijuan.data.Net
import com.storm.shijuan.data.OfficialProxy
import com.storm.shijuan.data.OfficialVlessProxyServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OfficialProxyAndVpnSplitTest {

    @Before
    fun setUp() {
        OfficialVlessProxyServer.stop()
    }

    @After
    fun tearDown() {
        OfficialVlessProxyServer.stop()
    }

    @Test
    fun testOfficialProxyConstants() {
        assertEquals("pw58ucaw.pages.dev", OfficialProxy.HOST)
        assertEquals("/epabbfgoah", OfficialProxy.PATH)
        assertEquals("3d5fc5eb-a03c-4e93-84ee-7a59ab6f0293", OfficialProxy.UUID_STR)
        assertEquals(16, OfficialProxy.UUID_BYTES.size)
        assertTrue(OfficialProxy.OPTIMIZED_IPS.isNotEmpty())
        assertTrue(OfficialProxy.OPTIMIZED_IPS.contains("104.17.64.1"))
    }

    @Test
    fun testNetProxyAndOfficialProxyState() {
        // 初始状态
        Net.setOfficialProxyActive(false)
        Net.setProxy(Net.ProxyCfg(enabled = false))
        assertFalse(Net.isOfficialProxyActive())
        assertFalse(Net.proxy().enabled)

        // 切换官方代理
        Net.setOfficialProxyActive(true)
        assertTrue(Net.isOfficialProxyActive())

        // 切换回自定义代理
        Net.setOfficialProxyActive(false)
        val custom = Net.ProxyCfg(enabled = true, host = "127.0.0.1", port = 7890)
        Net.setProxy(custom)
        assertFalse(Net.isOfficialProxyActive())
        assertTrue(Net.proxy().enabled)
        assertEquals("127.0.0.1", Net.proxy().host)
        assertEquals(7890, Net.proxy().port)
    }

    @Test
    fun testOfficialVlessProxyServerLifecycle() {
        val port = OfficialVlessProxyServer.ensureStarted()
        assertTrue("Port should be assigned", port > 0)
        assertEquals(port, OfficialVlessProxyServer.localPort)

        // Idempotent start
        val port2 = OfficialVlessProxyServer.ensureStarted()
        assertEquals(port, port2)

        // Stop
        OfficialVlessProxyServer.stop()
        assertEquals(0, OfficialVlessProxyServer.localPort)
    }
}
