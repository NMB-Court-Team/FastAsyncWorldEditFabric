package net.astrorbits.fawe.perm

import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * 本模组的权限目录和查询入口。
 *
 * 对象初始化时写入第一阶段用到的节点。AzamcBasic 第一次调用 [all] 就会触发这次初始化，
 * 不依赖两个模组的 `onInitialize` 谁先谁后。本模组不引用 AzamcBasic。
 *
 * 未调用 [bind] 时，[has] 要求玩家是 OP。控制台不经过这里。已绑定后，[has] 只调用装入的查询。
 */
object FawePermissions {
    private val nodes = LinkedHashMap<String, FawePermissionNode>()

    init {
        register(
            FawePermissionNode(
                id = "worldedit.selection.pos",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "设置 WorldEdit 选区位置",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.region.set",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用 //set 填充选区",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.history.undo",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "撤销 WorldEdit 编辑",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.history.undo.self",
                defaultGroup = FawePermissionDefault.ADMIN,
                parentId = "worldedit.history.undo",
                description = "撤销自己的 WorldEdit 编辑",
            ),
        )
    }

    /**
     * 把节点写入目录。同一 ID 已存在时保留先登记的那条。
     *
     * @param node 要登记的节点。`id` 不能为空白。
     */
    fun register(node: FawePermissionNode) {
        require(node.id.isNotBlank()) { "permission id is blank" }
        synchronized(nodes) {
            if (nodes.containsKey(node.id)) {
                return
            }
            nodes[node.id] = node
        }
    }

    /**
     * 当前已登记的节点，按登记顺序排列。
     *
     * 第一次调用会初始化本对象并写入第一阶段节点。
     *
     * @return 目录快照。之后再 [register] 不会改动这次返回的列表。
     */
    fun all(): List<FawePermissionNode> = synchronized(nodes) { nodes.values.toList() }

    /**
     * 装入权限查询。再次调用会替换上一次的查询。
     *
     * 查询在 WorldEdit 检查权限时于服务器线程调用，只应读取已有结果，不要在其中等待总线。
     *
     * @param query 参数是玩家 UUID 和 WorldEdit 节点 ID，返回该玩家是否持有节点。
     */
    fun bind(query: (UUID, String) -> Boolean) {
        FawePermissionBridge.bind { playerId, nodeId -> query(playerId, nodeId) }
    }

    /**
     * 查询玩家是否持有节点。
     *
     * 已调用 [bind] 时只走装入的查询。尚未绑定时，玩家需要 OP。
     *
     * @param player 当前服务器上的玩家。
     * @param nodeId WorldEdit 权限节点 ID。未在目录中登记的 ID 也会交给查询或 OP 回退。
     * @return 玩家是否可以通过这次检查。
     */
    fun has(player: ServerPlayer, nodeId: String): Boolean = FawePermissionBridge.has(player, nodeId)
}
