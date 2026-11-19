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

package com.sk89q.worldedit.util.translation;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.common.util.concurrent.Futures;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.sk89q.worldedit.internal.util.LogManagerCompat;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.renderer.TranslatableComponentRenderer;
import com.sk89q.worldedit.util.io.ResourceLoader;
import com.sk89q.worldedit.util.io.file.ArchiveUnpacker;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Handles translations for the plugin.
 *
 * <p>
 * These should be in the following format:
 * plugin.component.message[.meta]*
 * </p>
 *
 * <p>
 * Where,
 * plugin = worldedit
 * component = The part of the plugin, eg expand
 * message = A descriptor for which message, eg, expanded
 * meta = Any extra information such as plural/singular (Can have none to infinite)
 * </p>
 */
public class TranslationManager {

    private static final Logger LOGGER = LogManagerCompat.getLogger();
    private static final Gson GSON = new GsonBuilder().create();
    private static final Type STRING_MAP_TYPE = new TypeToken<Map<String, String>>() {
    }.getType();

    public static String makeTranslationKey(String type, String id) {
        String[] parts = id.split(":", 2);
        return type + '.' + parts[0] + '.' + parts[1].replace('/', '.');
    }

    private final TranslatableComponentRenderer<Locale> friendlyComponentRenderer = TranslatableComponentRenderer.from(
        this::getTranslation
    );
    private final Table<Locale, String, MessageFormat> translationTable = Tables.newCustomTable(
        new ConcurrentHashMap<>(), ConcurrentHashMap::new
    );
    private final Map<Locale, Future<Void>> loadFutures = new HashMap<>();
    private final Set<Locale> loadedLocales = Sets.newConcurrentHashSet();
    private final Lock loadLock = new ReentrantLock();
    private final List<Consumer<Locale>> localeChangeListeners = Lists.newArrayList();
    private Locale defaultLocale = Locale.ENGLISH;

    private final ArchiveUnpacker archiveUnpacker;
    private final ResourceLoader resourceLoader;
    private final Path userProvidedFlatRoot;
    private final Path internalZipRoot;
    @Nullable
    private Path userProvidedZipRoot;

    public TranslationManager(ArchiveUnpacker archiveUnpacker, ResourceLoader resourceLoader) throws IOException {
        this.archiveUnpacker = archiveUnpacker;
        this.resourceLoader = resourceLoader;
        checkNotNull(resourceLoader);
        this.userProvidedFlatRoot = resourceLoader.getLocalResource("lang");
        this.internalZipRoot = archiveUnpacker.unpackArchive(checkNotNull(
            resourceLoader.getRootResource("lang/i18n.zip"),
            "Missing internal i18n.zip!"
        ));
    }

    private void load() throws IOException {
        Path userZip = resourceLoader.getLocalResource("lang/i18n.zip");
        Path result = null;
        if (Files.exists(userZip)) {
            result = archiveUnpacker.unpackArchive(userZip.toUri().toURL());
        }
        this.userProvidedZipRoot = result;
    }

    public void reload() {
        loadLock.lock();
        try {
            loadedLocales.clear();
            for (Future<Void> future : loadFutures.values()) {
                Futures.getUnchecked(future);
            }
            loadFutures.clear();
            translationTable.clear();
            load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            loadLock.unlock();
        }
    }

    /**
     * Adds a listener that will be notified when the locale changes.
     *
     * <p>
     * Note: this can be called multiple times with the same locale, for example if the translation manager is reloaded.
     * </p>
     *
     * @param listener a consumer that will be called with the updated locale
     * @param includePreviouslyLoaded if true, the listener will be called with all previously loaded locales immediately
     */
    public void addLocaleChangeListener(Consumer<Locale> listener, boolean includePreviouslyLoaded) {
        localeChangeListeners.add(listener);

        if (includePreviouslyLoaded) {
            // Notify the listener of all loaded locales
            for (Locale locale : loadedLocales) {
                listener.accept(locale);
            }
        }
    }

    public Component convertText(Component component, Locale locale) {
        return friendlyComponentRenderer.render(component, locale);
    }

    public void setDefaultLocale(Locale defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    private MessageFormat getTranslation(Locale locale, String key) {
        if (!loadedLocales.contains(locale)) {
            loadLocale(locale);
        }

        MessageFormat format = translationTable.get(locale, key);
        if (format == null && !defaultLocale.equals(locale)) {
            // Recurse into other options if not already at the base condition (defaultLocale)
            if (!locale.getCountry().isEmpty()) {
                // try without country modifier
                return getTranslation(Locale.of(locale.getLanguage()), key);
            }
            // otherwise, try the default locale
            return getTranslation(defaultLocale, key);
        }
        // note that this may be null in the case of the defaultLocale
        return format;
    }

    private void loadLocale(Locale locale) {
        CompletableFuture<Void> ourFuture;
        loadLock.lock();
        try {
            Future<Void> ftr = loadFutures.get(locale);
            if (ftr == null) {
                // no existing future, enter ourselves as the loader
                ourFuture = new CompletableFuture<>();
                loadFutures.put(locale, ourFuture);
            } else {
                // existing loader, await their completion first
                Futures.getUnchecked(ftr);
                return;
            }
        } finally {
            loadLock.unlock();
        }

        try {
            loadTranslations(locale);
        } catch (Exception t) {
            LOGGER.warn("Failed to load translations, locale={}", locale, t);
        } finally {
            ourFuture.complete(null);
            loadedLocales.add(locale);
        }
    }

    private void loadTranslations(Locale locale) throws IOException {
        Map<String, String> entries = new HashMap<>();

        String localePath = getLocalePath(locale);
        // From lowest priority to highest
        if (defaultLocale.equals(locale)) {
            // load internal strings, not i18n.zip
            // we need this for development and to ensure translations are at least minimally
            // working in the case of no i18n.zip update
            URL internalStrings = resourceLoader.getRootResource("lang/strings.json");
            checkNotNull(internalStrings, "Failed to load internal strings.json");
            try (InputStream in = internalStrings.openStream()) {
                putTranslationData(entries, in);
            }
            // 默认语言的 localePath 固定是 strings.json。再按语言标签读 jar，
            // 该语言自己的键才能盖过英文底表。
            loadBundledLocaleFile(entries, languageTagPath(locale));
        } else {
            // load from the internal zip for all other locales
            putTranslationData(entries, this.internalZipRoot.resolve(localePath));
            loadBundledLocaleFile(entries, localePath);
        }
        if (this.userProvidedZipRoot != null) {
            putTranslationData(entries, this.userProvidedZipRoot.resolve(localePath));
        }
        putTranslationData(entries, this.userProvidedFlatRoot.resolve(localePath));

        // Load message formats
        for (Map.Entry<String, String> entry : entries.entrySet()) {
            MessageFormat format;
            try {
                format = new MessageFormat(entry.getValue().replace("'", "''"), locale);
            } catch (IllegalArgumentException e) {
                LOGGER.warn("Failed to load translation, locale={}, key={}, value={}", locale, entry.getKey(), entry.getValue(), e);
                continue;
            }
            translationTable.put(
                locale, entry.getKey(), format
            );
        }

        // Notify listeners of locale change
        localeChangeListeners.forEach(listener -> listener.accept(locale));
    }

    private String getLocalePath(Locale locale) {
        if (defaultLocale.equals(locale)) {
            return "strings.json";
        }
        String tagged = languageTagPath(locale);
        return tagged == null ? "strings.json" : tagged;
    }

    /**
     * 返回该语言在 {@code lang/} 下的文件路径。
     * <p>
     * 路径形如 {@code zh-CN/strings.json}。语言标签为空时返回 {@code null}，
     * 避免拼出以 {@code /} 开头的资源名。
     *
     * @param locale 玩家或配置使用的语言
     * @return 相对 {@code lang/} 的路径；语言标签为空时返回 {@code null}
     */
    @Nullable
    private static String languageTagPath(Locale locale) {
        if (locale.getLanguage().isEmpty()) {
            return null;
        }
        String country = locale.getCountry().isEmpty() ? "" : "-" + locale.getCountry();
        return locale.getLanguage() + country + "/strings.json";
    }

    /**
     * 把 jar 内的语言文件合并进当前表。
     * <p>
     * 资源名是 {@code lang/} 加上 {@code localePath}。文件不存在时不改表。
     * 同名键以后读入的值为准，因此要在 i18n.zip 或英文 {@code strings.json} 之后调用。
     *
     * @param entries 正在组装的翻译表，就地写入
     * @param localePath 相对 {@code lang/} 的路径；为 {@code null} 或空白时跳过
     * @throws IOException 资源存在但读取失败时抛出
     */
    private void loadBundledLocaleFile(Map<String, String> entries, @Nullable String localePath) throws IOException {
        if (localePath == null || localePath.isBlank()) {
            return;
        }
        URL bundled = resourceLoader.getRootResource("lang/" + localePath);
        if (bundled == null) {
            return;
        }
        try (InputStream in = bundled.openStream()) {
            putTranslationData(entries, in);
        }
    }

    private void putTranslationData(Map<String, String> data, Path source) throws IOException {
        if (!Files.exists(source)) {
            return;
        }
        try (InputStream in = Files.newInputStream(source)) {
            putTranslationData(data, in);
        }
    }

    private void putTranslationData(Map<String, String> data, InputStream inputStream) throws IOException {
        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            Map<String, String> map = GSON.fromJson(reader, STRING_MAP_TYPE);
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                data.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
