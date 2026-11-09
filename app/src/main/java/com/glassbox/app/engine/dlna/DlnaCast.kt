package com.glassbox.app.engine.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.glassbox.app.core.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class DlnaDevice(
    val udn: String,
    val name: String,
    val location: String,
    val manufacturer: String = "",
    val model: String = "",
    val avTransportControlUrl: String,
    val avTransportEventUrl: String? = null
)

/**
 * 轻量 DLNA/UPnP AVTransport 投屏：
 * - SSDP 发现 MediaRenderer
 * - 解析 device description 取 AVTransport controlURL
 * - SOAP：SetAVTransportURI / Play / Pause / Stop
 *
 * 不依赖 Cling，减少包体积；兼容多数电视/盒子渲染器。
 */
class DlnaCastManager(private val appContext: Context) {

    private val devices = ConcurrentHashMap<String, DlnaDevice>()

    fun snapshot(): List<DlnaDevice> = devices.values.sortedBy { it.name.lowercase() }

    suspend fun discover(timeoutMs: Long = 3_500L): List<DlnaDevice> = withContext(Dispatchers.IO) {
        val wifi = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("glassbox-dlna")?.apply {
            setReferenceCounted(true)
            acquire()
        }
        try {
            devices.clear()
            val socket = DatagramSocket().apply {
                reuseAddress = true
                soTimeout = 800
                broadcast = true
            }
            val stList = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
                "ssdp:all"
            )
            val group = InetAddress.getByName(SSDP_ADDR)
            for (st in stList) {
                val payload = buildSearch(st).toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, group, SSDP_PORT))
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val location = header(text, "LOCATION") ?: continue
                    if (!location.startsWith("http", true)) continue
                    runCatching { fetchAndRegister(location) }
                } catch (_: Exception) {
                    // timeout → continue until deadline
                }
            }
            socket.close()
            // 二次补拉：部分设备只回 ST 不带完整 desc
            delay(100)
            snapshot()
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }

    suspend fun cast(device: DlnaDevice, mediaUrl: String, title: String = "GlassBox"): Boolean =
        withContext(Dispatchers.IO) {
            val meta = didlMeta(title, mediaUrl)
            val setOk = soap(
                device.avTransportControlUrl,
                "SetAVTransportURI",
                """
                <InstanceID>0</InstanceID>
                <CurrentURI>${xmlEscape(mediaUrl)}</CurrentURI>
                <CurrentURIMetaData>${xmlEscape(meta)}</CurrentURIMetaData>
                """.trimIndent()
            )
            if (!setOk) return@withContext false
            soap(
                device.avTransportControlUrl,
                "Play",
                """
                <InstanceID>0</InstanceID>
                <Speed>1</Speed>
                """.trimIndent()
            )
        }

    suspend fun pause(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(device.avTransportControlUrl, "Pause", "<InstanceID>0</InstanceID>")
    }

    suspend fun play(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(
            device.avTransportControlUrl,
            "Play",
            "<InstanceID>0</InstanceID><Speed>1</Speed>"
        )
    }

    suspend fun stop(device: DlnaDevice): Boolean = withContext(Dispatchers.IO) {
        soap(device.avTransportControlUrl, "Stop", "<InstanceID>0</InstanceID>")
    }

    private fun fetchAndRegister(location: String) {
        val body = Net.client.newCall(
            Request.Builder().url(location).header("User-Agent", Net.UA).get().build()
        ).execute().use { it.body?.string().orEmpty() }
        if (body.isBlank()) return
        val base = location.substringBeforeLast('/') + "/"
        val parsed = parseDeviceXml(body, base, location) ?: return
        devices[parsed.udn] = parsed
        Log.i(TAG, "DLNA found: ${parsed.name} @ ${parsed.avTransportControlUrl}")
    }

    private fun parseDeviceXml(xml: String, base: String, location: String): DlnaDevice? {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser().apply {
            setInput(xml.reader())
        }
        var friendlyName = "DLNA 设备"
        var manufacturer = ""
        var model = ""
        var udn = location
        var inAvTransport = false
        var controlUrl: String? = null
        var eventUrl: String? = null
        var serviceType = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "friendlyName" -> friendlyName = parser.nextText().trim().ifBlank { friendlyName }
                    "manufacturer" -> manufacturer = parser.nextText().trim()
                    "modelName" -> model = parser.nextText().trim()
                    "UDN" -> udn = parser.nextText().trim().ifBlank { udn }
                    "serviceType" -> {
                        serviceType = parser.nextText().trim()
                        inAvTransport = serviceType.contains("AVTransport", ignoreCase = true)
                    }
                    "controlURL" -> {
                        val u = parser.nextText().trim()
                        if (inAvTransport) controlUrl = resolve(base, location, u)
                    }
                    "eventSubURL" -> {
                        val u = parser.nextText().trim()
                        if (inAvTransport) eventUrl = resolve(base, location, u)
                    }
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "service") {
                inAvTransport = false
                serviceType = ""
            }
            event = parser.next()
        }
        val ctrl = controlUrl ?: return null
        return DlnaDevice(
            udn = udn,
            name = friendlyName,
            location = location,
            manufacturer = manufacturer,
            model = model,
            avTransportControlUrl = ctrl,
            avTransportEventUrl = eventUrl
        )
    }

    private fun resolve(base: String, location: String, path: String): String {
        if (path.startsWith("http", true)) return path
        return try {
            URL(URL(location), path).toString()
        } catch (_: Exception) {
            if (path.startsWith("/")) {
                val u = URL(location)
                "${u.protocol}://${u.host}${if (u.port > 0) ":${u.port}" else ""}$path"
            } else base.trimEnd('/') + "/" + path.trimStart('/')
        }
    }

    private fun soap(controlUrl: String, action: String, bodyInner: String): Boolean {
        val envelope = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:$action xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  $bodyInner
                </u:$action>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        val req = Request.Builder()
            .url(controlUrl)
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .header("SOAPACTION", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            .header("User-Agent", Net.UA)
            .post(envelope.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            Net.client.newCall(req).execute().use { resp ->
                val ok = resp.isSuccessful
                if (!ok) Log.w(TAG, "SOAP $action failed ${resp.code}: ${resp.body?.string()?.take(200)}")
                ok
            }
        }.getOrDefault(false)
    }

    private fun didlMeta(title: String, url: String): String {
        val t = xmlEscape(title)
        val u = xmlEscape(url)
        val protocol = when {
            url.contains(".m3u8", true) -> "http-get:*:application/vnd.apple.mpegurl:*"
            url.contains(".mp4", true) -> "http-get:*:video/mp4:*"
            else -> "http-get:*:video/mpeg:*"
        }
        return """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
              xmlns:dc="http://purl.org/dc/elements/1.1/"
              xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
              <item id="0" parentID="-1" restricted="1">
                <dc:title>$t</dc:title>
                <upnp:class>object.item.videoItem</upnp:class>
                <res protocolInfo="$protocol">$u</res>
              </item>
            </DIDL-Lite>
        """.trimIndent().replace("\n", "")
    }

    private fun header(text: String, name: String): String? {
        val re = Regex("""^$name\s*:\s*(.+)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
        return re.find(text)?.groupValues?.getOrNull(1)?.trim()
    }

    private fun buildSearch(st: String): String = """
        M-SEARCH * HTTP/1.1
        HOST: $SSDP_ADDR:$SSDP_PORT
        MAN: "ssdp:discover"
        MX: 2
        ST: $st
        USER-AGENT: GlassBox/2.0 UPnP/1.1 Android

    """.trimIndent().replace("\n", "\r\n") + "\r\n"

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    companion object {
        private const val TAG = "DlnaCast"
        private const val SSDP_ADDR = "239.255.255.250"
        private const val SSDP_PORT = 1900
    }
}
