package net.astrorbits.fawe

import net.fabricmc.api.ModInitializer
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object FastAsyncWorldEditMain : ModInitializer {
    const val MOD_ID = "fastasyncworldedit"
    val LOGGER: Logger = LoggerFactory.getLogger("FastAsyncWorldEdit")

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    override fun onInitialize() {
    }
}
