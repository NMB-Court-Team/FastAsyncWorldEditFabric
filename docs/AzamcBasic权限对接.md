# AzamcBasic 权限对接

本模组自己登记权限目录，并提供查询入口。AzamcBasic 依赖本模组，在自己的初始化里读取目录、绑定查询。本模组不引用 `net.astrorbits.azamc`，也不反射调用 AzamcBasic。

没装 AzamcBasic 时，玩家权限回退到 OP，控制台保持放行。

## 依赖

编译期依赖本模组。`fabric.mod.json` 使用 `suggests`，不要写成 `depends`。没装本模组的大厅服仍能启动。

读取目录和绑定查询的代码放在单独的类里，只在确认本模组已安装后加载。这样未安装时不会碰到 `net.astrorbits.fawe.perm` 里的类型。

## 登记

在权限快照写出之前，把目录映射成 AzamcBasic 自己的 `PermissionNode`，再交给 `AzamcPermissionGroups.register`。时机与 `AzamcBlockPartyServer` 登记节点相同。

`FawePermissionDefault` 的名字与 `PermissionDefault` 一致，可以直接 `valueOf`。

```kotlin
val nodes = FawePermissions.all().map { node ->
    PermissionNode(
        id = node.id,
        defaultGroup = PermissionDefault.valueOf(node.defaultGroup.name),
        parentId = node.parentId,
        description = node.description,
    )
}
AzamcPermissionGroups.register(nodes)
```

`FawePermissions.all()` 会触发目录初始化，不依赖两个模组谁先进入 `onInitialize`。

已登记的节点默认组都是 `ADMIN`：

| id | parentId | 说明 |
| --- | --- | --- |
| `worldedit.selection.pos` | 空 | 设置选区位置 |
| `worldedit.region.set` | 空 | 使用 `//set` 填充选区 |
| `worldedit.history.undo` | 空 | 撤销编辑 |
| `worldedit.history.undo.self` | `worldedit.history.undo` | 撤销自己的编辑 |
| `worldedit.region.replace` | 空 | 使用 `//replace` 替换选区内的方块 |
| `worldedit.region.walls` | 空 | 使用 `//walls` 生成选区四面墙 |
| `worldedit.region.faces` | 空 | 使用 `//faces` 生成选区表面 |
| `worldedit.region.overlay` | 空 | 使用 `//overlay` 在选区内已有方块的上方放一层 |
| `worldedit.clipboard.copy` | 空 | 使用 `//copy` 复制选区 |
| `worldedit.clipboard.cut` | 空 | 使用 `//cut` 剪切选区 |
| `worldedit.clipboard.paste` | 空 | 使用 `//paste` 粘贴剪贴板 |
| `worldedit.history.redo` | 空 | 重做 WorldEdit 编辑 |
| `worldedit.history.redo.self` | `worldedit.history.redo` | 重做自己的 WorldEdit 编辑 |

节点 ID 就是 WorldEdit 命令上的原名。`//undo` 接受 `worldedit.history.undo` 或 `worldedit.history.undo.self` 里的任意一个。`//redo` 接受 `worldedit.history.redo` 或 `worldedit.history.redo.self` 里的任意一个。

## 查询

登记之后绑定只读查询。检查时读集群权限的内存快照，不要在主线程上等待总线。

```kotlin
FawePermissions.bind { uuid, nodeId ->
    ClusterPermissionCache.has(uuid, nodeId)
}
```

绑定之后，玩家的 `Actor.hasPermission` 只调用这个函数。再次调用 `bind` 会替换上一次的查询。
