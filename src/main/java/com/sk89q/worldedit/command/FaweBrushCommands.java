package com.sk89q.worldedit.command;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.command.util.CommandPermissions;
import com.sk89q.worldedit.command.util.CommandPermissionsConditionGenerator;
import com.sk89q.worldedit.entity.Player;
import net.astrorbits.fawe.brush.FaweBrushes;
import org.enginehub.piston.annotation.Command;
import org.enginehub.piston.annotation.CommandContainer;
import org.enginehub.piston.annotation.param.Arg;
import org.enginehub.piston.annotation.param.Switch;

/**
 * 上游多出来的笔刷命令。
 * <p>
 * 放置类命令把笔刷绑到手上的物品。保存把当前笔刷写成 `.jsgz`，加载按文件重新绑定，列表只列出文件。
 */
@CommandContainer(superTypes = CommandPermissionsConditionGenerator.Registration.class)
public class FaweBrushCommands {
    private final WorldEdit worldEdit;

    public FaweBrushCommands(WorldEdit worldEdit) {
        this.worldEdit = worldEdit;
    }

    @Command(name = "blendball", desc = "Blend blocks inside the brush")
    @CommandPermissions("worldedit.brush.blendball")
    public void blendball(Player player, LocalSession session,
                          @Arg(desc = "Radius", def = "5") double radius,
                          @Arg(desc = "Iterations", def = "1") int iterations) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, "blendball", radius, null, null, Integer.toString(iterations));
    }

    @Command(name = "erode", desc = "Erode and fill blocks inside the brush")
    @CommandPermissions("worldedit.brush.erode")
    public void erode(Player player, LocalSession session,
                      @Arg(desc = "Radius", def = "5") double radius,
                      @Arg(desc = "Iterations", def = "1") int iterations) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, "erode", radius, "stone", null, Integer.toString(iterations));
    }

    @Command(name = "pull", desc = "Pull terrain toward the clicked block")
    @CommandPermissions("worldedit.brush.pull")
    public void pull(Player player, LocalSession session, @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "pull", radius, null, null);
    }

    @Command(name = "circle", desc = "Draw a horizontal circle")
    @CommandPermissions("worldedit.brush.circle")
    public void circle(Player player, LocalSession session,
                       @Arg(desc = "Pattern", def = "stone") String pattern,
                       @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "circle", radius, pattern, null);
    }

    @Command(name = "recursive", desc = "Replace the connected block type")
    @CommandPermissions("worldedit.brush.recursive")
    public void recursive(Player player, LocalSession session,
                          @Arg(desc = "Pattern", def = "air") String pattern,
                          @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "recursive", radius, pattern, null);
    }

    @Command(name = "line", desc = "Draw a line between two clicks")
    @CommandPermissions("worldedit.brush.line")
    public void line(Player player, LocalSession session,
                     @Arg(desc = "Pattern", def = "stone") String pattern,
                     @Arg(desc = "Radius", def = "0") double radius) throws WorldEditException {
        simple(player, session, "line", radius, pattern, null);
    }

    @Command(name = "spline", desc = "Draw a spline; click the last point again to finish")
    @CommandPermissions("worldedit.brush.spline")
    public void spline(Player player, LocalSession session,
                       @Arg(desc = "Pattern", def = "stone") String pattern,
                       @Arg(desc = "Radius", def = "0") double radius) throws WorldEditException {
        simple(player, session, "spline", radius, pattern, null);
    }

    @Command(name = "sweep", desc = "Sweep spheres between two clicks")
    @CommandPermissions("worldedit.brush.sweep")
    public void sweep(Player player, LocalSession session,
                      @Arg(desc = "Pattern", def = "stone") String pattern,
                      @Arg(desc = "Radius", def = "2") double radius) throws WorldEditException {
        simple(player, session, "sweep", radius, pattern, null);
    }

    @Command(name = "catenary", desc = "Draw a hanging curve between two clicks")
    @CommandPermissions("worldedit.brush.catenary")
    public void catenary(Player player, LocalSession session,
                         @Arg(desc = "Pattern", def = "stone") String pattern,
                         @Arg(desc = "Radius", def = "0") double radius) throws WorldEditException {
        simple(player, session, "catenary", radius, pattern, null);
    }

    @Command(name = "surfacespline", desc = "Draw a spline along the surface")
    @CommandPermissions("worldedit.brush.surfacespline")
    public void surfacespline(Player player, LocalSession session,
                              @Arg(desc = "Pattern", def = "stone") String pattern,
                              @Arg(desc = "Radius", def = "0") double radius) throws WorldEditException {
        simple(player, session, "surfacespline", radius, pattern, null);
    }

    @Command(name = "rock", desc = "Place a cluster of spheres")
    @CommandPermissions("worldedit.brush.rock")
    public void rock(Player player, LocalSession session,
                     @Arg(desc = "Pattern", def = "stone") String pattern,
                     @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "rock", radius, pattern, null);
    }

    @Command(name = "shatter", desc = "Cut cracks through the brush")
    @CommandPermissions("worldedit.brush.shatter")
    public void shatter(Player player, LocalSession session, @Arg(desc = "Radius", def = "8") double radius) throws WorldEditException {
        simple(player, session, "shatter", radius, null, null);
    }

    @Command(name = "stencil", desc = "Place the pattern where an image is opaque")
    @CommandPermissions("worldedit.brush.stencil")
    public void stencil(Player player, LocalSession session,
                        @Arg(desc = "https image URL") String url,
                        @Arg(desc = "Pattern", def = "stone") String pattern,
                        @Arg(desc = "Radius", def = "10") double radius) throws WorldEditException {
        simple(player, session, "stencil", radius, pattern, url);
    }

    @Command(name = "image", desc = "Place colored blocks from an image")
    @CommandPermissions("worldedit.brush.image")
    public void image(Player player, LocalSession session,
                      @Arg(desc = "https image URL") String url,
                      @Arg(desc = "Radius", def = "10") double radius) throws WorldEditException {
        simple(player, session, "image", radius, null, url);
    }

    @Command(name = "surface", desc = "Paint the surface")
    @CommandPermissions("worldedit.brush.surface")
    public void surface(Player player, LocalSession session,
                        @Arg(desc = "Pattern", def = "grass_block") String pattern,
                        @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "surface", radius, pattern, null);
    }

    @Command(name = "scatter", desc = "Scatter blocks inside the brush")
    @CommandPermissions("worldedit.brush.scatter")
    public void scatter(Player player, LocalSession session,
                        @Arg(desc = "Pattern", def = "stone") String pattern,
                        @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "scatter", radius, pattern, null);
    }

    @Command(name = "populateschematic", desc = "Paste a schematic at random points")
    @CommandPermissions("worldedit.brush.populateschematic")
    public void populateschematic(Player player, LocalSession session,
                                  @Arg(desc = "Schematic file, https URL, or url:uuid") String schematic,
                                  @Arg(desc = "Radius", def = "10") double radius,
                                  @Arg(desc = "Copies per click", def = "1") int copies) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, "populateschematic", radius, null, null, schematic + "\n" + copies);
    }

    @Command(name = "layer", desc = "Stack the pattern down from the surface")
    @CommandPermissions("worldedit.brush.layer")
    public void layer(Player player, LocalSession session,
                      @Arg(desc = "Pattern", def = "stone") String pattern,
                      @Arg(desc = "Radius", def = "5") double radius,
                      @Arg(desc = "Layers", def = "1") int layers) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, "layer", radius, pattern, null, Integer.toString(layers));
    }

    @Command(name = "scattercommand", desc = "Run commands at random points")
    @CommandPermissions("worldedit.brush.scattercommand")
    public void scattercommand(Player player, LocalSession session,
                               @Arg(desc = "Commands separated by ;") String commands,
                               @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, "scattercommand", radius, null, null, commands);
    }

    @Command(name = "height", desc = "Paint a noisy height")
    @CommandPermissions("worldedit.brush.height")
    public void height(Player player, LocalSession session,
                       @Arg(desc = "Pattern", def = "stone") String pattern,
                       @Arg(desc = "Radius", def = "8") double radius) throws WorldEditException {
        simple(player, session, "height", radius, pattern, null);
    }

    @Command(name = "cliff", desc = "Fill steep terrain")
    @CommandPermissions("worldedit.brush.cliff")
    public void cliff(Player player, LocalSession session,
                      @Arg(desc = "Pattern", def = "stone") String pattern,
                      @Arg(desc = "Radius", def = "8") double radius) throws WorldEditException {
        simple(player, session, "cliff", radius, pattern, null);
    }

    @Command(name = "flatten", desc = "Flatten to the clicked height")
    @CommandPermissions("worldedit.brush.flatten")
    public void flatten(Player player, LocalSession session,
                        @Arg(desc = "Pattern", def = "grass_block") String pattern,
                        @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "flatten", radius, pattern, null);
    }

    @Command(name = "copypaste", desc = "Copy on the first click and paste on the next")
    @CommandPermissions("worldedit.brush.copypaste")
    public void copypaste(Player player, LocalSession session, @Arg(desc = "Radius", def = "5") double radius) throws WorldEditException {
        simple(player, session, "copypaste", radius, null, null);
    }

    @Command(name = "command", desc = "Run commands at the clicked block")
    @CommandPermissions("worldedit.brush.command")
    public void command(Player player, LocalSession session,
                        @Arg(desc = "Commands separated by ;") String commands,
                        @Arg(desc = "Radius", def = "0") double radius) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, "command", radius, null, null, commands);
    }

    @Command(name = "savebrush", desc = "Save the current brush as a .jsgz file")
    @CommandPermissions("worldedit.brush.save")
    public void savebrush(Player player, LocalSession session,
                          @Arg(desc = "Name") String name,
                          @Switch(name = 'g', desc = "Save to the global folder") boolean global) {
        FaweBrushes.save(player, session, name, global);
    }

    @Command(name = "loadbrush", desc = "Load a saved .jsgz brush")
    @CommandPermissions("worldedit.brush.load")
    public void loadbrush(Player player, LocalSession session, @Arg(desc = "Name") String name) {
        FaweBrushes.load(player, session, name);
    }

    @Command(name = "listbrush", desc = "List saved brushes")
    @CommandPermissions("worldedit.brush.list")
    public void listbrush(Player player) {
        FaweBrushes.list(player);
    }

    private void simple(Player player, LocalSession session, String kind, double radius, String pattern, String extra) throws WorldEditException {
        radius(radius);
        FaweBrushes.bind(player, session, kind, radius, pattern, null, extra);
    }

    private void radius(double radius) throws WorldEditException {
        worldEdit.checkMaxBrushRadius(radius);
    }
}
