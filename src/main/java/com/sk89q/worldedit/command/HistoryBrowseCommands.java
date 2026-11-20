package com.sk89q.worldedit.command;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.command.util.CommandPermissions;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extension.platform.Locatable;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.World;
import net.astrorbits.fawe.history.DiskHistories;
import org.enginehub.piston.annotation.Command;
import org.enginehub.piston.exception.StopExecutionException;
import org.enginehub.piston.annotation.CommandContainer;
import org.enginehub.piston.annotation.param.Arg;

import java.util.List;
import java.util.Map;

/**
 * 直接扫描磁盘历史文件的命令。
 */
@CommandContainer(superTypes = CommandPermissionsConditionGenerator.Registration.class)
public class HistoryBrowseCommands {
    private List<DiskHistories.Entry> select(Actor actor, String player, int radius, String time) throws WorldEditException {
        Long duration = DiskHistories.parseDuration(time);
        if (duration == null) {
            throw new StopExecutionException(TranslatableComponent.of("fastasyncworldedit.history.bad-time", TextComponent.of(time)));
        }
        String actorId = "me".equalsIgnoreCase(player) ? actor.getUniqueId().toString() : player;
        if ("*".equals(player)) {
            actorId = null;
        }
        String worldName = null;
        BlockVector3 center = null;
        if (actor instanceof Locatable locatable && locatable.getExtent() instanceof World world) {
            worldName = world.getName();
            center = locatable.getLocation().toVector().toBlockPoint();
        }
        long newerThan = duration == 0 ? 0 : System.currentTimeMillis() - duration;
        return DiskHistories.find(worldName, actorId, center, radius, newerThan);
    }

    @Command(name = "find", desc = "List matching history files")
    @CommandPermissions("worldedit.history.find")
    public void find(Actor actor,
                     @Arg(desc = "Player UUID, me, or *", def = "me") String player,
                     @Arg(desc = "Radius, 0 for anywhere", def = "0") int radius,
                     @Arg(desc = "Age such as 20s, 5m, 1h, 2d", def = "0") String time) throws WorldEditException {
        List<DiskHistories.Entry> entries = select(actor, player, radius, time);
        if (entries.isEmpty()) {
            actor.printInfo(TranslatableComponent.of("fastasyncworldedit.history.empty"));
            return;
        }
        actor.printInfo(TranslatableComponent.of("fastasyncworldedit.history.list", TextComponent.of(entries.size())));
        int shown = Math.min(entries.size(), 10);
        for (int index = 0; index < shown; index++) {
            DiskHistories.Entry entry = entries.get(index);
            actor.printInfo(TextComponent.of(index + " " + entry.getCreatedAt() + " " + entry.getCount()));
        }
    }

    @Command(name = "list", desc = "List your recent history files")
    @CommandPermissions("worldedit.history.list")
    public void list(Actor actor, LocalSession session) throws WorldEditException {
        find(actor, "me", 0, "0");
    }

    @Command(name = "rollback", desc = "Undo matching history files")
    @CommandPermissions("worldedit.history.rollback")
    public void rollback(Actor actor, LocalSession session,
                         @Arg(desc = "Player UUID, me, or *", def = "me") String player,
                         @Arg(desc = "Radius, 0 for anywhere", def = "0") int radius,
                         @Arg(desc = "Age such as 20s, 5m, 1h, 2d", def = "0") String time) throws WorldEditException {
        applyAll(actor, player, radius, time, true);
    }

    @Command(name = "restore", desc = "Redo matching history files")
    @CommandPermissions("worldedit.history.restore")
    public void restore(Actor actor, LocalSession session,
                        @Arg(desc = "Player UUID, me, or *", def = "me") String player,
                        @Arg(desc = "Radius, 0 for anywhere", def = "0") int radius,
                        @Arg(desc = "Age such as 20s, 5m, 1h, 2d", def = "0") String time) throws WorldEditException {
        applyAll(actor, player, radius, time, false);
    }

    @Command(name = "info", desc = "Show one history file")
    @CommandPermissions("worldedit.history.info")
    public void info(Actor actor,
                     @Arg(desc = "Index in the newest-first list", def = "0") int index,
                     @Arg(desc = "Player UUID, me, or *", def = "me") String player,
                     @Arg(desc = "Radius, 0 for anywhere", def = "0") int radius,
                     @Arg(desc = "Age such as 20s, 5m, 1h, 2d", def = "0") String time) throws WorldEditException {
        DiskHistories.Entry entry = one(actor, player, radius, time, index);
        actor.printInfo(TranslatableComponent.of(
            "fastasyncworldedit.history.info",
            TextComponent.of(entry.getWorldName()),
            TextComponent.of(entry.getActorId()),
            TextComponent.of(entry.getCount())
        ));
    }

    @Command(name = "distr", desc = "Count block states in one history file")
    @CommandPermissions("worldedit.history.distr")
    public void distr(Actor actor,
                      @Arg(desc = "Index in the newest-first list", def = "0") int index,
                      @Arg(desc = "Player UUID, me, or *", def = "me") String player,
                      @Arg(desc = "Radius, 0 for anywhere", def = "0") int radius,
                      @Arg(desc = "Age such as 20s, 5m, 1h, 2d", def = "0") String time) throws WorldEditException {
        DiskHistories.Entry entry = one(actor, player, radius, time, index);
        Map<String, Integer> counts = DiskHistories.distribution(entry);
        actor.printInfo(TranslatableComponent.of("fastasyncworldedit.history.distr", TextComponent.of(counts.size())));
        int shown = 0;
        for (Map.Entry<String, Integer> count : counts.entrySet()) {
            if (shown++ >= 10) {
                break;
            }
            actor.printInfo(TextComponent.of(count.getKey() + ": " + count.getValue()));
        }
    }

    @Command(name = "clear", desc = "Delete matching history files")
    @CommandPermissions("worldedit.history.clearfiles")
    public void clear(Actor actor, LocalSession session,
                      @Arg(desc = "Player UUID, me, or *", def = "me") String player,
                      @Arg(desc = "Radius, 0 for anywhere", def = "0") int radius,
                      @Arg(desc = "Age such as 20s, 5m, 1h, 2d", def = "0") String time) throws WorldEditException {
        List<DiskHistories.Entry> entries = select(actor, player, radius, time);
        for (DiskHistories.Entry entry : entries) {
            DiskHistories.delete(session, entry);
        }
        actor.printInfo(TranslatableComponent.of("fastasyncworldedit.history.cleared", TextComponent.of(entries.size())));
    }

    private void applyAll(Actor actor, String player, int radius, String time, boolean undo) throws WorldEditException {
        List<DiskHistories.Entry> entries = select(actor, player, radius, time);
        int applied = 0;
        for (int index = entries.size() - 1; index >= 0; index--) {
            if (DiskHistories.apply(actor, entries.get(index), undo)) {
                applied++;
            }
        }
        actor.printInfo(TranslatableComponent.of(
            undo ? "fastasyncworldedit.history.rolled-back" : "fastasyncworldedit.history.restored",
            TextComponent.of(applied)
        ));
    }

    private DiskHistories.Entry one(Actor actor, String player, int radius, String time, int index) throws WorldEditException {
        List<DiskHistories.Entry> entries = select(actor, player, radius, time);
        if (index < 0 || index >= entries.size()) {
            throw new StopExecutionException(TranslatableComponent.of("fastasyncworldedit.history.missing"));
        }
        return entries.get(index);
    }
}
