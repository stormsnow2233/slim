package com.storm.shijuan.data

import java.nio.ByteBuffer
import java.util.UUID

/**
 * 官方云端优选加速通道配置（内部加密保护，不向 UI 明文暴露）
 */
object OfficialProxy {
    const val SUB_URL = "https://un1yo9na.pages.dev/sub/3d5fc5eb-a03c-4e93-84ee-7a59ab6f0293?target=base64"
    const val HOST = "pw58ucaw.pages.dev"
    const val PATH = "/epabbfgoah"
    const val UUID_STR = "3d5fc5eb-a03c-4e93-84ee-7a59ab6f0293"

    val UUID_BYTES: ByteArray by lazy {
        val u = UUID.fromString(UUID_STR)
        val bb = ByteBuffer.allocate(16)
        bb.putLong(u.mostSignificantBits)
        bb.putLong(u.leastSignificantBits)
        bb.array()
    }

    val OPTIMIZED_IPS = listOf(
        "104.17.64.1",
        "104.17.0.1",
        "172.66.0.1",
        "104.21.64.1",
        "104.18.0.1",
        "104.19.0.1",
        "104.17.192.1",
        "104.19.64.1",
        "104.16.192.1",
        "104.17.32.1",
        "104.21.0.1",
        "104.22.0.1",
    )
}
