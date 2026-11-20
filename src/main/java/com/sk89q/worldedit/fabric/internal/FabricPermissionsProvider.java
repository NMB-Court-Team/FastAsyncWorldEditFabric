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

/**
 * Fabric 平台的权限检查入口。
 *
 * <p>Fabric Permission API 可用时，把权限查询交给该 API 上注册的处理器；API 不可用，或没有处理器处理
 * 该权限节点时，回退到 {@link CreativeOpPermissionsProvider}，即只放行创造模式玩家与二级 OP。</p>
 *
 * <p>Fabric Permission API 是独立模块，未安装时相关类型不在运行时类路径上，因此本类只在首次执行
 * {@link #hasPermission} 或 {@link #registerPermission} 时，才通过 {@link FabricPermissionApiSupport}
 * 引用其类型；模块可用性在类初始化阶段用 {@link Class#forName} 探测，避免触发
 * {@link NoClassDefFoundError}。</p>
 */
final class FabricPermissionsProvider implements CoreMcPermissionsProvider {

    private static final String FABRIC_PERMISSION_NODE_CLASS = "net.fabricmc.fabric.api.permission.v1.PermissionNode";
    private static final boolean FABRIC_PERMISSION_API_AVAILABLE = detectFabricPermissionApi();

    private final CoreMcPermissionsProvider fallback = new CreativeOpPermissionsProvider();

    @Override
    public boolean hasPermission(ServerPlayer player, String permission) {
        if (FABRIC_PERMISSION_API_AVAILABLE) {
            Boolean result = FabricPermissionApiSupport.checkPermission(player, permission);
            if (result != null) {
                return result;
            }
        }
        return fallback.hasPermission(player, permission);
    }

    @Override
    public void registerPermission(String permission) {
        fallback.registerPermission(permission);
        if (FABRIC_PERMISSION_API_AVAILABLE) {
            FabricPermissionApiSupport.registerPermission(permission);
        }
    }

    /**
     * 探测 Fabric Permission API 模块是否可用。
     *
     * <p>只检查类型是否存在，不初始化该类；模块缺失时返回 {@code false}，后续调用不会再触碰
     * Fabric Permission API 的类型。</p>
     *
     * @return 模块存在时返回 {@code true}
     */
    private static boolean detectFabricPermissionApi() {
        try {
            Class.forName(FABRIC_PERMISSION_NODE_CLASS, false, FabricPermissionsProvider.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }
}
