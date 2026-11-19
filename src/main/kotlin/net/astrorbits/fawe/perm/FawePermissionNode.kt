package net.astrorbits.fawe.perm

/**
 * 本模组公开的一条权限节点。
 *
 * 字段顺序与 AzamcBasic 的 `PermissionNode` 对齐：`id`、`defaultGroup`、`parentId`、`description`。
 * 节点 ID 使用 WorldEdit 原名，例如 `worldedit.region.set`。
 *
 * @property id WorldEdit 权限节点 ID，不能为空白。
 * @property defaultGroup 对方把节点放进权限组时的默认位置。
 * @property parentId 父节点 ID。没有父节点时为空字符串。
 * @property description 给权限目录展示的说明。
 */
data class FawePermissionNode(
    val id: String,
    val defaultGroup: FawePermissionDefault,
    val parentId: String = "",
    val description: String = "",
)
