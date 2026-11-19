package net.astrorbits.fawe.perm;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Fabric 侧权限查询的稳定入口。
 *
 * <p>未调用 {@link #bind(Query)} 时，玩家需要 OP。控制台不走这里，WorldEdit 的控制台 Actor 自行放行。
 * 已绑定后只调用装入的查询，不再看 OP。
 */
public final class FawePermissionBridge {
    /**
     * 集群或其它权限实现提供的查询。
     */
    @FunctionalInterface
    public interface Query {
        /**
         * @param playerId 玩家 UUID
         * @param nodeId WorldEdit 权限节点，例如 {@code worldedit.region.set}
         * @return 该玩家是否持有节点
         */
        boolean has(UUID playerId, String nodeId);
    }

    private static volatile Query bound;

    private FawePermissionBridge() {
    }

    /**
     * 装入查询。再次调用会替换上一次的查询。
     *
     * @param query 只读查询，调用方不得在其中等待总线
     */
    public static void bind(Query query) {
        bound = query;
    }

    /**
     * @param player 当前服务器玩家
     * @param nodeId WorldEdit 权限节点
     * @return 已绑定时为查询结果；未绑定时为该玩家是否为 OP
     */
    public static boolean has(ServerPlayer player, String nodeId) {
        Query query = bound;
        if (query != null) {
            return query.has(player.getUUID(), nodeId);
        }
        ServerLevel level = player.level();
        return level.getServer().getPlayerList().isOp(player.nameAndId());
    }
}
