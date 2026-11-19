package net.astrorbits.fawe

import com.sk89q.worldedit.fabric.internal.FabricWorldEdit
import net.astrorbits.fawe.config.FaweConfig
import net.astrorbits.fawe.guard.RegionEditGuards
import net.astrorbits.fawe.perm.FawePermissions
import net.astrorbits.fawe.queue.ChunkWriteQueue
import net.astrorbits.fawe.queue.FaweEditSessions
import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object FastAsyncWorldEditMain : ModInitializer {
    const val MOD_ID = "fastasyncworldedit"
    val LOGGER: Logger = LoggerFactory.getLogger("FastAsyncWorldEdit")

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
        FaweConfig.load()
        FawePermissions.all()
        RegionEditGuards.loadEntrypoints()
        ChunkWriteQueue.start()
        FabricWorldEdit().onInitialize()
        FaweEditSessions.register()
        LOGGER.info("FastAsyncWorldEdit started")
    }
}
