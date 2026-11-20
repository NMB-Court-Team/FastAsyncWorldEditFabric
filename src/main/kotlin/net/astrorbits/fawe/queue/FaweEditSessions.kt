package net.astrorbits.fawe.queue

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.event.extent.EditSessionEvent
import com.sk89q.worldedit.util.eventbus.Subscribe

/**
 * 在 ChangeSet 内侧插入 [QueuedExtent]。
 *
 * `BEFORE_CHANGE` 位于磁盘历史的里面：历史先记下旧方块，再由队列写入。
 * `//undo` 重放走 `bypassHistory`，仍然经过这一层。
 */
object FaweEditSessions {
    /**
     * @param event WorldEdit 创建编辑会话时发出的事件。其它阶段不改范围。
     */
    @Subscribe
    fun onEditSession(event: EditSessionEvent) {
        if (event.stage != EditSession.Stage.BEFORE_CHANGE) {
            return
        }
        val world = event.world ?: return
        event.extent = QueuedExtent(event.extent, world, event.actor)
    }

    /**
     * 向 WorldEdit 事件总线登记 [onEditSession]。WorldEdit 平台完成初始化后调用。
     */
    fun register() {
        WorldEdit.getInstance().eventBus.register(this)
    }
}
