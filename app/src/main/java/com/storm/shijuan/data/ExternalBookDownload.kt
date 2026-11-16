package com.storm.shijuan.data

import android.content.Context
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Downloads an untrusted public URL into private cache. The caller owns the returned file. */
object ExternalBookDownload {
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val MAX_XML = 2 * 1024 * 1024
    private const val MAX_ZIP_ENTRY_BYTES = 64L * 1024 * 1024
    private const val MAX_ZIP_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_ZIP_ENTRIES = 10_000
    private const val MAX_REDIRECTS = 5

    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !isPublic(it) }) {
                throw IOException("Destination is not a public address")
            }
            return addresses
        }
    }
    private val client = OkHttpClient.Builder()
        .dns(publicDns).proxy(Proxy.NO_PROXY)
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    suspend fun download(context: Context, url: String): File = suspendCancellableCoroutine { continuation ->
        val activeCall = AtomicReference<Call?>()
        continuation.invokeOnCancellation { activeCall.get()?.cancel() }
        client.dispatcher.executorService.execute {
            var file: File? = null
            try {
                var current = checkedUrl(url.toHttpUrlOrNull() ?: throw IOException("Invalid HTTP URL"))
                var redirects = 0
                while (true) {
                    if (!continuation.isActive) throw IOException("Cancelled")
                    val call = client.newCall(Request.Builder().url(current).header("Accept-Encoding", "identity").get().build())
                    activeCall.set(call)
                    if (!continuation.isActive) { call.cancel(); throw IOException("Cancelled") }
                    var next: HttpUrl? = null
                    call.execute().use { response ->
                        if (response.code in listOf(301, 302, 303, 307, 308)) {
                            if (++redirects > MAX_REDIRECTS) throw IOException("Too many redirects")
                            val location = response.header("Location") ?: throw IOException("Redirect without Location")
                            next = checkedUrl(current.resolve(location) ?: throw IOException("Invalid redirect"))
                            if (current.isHttps && !next!!.isHttps) throw IOException("HTTPS downgrade forbidden")
                        } else {
                            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                            val body = response.body ?: throw IOException("Empty response")
                            if (body.contentLength() > MAX_BYTES) throw IOException("Book exceeds 32 MiB")
                            val target = File.createTempFile("external-book-", ".epub", context.cacheDir)
                            file = target
                            body.byteStream().use { input ->
                                target.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(16 * 1024)
                                    var total = 0L
                                    while (true) {
                                        if (!continuation.isActive) throw IOException("Cancelled")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        total += count
                                        if (total > MAX_BYTES) throw IOException("Book exceeds 32 MiB")
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                            validateEpub(target) { continuation.isActive }
                        }
                    }
                    if (next != null) { current = next!!; continue }
                    val result = file ?: throw IOException("No book downloaded")
                    continuation.resume(result) { result.delete() }
                    file = null
                    break
                }
            } catch (e: Exception) {
                file?.delete()
                if (continuation.isActive) continuation.resumeWithException(e)
            } finally {
                activeCall.set(null)
            }
        }
    }

    private fun checkedUrl(url: HttpUrl): HttpUrl {
        if ((url.scheme != "http" && url.scheme != "https") || url.username.isNotEmpty() || url.password.isNotEmpty())
            throw IOException("Only public HTTP(S) URLs without credentials are allowed")
        // OkHttp bypasses Dns for IP literals, so refuse them rather than bypassing our address policy.
        if (url.host.contains(':') || url.host.matches(Regex("[0-9]+(\\.[0-9]+){3}")))
            throw IOException("IP literal destinations are forbidden")
        return url
    }

    internal fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address.map { it.toInt() and 255 }
        if (address is Inet4Address) {
            val a = b[0]; val c = b[1]; val d = b[2]
            return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && c in 64..127) || (a == 169 && c == 254) ||
                (a == 172 && c in 16..31) || (a == 192 && c == 168) ||
                (a == 192 && c == 0 && (d == 0 || d == 2)) ||
                (a == 192 && c == 88 && d == 99) || (a == 198 && c in 18..19) ||
                (a == 198 && c == 51 && d == 100) || (a == 203 && c == 0 && d == 113))
        }
        // Only ordinary global unicast IPv6, excluding documentation, translation and special ranges.
        if (address is Inet6Address) {
            if (b[0] !in 0x20..0x3f) return false
            if (b[0] == 0x20 && b[1] == 0x01 && (b[2] < 0x20 || (b[2] == 0x0d && b[3] == 0xb8))) return false
            if (b[0] == 0x20 && b[1] == 0x02) return false // 6to4 tunnels
            return true
        }
        return false
    }

    internal fun validateEpub(file: File, active: () -> Boolean = { true }) {
        // EPUB requires its first ZIP member to be an uncompressed mimetype entry.
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            val first = zip.nextEntry ?: throw IOException("Not an EPUB")
            if (first.name != "mimetype" || first.method != java.util.zip.ZipEntry.STORED ||
                first.extra?.isNotEmpty() == true || readLimited(zip, 64, active).toString(Charsets.US_ASCII) != "application/epub+zip")
                throw IOException("Invalid EPUB signature")
        }
        ZipFile(file).use { zip ->
            val names = HashSet<String>()
            val entries = zip.entries()
            var count = 0
            var declaredTotal = 0L
            var totalUncompressed = 0L
            while (entries.hasMoreElements()) {
                if (!active()) throw IOException("Cancelled")
                val entry = entries.nextElement()
                val declaredSize = entry.size
                if (++count > MAX_ZIP_ENTRIES || !names.add(entry.name) || entry.name.startsWith('/') ||
                    entry.name.split('/').any { it == ".." || it == "." } || entry.name.contains('\\') ||
                    declaredSize > MAX_ZIP_ENTRY_BYTES ||
                    (declaredSize >= 0 && declaredTotal > MAX_ZIP_TOTAL_BYTES - declaredSize))
                    throw IOException("Invalid EPUB entries")
                if (declaredSize >= 0) declaredTotal += declaredSize
                var entryBytes = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        if (!active()) throw IOException("Cancelled")
                        val n = input.read(buffer)
                        if (n < 0) break
                        entryBytes += n
                        totalUncompressed += n
                        if (entryBytes > MAX_ZIP_ENTRY_BYTES || totalUncompressed > MAX_ZIP_TOTAL_BYTES)
                            throw IOException("EPUB decompressed data exceeds limit")
                    }
                }
            }
            val container = zip.getEntry("META-INF/container.xml") ?: throw IOException("Missing EPUB container")
            val containerBytes = zip.getInputStream(container).use { readLimited(it, MAX_XML, active) }
            var rootPath: String? = null
            parseXml(containerBytes) { parser ->
                if (parser.name == "rootfile" && parser.getAttributeValue(null, "media-type") == "application/oebps-package+xml")
                    rootPath = parser.getAttributeValue(null, "full-path")
            }
            val path = rootPath ?: throw IOException("Missing EPUB package")
            if (path.startsWith('/') || path.contains('\\') || path.split('/').any { it == "." || it == ".." }) throw IOException("Invalid package path")
            val packageEntry = zip.getEntry(path) ?: throw IOException("Missing EPUB package")
            val packageBytes = zip.getInputStream(packageEntry).use { readLimited(it, MAX_XML, active) }
            var packageFound = false; var manifest = false; var spine = false
            parseXml(packageBytes) { parser ->
                when (parser.name) {
                    "package" -> if (parser.namespace == "http://www.idpf.org/2007/opf") packageFound = true
                    "manifest" -> manifest = true
                    "spine" -> spine = true
                }
            }
            if (!packageFound || !manifest || !spine) throw IOException("Invalid EPUB package")
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Int, active: () -> Boolean): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            if (!active()) throw IOException("Cancelled")
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size().toLong() + n > limit) throw IOException("EPUB entry too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun parseXml(bytes: ByteArray, visit: (XmlPullParser) -> Unit) {
        // Reject DTDs rather than allowing entity expansion or external entities.
        val text = bytes.toString(Charsets.UTF_8)
        if (text.contains("<!DOCTYPE", ignoreCase = true) || text.contains("<!ENTITY", ignoreCase = true) || text.indexOf('\u0000') >= 0)
            throw IOException("Unsafe EPUB XML")
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
        parser.setInput(text.reader())
        while (true) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> visit(parser)
                XmlPullParser.END_DOCUMENT -> break
            }
        }
    }
}
