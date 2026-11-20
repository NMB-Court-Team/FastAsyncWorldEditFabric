package com.sk89q.worldedit.command.argument;

import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.internal.annotation.SchematicSource;
import com.sk89q.worldedit.internal.schematic.SchematicsManager;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TextComponent;
import org.enginehub.piston.CommandManager;
import org.enginehub.piston.converter.ArgumentConverter;
import org.enginehub.piston.converter.ConversionResult;
import org.enginehub.piston.converter.SuccessfulConversion;
import org.enginehub.piston.inject.InjectedValueAccess;
import org.enginehub.piston.inject.Key;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.enginehub.piston.converter.SuggestionHelper.limitByPrefix;

/**
 * 把 `//schem load` 的参数保留成原始字符串。
 * <p>
 * 已有的原理图文件会作为补全候选。https 链接和 `url:` 不要求本地文件存在。
 */
public class SchematicSourceConverter implements ArgumentConverter<String> {
    public static void register(WorldEdit worldEdit, CommandManager commandManager) {
        commandManager.registerConverter(Key.of(String.class, SchematicSource.class), new SchematicSourceConverter(worldEdit));
    }

    private final WorldEdit worldEdit;

    private SchematicSourceConverter(WorldEdit worldEdit) {
        this.worldEdit = worldEdit;
    }

    @Override
    public Component describeAcceptableArguments() {
        return TextComponent.of("schematic file, https URL, or url:uuid");
    }

    @Override
    public List<String> getSuggestions(String input, InjectedValueAccess context) {
        SchematicsManager schematicsManager = worldEdit.getSchematicsManager();
        Path root = schematicsManager.getRoot();
        List<String> names = new ArrayList<>();
        names.add("url:");
        names.add("https://");
        schematicsManager.getSchematicPaths().stream()
            .map(path -> root.relativize(path).toString())
            .forEach(names::add);
        return limitByPrefix(names.stream(), input);
    }

    @Override
    public ConversionResult<String> convert(String argument, InjectedValueAccess context) {
        return SuccessfulConversion.fromSingle(argument);
    }
}
