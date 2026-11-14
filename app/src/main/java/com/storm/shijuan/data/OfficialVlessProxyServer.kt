package com.storm.shijuan.data

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket

/**
 * 官方云端优选加速通道（内置轻量级本地 SOCKS5 转 VLESS-over-WebSocket 隧道）
 * 100% 纯原生 Kotlin / Java Socket 实现，零三方底层依赖，内存占用极低。
 */
object OfficialVlessProxyServer {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    @Volatile
    var localPort: Int = 0
        private set

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "vless-socks5-worker").apply { isDaemon = true }
    }

    private val random = SecureRandom()

    @Synchronized
    fun ensureStarted(): Int {
        val existing = serverSocket
        if (running.get() && existing != null && !existing.isClosed) {
            return localPort
        }
        stop()

        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        localPort = ss.localPort
        running.set(true)

        executor.execute {
            while (running.get() && !ss.isClosed) {
                try {
                    val client = ss.accept()
                    client.tcpNoDelay = true
                    client.soTimeout = 30_000
                    executor.execute { handleSocksClient(client) }
                } catch (_: Exception) {
                    if (ss.isClosed) break
                }
            }
        }
        return localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        localPort = 0
    }

    private fun handleSocksClient(client: Socket) {
        var remoteSocket: SSLSocket? = null
        try {
            val cin = client.getInputStream()
            val cout = client.getOutputStream()

            // 1. SOCKS5 握手认证
            val ver = cin.read()
            if (ver != 5) {
                client.close()
                return
            }
            val nMethods = cin.read()
            if (nMethods <= 0) {
                client.close()
                return
            }
            val methods = ByteArray(nMethods)
            readFully(cin, methods)
            // 响应无密码认证
            cout.write(byteArrayOf(0x05, 0x00))
            cout.flush()

            // 2. SOCKS5 请求解析
            val ver2 = cin.read()
            val cmd = cin.read()
            val rsv = cin.read()
            val atyp = cin.read()
            if (ver2 != 5 || cmd != 1) { // 仅支持 CONNECT (0x01)
                cout.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                cout.flush()
                client.close()
                return
            }

            val targetHost: String
            val vlessAddrBytes: ByteArray

            when (atyp) {
                1 -> { // IPv4
                    val ipBytes = ByteArray(4)
                    readFully(cin, ipBytes)
                    targetHost = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                    vlessAddrBytes = byteArrayOf(1) + ipBytes
                }
                3 -> { // 域名
                    val dlen = cin.read()
                    if (dlen <= 0) {
                        client.close()
                        return
                    }
                    val domainBytes = ByteArray(dlen)
                    readFully(cin, domainBytes)
                    targetHost = String(domainBytes, Charsets.UTF_8)
                    vlessAddrBytes = byteArrayOf(2, dlen.toByte()) + domainBytes
                }
                4 -> { // IPv6
                    val ipBytes = ByteArray(16)
                    readFully(cin, ipBytes)
                    targetHost = InetAddress.getByAddress(ipBytes).hostAddress ?: ""
                    vlessAddrBytes = byteArrayOf(3) + ipBytes
                }
                else -> {
                    client.close()
                    return
                }
            }

            val portH = cin.read()
            val portL = cin.read()
            if (portH < 0 || portL < 0) {
                client.close()
                return
            }
            val targetPort = (portH shl 8) or portL

            // 3. 连接 Cloudflare Pages 优选节点
            val cfSocket = connectToOptimizedCf()
            if (cfSocket == null) {
                cout.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0)) // Host unreachable
                cout.flush()
                client.close()
                return
            }
            remoteSocket = cfSocket

            val rin = cfSocket.inputStream
            val rout = cfSocket.outputStream

            // 4. WebSocket Upgrade 握手
            val wsKey = "dGhlIHNhbXBsZSBub25jZQ=="
            val wsReq = "GET ${OfficialProxy.PATH} HTTP/1.1\r\n" +
                "Host: ${OfficialProxy.HOST}\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $wsKey\r\n" +
                "Sec-WebSocket-Version: 13\r\n\r\n"
            rout.write(wsReq.toByteArray(Charsets.US_ASCII))
            rout.flush()

            val headerResp = readUntilDoubleCrlf(rin)
            if (!headerResp.contains("101")) {
                cfSocket.close()
                client.close()
                return
            }

            // 5. 发送 VLESS 初始握手包（封装进 WebSocket 二进制掩码帧中）
            val vh = ByteArray(1 + 16 + 1 + 1 + 2 + vlessAddrBytes.size)
            vh[0] = 0 // version
            System.arraycopy(OfficialProxy.UUID_BYTES, 0, vh, 1, 16)
            vh[17] = 0 // addon len
            vh[18] = 1 // cmd: 1 (TCP)
            vh[19] = (targetPort shr 8).toByte()
            vh[20] = (targetPort and 0xFF).toByte()
            System.arraycopy(vlessAddrBytes, 0, vh, 21, vlessAddrBytes.size)

            sendWsBinaryFrame(rout, vh)

            // 6. 读取 VLESS ACK（4 字节：0x82, 0x02, 0x00, 0x00）
            val ack = ByteArray(4)
            readFully(rin, ack)
            if (ack[0] != 0x82.toByte()) {
                cfSocket.close()
                client.close()
                return
            }

            // 7. 回复 SOCKS5 成功响应
            cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            cout.flush()

            // 8. 双向数据转发
            val t1 = Thread {
                val buf = ByteArray(16384)
                try {
                    while (true) {
                        val n = cin.read(buf)
                        if (n <= 0) break
                        sendWsBinaryFrame(rout, buf, 0, n)
                    }
                } catch (_: Exception) {}
                runCatching { cfSocket.close() }
            }

            val t2 = Thread {
                try {
                    val frameHeader = ByteArray(2)
                    while (true) {
                        readFully(rin, frameHeader)
                        val b0 = frameHeader[0].toInt() and 0xFF
                        val b1 = frameHeader[1].toInt() and 0xFF
                        val opcode = b0 and 0x0F
                        if (opcode == 8) break // WS close

                        val isMasked = (b1 and 0x80) != 0
                        var pLen = (b1 and 0x7F).toLong()
                        if (pLen == 126L) {
                            val ext = ByteArray(2)
                            readFully(rin, ext)
                            pLen = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
                        } else if (pLen == 127L) {
                            val ext = ByteArray(8)
                            readFully(rin, ext)
                            var acc = 0L
                            for (b in ext) acc = (acc shl 8) or (b.toLong() and 0xFF)
                            pLen = acc
                        }

                        val maskKey = if (isMasked) {
                            val m = ByteArray(4)
                            readFully(rin, m)
                            m
                        } else null

                        if (pLen > 0) {
                            var remain = pLen
                            val chunk = ByteArray(minOf(remain, 16384L).toInt())
                            var maskIdx = 0
                            while (remain > 0) {
                                val toRead = minOf(remain, chunk.size.toLong()).toInt()
                                readFully(rin, chunk, 0, toRead)
                                if (maskKey != null) {
                                    for (i in 0 until toRead) {
                                        chunk[i] = (chunk[i].toInt() xor maskKey[maskIdx % 4].toInt()).toByte()
                                        maskIdx++
                                    }
                                }
                                cout.write(chunk, 0, toRead)
                                remain -= toRead
                            }
                            cout.flush()
                        }
                    }
                } catch (_: Exception) {}
                runCatching { client.close() }
            }

            t1.isDaemon = true
            t2.isDaemon = true
            t1.start()
            t2.start()
            t1.join()
            t2.join()
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
            runCatching { remoteSocket?.close() }
        }
    }

    private fun connectToOptimizedCf(): SSLSocket? {
        val ips = OfficialProxy.OPTIMIZED_IPS.shuffled()
        val sslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        for (ip in ips.take(3)) {
            try {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.soTimeout = 15_000
                raw.connect(InetSocketAddress(ip, 443), 4_000)

                val ssl = sslFactory.createSocket(raw, OfficialProxy.HOST, 443, true) as SSLSocket
                val params = ssl.sslParameters
                params.serverNames = listOf(SNIHostName(OfficialProxy.HOST))
                ssl.sslParameters = params
                ssl.startHandshake()
                return ssl
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun sendWsBinaryFrame(out: OutputStream, data: ByteArray, offset: Int = 0, len: Int = data.size) {
        val mask = ByteArray(4)
        random.nextBytes(mask)

        val header = ByteBuffer.allocate(14)
        header.put(0x82.toByte()) // FIN=1, binary=2

        if (len < 126) {
            header.put((0x80 or len).toByte())
        } else if (len <= 65535) {
            header.put((0x80 or 126).toByte())
            header.putShort(len.toShort())
        } else {
            header.put((0x80 or 127).toByte())
            header.putLong(len.toLong())
        }
        header.put(mask)
        out.write(header.array(), 0, header.position())

        val masked = ByteArray(len)
        for (i in 0 until len) {
            masked[i] = (data[offset + i].toInt() xor mask[i % 4].toInt()).toByte()
        }
        out.write(masked)
        out.flush()
    }

    private fun readUntilDoubleCrlf(input: InputStream): String {
        val baos = java.io.ByteArrayOutputStream()
        var match = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            baos.write(b)
            if ((match == 0 || match == 2) && b == '\r'.code) match++
            else if ((match == 1 || match == 3) && b == '\n'.code) match++
            else match = 0
            if (match == 4) break
        }
        return baos.toString("US-ASCII")
    }

    private fun readFully(input: InputStream, b: ByteArray, offset: Int = 0, len: Int = b.size) {
        var n = 0
        while (n < len) {
            val count = input.read(b, offset + n, len - n)
            if (count < 0) throw java.io.EOFException("Unexpected end of stream")
            n += count
        }
    }
}
