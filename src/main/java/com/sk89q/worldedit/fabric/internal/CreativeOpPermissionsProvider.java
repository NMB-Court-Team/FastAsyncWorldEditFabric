/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.fabric.internal;

import com.sk89q.worldedit.coremc.CoreMcPermissionsProvider;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * Fabric Permission API 不可用，或可用但没有处理器处理某个权限节点时使用的权限检查。
 *
 * <p>只放行创造模式玩家，以及权限等级达到 {@link Permissions#COMMANDS_GAMEMASTER}（二级 OP）的玩家。
 * 权限等级来自服务端的玩家档案权限，非 OP 玩家为 {@code ALL}，因此普通生存玩家一律被拒绝。</p>
 */
final class CreativeOpPermissionsProvider implements CoreMcPermissionsProvider {

    /**
     * 判断玩家是否可执行需要权限的操作。
     *
     * @param player     发起权限检查的玩家
     * @param permission 权限字符串；本实现不区分具体节点，所有节点使用同一判断
     * @return 玩家处于创造模式或拥有二级 OP 权限时返回 {@code true}
     */
    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        return player.isCreative() || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * 平台注册权限节点时调用；本实现不做任何登记。
     *
     * @param permission 权限字符串
     */
    @Override
    public void registerPermission(String permission) {
    }
}
