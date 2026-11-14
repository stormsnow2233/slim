package com.storm.shijuan

import com.storm.shijuan.data.ComicRepo
import com.storm.shijuan.data.Net
import org.junit.Test

class TestHomeAll {
    @Test
    fun testAllSourcesHome() {
        println("ComicRepo sources: ${ComicRepo.sources.map { it.id }}")
        
        // Debug Manhuagui
        val r = Net.get("https://m.manhuagui.com/update/", fast = true)
        println("Manhuagui /update/ ok=${r.ok}, code=${r.code}, len=${r.body.length}, error=${r.error}")
        if (r.body.isNotEmpty()) {
            println("Manhuagui body snippet: " + r.body.take(500))
        }

        var successCount = 0
        for (src in ComicRepo.sources) {
            val t0 = System.currentTimeMillis()
            try {
                val list = src.home()
                val dt = System.currentTimeMillis() - t0
                println("Source ${src.id} (${src.name}): count=${list.size}, time=${dt}ms")
                if (list.isNotEmpty()) {
                    successCount++
                    println("  First comic: ${list[0].name}, cover=${list[0].cover}, url=${list[0].url}")
                }
            } catch (e: Throwable) {
                val dt = System.currentTimeMillis() - t0
                println("Source ${src.id} (${src.name}) FAILED: ${e.message}, time=${dt}ms")
            }
        }
        org.junit.Assert.assertTrue("At least one comic source should return comics", successCount > 0)
    }
}


