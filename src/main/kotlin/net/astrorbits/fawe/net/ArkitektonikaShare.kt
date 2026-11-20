package net.astrorbits.fawe.net

import com.google.gson.JsonParser
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.share.ClipboardShareDestination
import com.sk89q.worldedit.extent.clipboard.io.share.ClipboardShareMetadata
import com.sk89q.worldedit.extent.clipboard.io.share.ShareOutputProvider
import com.sk89q.worldedit.util.formatting.text.TextComponent
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent
import com.sk89q.worldedit.util.formatting.text.event.ClickEvent
import net.astrorbits.fawe.config.FaweConfig
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import java.util.function.Consumer

/**
 * 把剪贴板上传到配置里的 Arkitektonika 后端。
 *
 * 别名是 `arkitektonika` 和 `fawe`。默认格式是 Sponge v2，也接受 Sponge v3。
 * 上传成功后把下载链接和删除链接发给玩家。状态码不是 200 时抛出 `IllegalStateException`。
 * 响应里没有 `download_key` 或 `delete_key` 时，读取字段会失败。
 */
object ArkitektonikaShare : ClipboardShareDestination {
    private val client: HttpClient = HttpClient.newHttpClient()

    override fun getName(): String = "Arkitektonika"

    override fun getAliases(): Set<String> = setOf("arkitektonika", "fawe")

    override fun getDefaultFormat(): ClipboardFormat = BuiltInClipboardFormat.SPONGE_V2_SCHEMATIC

    override fun supportsFormat(format: ClipboardFormat): Boolean {
        return format == BuiltInClipboardFormat.SPONGE_V2_SCHEMATIC || format == BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
    }

    override fun share(metadata: ClipboardShareMetadata, serializer: ShareOutputProvider): Consumer<Actor> {
        val schematic = ByteArrayOutputStream()
        serializer.writeTo(schematic)
        val boundary = UUID.randomUUID().toString()
        val filename = metadata.name() + "." + metadata.format().primaryFileExtension
        val body = HttpRequest.BodyPublishers.concat(
            HttpRequest.BodyPublishers.ofString("--$boundary\r\n"),
            HttpRequest.BodyPublishers.ofString(
                "Content-Disposition: form-data; name=\"schematic\"; filename=\"$filename\"\r\n\r\n",
            ),
            HttpRequest.BodyPublishers.ofByteArray(schematic.toByteArray()),
            HttpRequest.BodyPublishers.ofString("\r\n--$boundary--"),
        )
        val backend = FaweConfig.arkitektonikaBackend.trimEnd('/')
        val response = client.send(
            HttpRequest.newBuilder(URI.create("$backend/upload"))
                .header("Content-Type", "multipart/form-data; boundary=\"$boundary\"")
                .POST(body)
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) {
            throw IllegalStateException("Arkitektonika returned status " + response.statusCode())
        }
        val json = JsonParser.parseString(response.body()).asJsonObject
        val download = FaweConfig.arkitektonikaDownload.replace("{key}", json.get("download_key").asString)
        val delete = FaweConfig.arkitektonikaDelete.replace("{key}", json.get("delete_key").asString)
        return Consumer { actor ->
            actor.printInfo(link("fastasyncworldedit.schematic.download", download))
            actor.printInfo(link("fastasyncworldedit.schematic.delete-link", delete))
        }
    }

    private fun link(key: String, url: String): TranslatableComponent {
        return TranslatableComponent.of(
            key,
            TextComponent.of(url).clickEvent(ClickEvent.openUrl(url)),
        )
    }
}
