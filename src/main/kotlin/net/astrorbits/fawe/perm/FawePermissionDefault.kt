package net.astrorbits.fawe.perm

/**
 * 权限节点进入对方权限组时的默认位置。
 *
 * 名字与 AzamcBasic 的 `PermissionDefault` 一致，对方可以用 `valueOf` 转换，不必共享类型。
 *
 * @property DEFAULT 写入默认组，普通玩家可用。
 * @property ADMIN 写入管理员组。
 * @property NONE 只进目录，不写入任何组，需要再单独授予。
 */
enum class FawePermissionDefault {
    DEFAULT,
    ADMIN,
    NONE,
}
