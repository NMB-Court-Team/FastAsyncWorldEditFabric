package net.astrorbits.fawe.perm

import net.astrorbits.fawe.limit.FaweLimits
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * 本模组的权限目录和查询入口。
 *
 * 对象初始化时写入本模组开放的权限节点。AzamcBasic 第一次调用 [all] 就会触发这次初始化，
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
                description = "设置WorldEdit选区位置",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.region.set",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//set 填充选区",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.history.undo",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "撤销WorldEdit编辑",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.history.undo.self",
                defaultGroup = FawePermissionDefault.ADMIN,
                parentId = "worldedit.history.undo",
                description = "撤销自己的WorldEdit编辑",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.region.replace",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//replace 替换选区内的方块",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.region.walls",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//walls 生成选区四面墙",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.region.faces",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//faces 生成选区表面",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.region.overlay",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//overlay 在选区内已有方块的上方放一层",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.clipboard.copy",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//copy 复制选区",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.clipboard.cut",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//cut 剪切选区",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.clipboard.paste",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//paste 粘贴剪贴板",
            ),
        )
        // //schem load 与 //schem save 的权限条件是两个节点满足其一。
        // 两个节点都放进目录后，外部权限系统才能分别授予。
        register(
            FawePermissionNode(
                id = "worldedit.clipboard.load",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem load 把原理图加载进剪贴板。与worldedit.schematic.load满足其一即可",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.schematic.load",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem load 把原理图加载进剪贴板。与worldedit.clipboard.load满足其一即可",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.clipboard.save",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem save 把剪贴板保存为原理图。与worldedit.schematic.save满足其一即可",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.schematic.save",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem save 把剪贴板保存为原理图。与worldedit.clipboard.save满足其一即可",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.schematic.delete",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem delete 删除原理图。覆盖已有文件的//schem save -f 也检查这个节点",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.schematic.list",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem list 列出已保存的原理图",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.schematic.formats",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "使用//schem formats 列出可用的原理图格式",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.history.redo",
                defaultGroup = FawePermissionDefault.ADMIN,
                description = "重做WorldEdit编辑",
            ),
        )
        register(
            FawePermissionNode(
                id = "worldedit.history.redo.self",
                defaultGroup = FawePermissionDefault.ADMIN,
                parentId = "worldedit.history.redo",
                description = "重做自己的WorldEdit编辑",
            ),
        )
        FaweExtraPermissions.register()
        FaweLimits.registerNodes()
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
     * 第一次调用会初始化本对象并写入本模组开放的权限节点。
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
