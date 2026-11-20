package com.sk89q.worldedit.command;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.command.util.CommandPermissions;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import net.astrorbits.fawe.config.FaweConfig;
import net.astrorbits.fawe.limit.FaweLimits;
import org.enginehub.piston.annotation.Command;
import org.enginehub.piston.annotation.CommandContainer;
import org.enginehub.piston.annotation.param.Arg;

import java.util.Locale;
import java.util.UUID;

/**
 * 调整单个玩家限额的命令。
 * <p>
 * 覆盖只保存在内存里：玩家退出、服务器关闭后恢复为按权限计算的限额。覆盖组里没有指定的项沿用配置里的 `default` 组。
 */
@CommandContainer(superTypes = CommandPermissionsConditionGenerator.Registration.class)
public class FaweLimitCommands {
    private static final String LIMIT_NAMES = "maxchanges, maxchecks, maxfails, maxiterations, "
        + "maxentities, maxblockstates, maxradius, maxbrushradius, maxexpressionms, maxhistorymb";

    private final WorldEdit worldEdit;

    public FaweLimitCommands(WorldEdit worldEdit) {
        this.worldEdit = worldEdit;
    }

    @Command(name = "/limitplayer", desc = "Adjust one player's FAWE limits")
    @CommandPermissions("worldedit.limit.player")
    public void limitPlayer(Actor actor,
                            @Arg(desc = "Player name or UUID") String player,
                            @Arg(desc = "Limit name, or clear") String limit,
                            @Arg(desc = "New cap, -1 for no cap (radius: WorldEdit config)", def = "") String value) {
        UUID uniqueId = findUniqueId(player);
        if (uniqueId == null) {
            actor.printError(TranslatableComponent.of(
                "fastasyncworldedit.limit.player.not-found",
                TextComponent.of(player)
            ));
            return;
        }
        if ("clear".equalsIgnoreCase(limit)) {
            FaweLimits.clearOverride(uniqueId);
            actor.printInfo(TranslatableComponent.of(
                "fastasyncworldedit.limit.player.cleared",
                TextComponent.of(player)
            ));
            return;
        }
        Long parsed = parseValue(value);
        if (parsed == null) {
            actor.printError(TranslatableComponent.of("fastasyncworldedit.limit.player.bad-value"));
            return;
        }
        FaweConfig.LimitGroup group = FaweLimits.getOverride(uniqueId);
        if (group == null) {
            FaweConfig.LimitGroup base = FaweConfig.INSTANCE.limitGroups().get("default");
            group = base == null ? new FaweConfig.LimitGroup() : base.copy();
        }
        boolean applied;
        try {
            applied = applyLimit(group, limit, parsed);
        } catch (ArithmeticException outOfRange) {
            actor.printError(TranslatableComponent.of("fastasyncworldedit.limit.player.out-of-range"));
            return;
        }
        if (!applied) {
            actor.printError(TranslatableComponent.of(
                "fastasyncworldedit.limit.player.unknown",
                TextComponent.of(limit),
                TextComponent.of(LIMIT_NAMES)
            ));
            return;
        }
        FaweLimits.setOverride(uniqueId, group);
        actor.printInfo(TranslatableComponent.of(
            "fastasyncworldedit.limit.player.set",
            TextComponent.of(player),
            TextComponent.of(limit.toLowerCase(Locale.ROOT)),
            TextComponent.of(Long.toString(parsed))
        ));
    }

    private UUID findUniqueId(String player) {
        try {
            return UUID.fromString(player);
        } catch (IllegalArgumentException ignored) {
            // 输入不是 UUID，改按玩家名查找在线会话。
        }
        return worldEdit.getSessionManager().findUniqueIdByName(player);
    }

    private static Long parseValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed < -1 ? null : parsed;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean applyLimit(FaweConfig.LimitGroup group, String limit, long value) {
        switch (limit.toLowerCase(Locale.ROOT)) {
            case "maxchanges" -> group.setMaxChanges(value);
            case "maxchecks" -> group.setMaxChecks(value);
            case "maxfails" -> group.setMaxFails(Math.toIntExact(value));
            case "maxiterations" -> group.setMaxIterations(Math.toIntExact(value));
            case "maxentities" -> group.setMaxEntities(Math.toIntExact(value));
            case "maxblockstates" -> group.setMaxBlockStates(Math.toIntExact(value));
            case "maxradius" -> group.setMaxRadius(Math.toIntExact(value));
            case "maxbrushradius" -> group.setMaxBrushRadius(Math.toIntExact(value));
            case "maxexpressionms" -> group.setMaxExpressionMs(Math.toIntExact(value));
            case "maxhistorymb" -> group.setMaxHistoryMb(Math.toIntExact(value));
            default -> {
                return false;
            }
        }
        return true;
    }
}
