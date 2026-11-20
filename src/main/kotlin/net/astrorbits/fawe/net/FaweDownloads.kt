package net.astrorbits.fawe.net

import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import net.astrorbits.fawe.config.FaweConfig
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import javax.imageio.ImageIO

/**
 * 按白名单下载 https 资源。
 *
 * 主机名必须在调用方给出的名单里。解析出的地址如果是回环、链路本地、站点本地或任意地址，下载会被拒绝。
 * 不跟随重定向，避免跳到名单之外的主机。
 */
object FaweDownloads {
    const val MAX_BYTES: Int = 16 * 1024 * 1024

    /**
     * 下载被拒绝或没有得到可用内容。
     *
     * @property key 给玩家看的翻译键。
     */
    class Rejected(val key: String) : RuntimeException(key)

    /**
     * 把 `url:<uuid>` 拼成 [FaweConfig.webUrl] 下面的 `uploads/<uuid>.schem`。
     *
     * @param token `url:` 后面的内容。长度必须是 1 到 64，并且只能含字母、数字和连字符。
     * @return 可以继续交给 [download] 的地址。
     * @throws Rejected token 长度或字符不符合要求，或根地址不是 https。
     */
    @JvmStatic
    fun webClipboardUri(token: String): URI {
        if (!token.matches(Regex("[A-Za-z0-9-]{1,64}"))) {
            throw Rejected("fastasyncworldedit.schematic.bad-url")
        }
        return parseHttps(FaweConfig.webUrl + "uploads/" + token + ".schem")
    }

    /**
     * 下载一份 https 资源。
     *
     * @param raw 完整的 https 地址。
     * @param hosts 允许的主机名，不含端口，大小写不敏感。
     * @param timeoutSeconds 连接和读取的超时，单位是秒。小于 1 时按 1 秒执行。
     * @return 响应体。
     * @throws Rejected 地址不是 https、带有用户信息、主机为空、主机不在名单里、解析失败，或解析结果是回环、链路本地、站点本地、任意地址。状态不是 200 或正文超过 [MAX_BYTES] 时同样抛出。
     * @throws java.io.IOException `client.send` 失败时抛出。
     * @throws InterruptedException 调用线程被中断时抛出。
     */
    @JvmStatic
    fun download(raw: String, hosts: List<String>, timeoutSeconds: Int): ByteArray {
        val uri = parseHttps(raw)
        assertAllowed(uri, hosts)
        val timeout = Duration.ofSeconds(timeoutSeconds.toLong().coerceAtLeast(1))
        val client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            throw Rejected("fastasyncworldedit.schematic.download-failed")
        }
        val body = response.body()
        if (body.size > MAX_BYTES) {
            throw Rejected("fastasyncworldedit.schematic.download-failed")
        }
        return body
    }

    /**
     * 用现有剪贴板格式读取刚下载的字节。读完就删除临时文件。
     *
     * @param bytes 原理图字节。
     * @param suffix 临时文件后缀，例如 `.schem`。
     * @return 读出的剪贴板。认不出格式时按 Sponge v2 读。
     */
    @JvmStatic
    fun readClipboard(bytes: ByteArray, suffix: String): Clipboard {
        val temp = Files.createTempFile("fawe-download", suffix)
        try {
            Files.write(temp, bytes)
            val format = ClipboardFormats.findByFile(temp.toFile()) ?: BuiltInClipboardFormat.SPONGE_V2_SCHEMATIC
            format.getReader(Files.newInputStream(temp)).use { reader ->
                return reader.read()
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    /**
     * 把下载的字节解码成图片，并限制像素数。
     *
     * @param bytes 图片字节。
     * @param maxPixels 宽乘高的上限。
     * @return 解码后的图片。
     * @throws Rejected 不是图片，或像素数超过上限。
     */
    fun readImage(bytes: ByteArray, maxPixels: Int): BufferedImage {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: throw Rejected("fastasyncworldedit.brush.bad-image")
        if (image.width.toLong() * image.height.toLong() > maxPixels.toLong()) {
            throw Rejected("fastasyncworldedit.brush.bad-image")
        }
        return image
    }

    private fun parseHttps(raw: String): URI {
        val uri = try {
            URI(raw)
        } catch (_: Exception) {
            throw Rejected("fastasyncworldedit.schematic.bad-url")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw Rejected("fastasyncworldedit.schematic.bad-url")
        }
        return uri
    }

    private fun assertAllowed(uri: URI, hosts: List<String>) {
        val host = uri.host.lowercase()
        if (hosts.none { it.equals(host, ignoreCase = true) }) {
            throw Rejected("fastasyncworldedit.schematic.host-denied")
        }
        val addresses = try {
            InetAddress.getAllByName(uri.host)
        } catch (_: Exception) {
            throw Rejected("fastasyncworldedit.schematic.host-denied")
        }
        if (addresses.any { it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress }) {
            throw Rejected("fastasyncworldedit.schematic.host-denied")
        }
    }
}
