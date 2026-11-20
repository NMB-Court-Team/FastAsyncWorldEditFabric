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

import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把 WorldEdit 的权限字符串转换成 Fabric Permission API 的权限节点并执行查询。
 *
 * <p>本类直接引用 {@code net.fabricmc.fabric.api.permission.v1} 的类型，只能在 Fabric Permission API
 * 模块存在时加载。加载时机由调用方决定：{@link FabricPermissionsProvider} 先探测该模块，确认存在后
 * 才会触发本类的类初始化。</p>
 */
final class FabricPermissionApiSupport {

    private static final Map<String, PermissionNode<Boolean>> NODES = new ConcurrentHashMap<>();

    private FabricPermissionApiSupport() {
    }

    /**
     * 通过 Fabric Permission API 查询玩家是否拥有指定权限。
     *
     * <p>查询会交给已加载的权限处理器（{@code PermissionEvents.ON_REQUEST}）。没有任何处理器处理该
     * 节点时返回 {@code null}，由调用方决定回退策略；权限字符串无法转换成 {@link Identifier} 时同样
     * 返回 {@code null}。</p>
     *
     * @param player     发起权限检查的玩家
     * @param permission WorldEdit 风格的权限字符串，如 {@code worldedit.region.set}
     * @return 处理器给出的查询结果；未处理该节点时返回 {@code null}
     */
    static @Nullable Boolean checkPermission(ServerPlayer player, String permission) {
        PermissionNode<Boolean> node = nodeFor(permission);
        if (node == null) {
            return null;
        }
        return player.checkPermission(node);
    }

    /**
     * 提前创建并缓存权限节点，避免首次查询时重复做字符串转换。
     *
     * @param permission WorldEdit 风格的权限字符串
     */
    static void registerPermission(String permission) {
        nodeFor(permission);
    }

    /**
     * 读取或创建权限字符串对应的权限节点。
     *
     * <p>权限字符串至少要含一个 {@code .} 作为命名空间分隔符，且该分隔符不在首尾，才能映射成
     * {@code a:b.c}；不满足时返回 {@code null}，由调用方回退。</p>
     *
     * @param permission WorldEdit 风格的权限字符串
     * @return 缓存中的权限节点；无法映射时返回 {@code null}
     */
    private static @Nullable PermissionNode<Boolean> nodeFor(String permission) {
        PermissionNode<Boolean> cached = NODES.get(permission);
        if (cached != null) {
            return cached;
        }

        PermissionNode<Boolean> created = createNode(permission);
        if (created == null) {
            return null;
        }

        PermissionNode<Boolean> existing = NODES.putIfAbsent(permission, created);
        return existing != null ? existing : created;
    }

    private static @Nullable PermissionNode<Boolean> createNode(String permission) {
        Identifier identifier = identifierFor(permission);
        return identifier != null ? PermissionNode.of(identifier) : null;
    }

    /**
     * 把 {@code a.b.c.d} 形式的权限字符串转换成 Fabric 使用的 {@code a:b.c.d}。
     *
     * <p>{@link Identifier} 的字符集比权限字符串更严格。编码时先把整段转成小写，再按 UTF-8 字节
     * 逐个处理：保留小写字母、数字、{@code .} 和 {@code -}，路径部分额外保留 {@code /}，其余字节写成
     * {@code _} 加两位十六进制；一个非 ASCII 字符对应多个字节，因此会产生多组 {@code _hh}。命名空间
     * 部分不允许出现斜杠，路径部分允许。</p>
     *
     * @param permission WorldEdit 风格的权限字符串
     * @return 对应的标识符；字符串不含命名空间分隔符，或分隔符位于首尾时返回 {@code null}
     */
    static @Nullable Identifier identifierFor(String permission) {
        int namespaceSeparator = permission.indexOf('.');
        if (namespaceSeparator < 1 || namespaceSeparator == permission.length() - 1) {
            return null;
        }

        return Identifier.fromNamespaceAndPath(
            encodeIdentifierPart(permission.substring(0, namespaceSeparator), false),
            encodeIdentifierPart(permission.substring(namespaceSeparator + 1), true)
        );
    }

    private static String encodeIdentifierPart(String value, boolean allowSlash) {
        byte[] bytes = value.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte rawByte : bytes) {
            int valueByte = Byte.toUnsignedInt(rawByte);
            if ((valueByte >= 'a' && valueByte <= 'z')
                    || (valueByte >= '0' && valueByte <= '9')
                    || valueByte == '.' || valueByte == '-'
                    || (allowSlash && valueByte == '/')) {
                encoded.append((char) valueByte);
            } else {
                encoded.append('_');
                encoded.append(HexFormat.of().toHexDigits(rawByte));
            }
        }
        return encoded.toString();
    }
}
