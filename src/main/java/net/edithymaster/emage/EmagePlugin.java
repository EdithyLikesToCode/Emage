package net.edithymaster.emage;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.SimplePie;
import org.bstats.charts.SingleLineChart;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;
import net.edithymaster.emage.Command.EmageCommand;
import net.edithymaster.emage.Config.EmageConfig;
import net.edithymaster.emage.Manager.EmageManager;
import net.edithymaster.emage.Processing.EmageCore;
import net.edithymaster.emage.Render.GifRenderer;
import net.edithymaster.emage.Util.GifCache;
import net.edithymaster.emage.Util.UpdateChecker;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EmagePlugin extends JavaPlugin {

    private EmageManager manager;
    private UpdateChecker updateChecker;
    private EmageConfig emageConfig;
    private final Pattern hexPattern = Pattern.compile("&#([a-fA-F0-9]{6})");

    @Override
    public void onEnable() {
        int pluginID = 29638;
        Metrics metrics = new Metrics(this, pluginID);

        saveDefaultConfig();
        backfillConfig();

        getLogger().info("Initializing color system...");
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            EmageCore.initColorSystem();
            getLogger().info("Color system initialized.");
        });

        emageConfig = new EmageConfig(this);

        EmageCore.setConfig(emageConfig);

        GifCache.init(getLogger());
        GifCache.configure(
                emageConfig.getCacheMaxEntries(),
                emageConfig.getCacheMaxMemoryBytes(),
                emageConfig.getCacheExpireMs()
        );
        GifRenderer.init(this, emageConfig);

        manager = new EmageManager(this, emageConfig);
        Bukkit.getPluginManager().registerEvents(manager, this);

        var cmd = getCommand("emage");
        if (cmd != null) {
            EmageCommand exec = new EmageCommand(this, manager);
            cmd.setExecutor(exec);
            cmd.setTabCompleter(exec);
        }

        manager.loadAllMaps();
        registerCustomMetrics(metrics);

        if (getConfig().getBoolean("check-updates", true)) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(this, () -> {
                updateChecker = new UpdateChecker(this, "Ed1thy", "Emage");
            }, 100L);
        }

        getLogger().info("Emage v" + getDescription().getVersion() + " enabled!");
    }

    private void backfillConfig() {
        boolean changed = false;
        var config = getConfig();
        var defaults = config.getDefaults();

        if (defaults != null) {
            for (String key : defaults.getKeys(true)) {
                if (!config.isSet(key)) {
                    config.set(key, defaults.get(key));
                    changed = true;
                }
            }
        }

        if (changed) {
            saveConfig();
            getLogger().info("Added missing config entries from defaults.");
        }
    }

    @Override
    public void onDisable() {
        GifRenderer.stop();

        if (manager != null) {
            manager.shutdown();
        }

        EmageCore.shutdown();

        int cached = GifCache.clearCache();
        if (cached > 0) {
            getLogger().info("Cleared " + cached + " cached GIFs.");
        }

        getLogger().info("Emage disabled!");
    }

    private void registerCustomMetrics(Metrics metrics) {

        metrics.addCustomChart(new SingleLineChart("total_maps", () ->
                manager.getStats().activeMaps
        ));

        metrics.addCustomChart(new SingleLineChart("total_animations", () ->
                manager.getStats().animations
        ));

        metrics.addCustomChart(new SimplePie("uses_animations", () ->
                manager.getStats().animations > 0 ? "Yes" : "No"
        ));

        metrics.addCustomChart(new SimplePie("maps_range", () -> {
            int count = manager.getStats().activeMaps;
            if (count == 0) return "None";
            if (count <= 5) return "1-5";
            if (count <= 15) return "6-15";
            if (count <= 50) return "16-50";
            if (count <= 100) return "51-100";
            return "100+";
        }));

        metrics.addCustomChart(new SimplePie("dither_quality", () -> {
            String quality = getConfig().getString("quality.default-dither", "BALANCED");
            return quality != null ? quality.toUpperCase() : "BALANCED";
        }));

        metrics.addCustomChart(new SimplePie("adaptive_performance", () ->
                emageConfig.isAdaptivePerformance() ? "Enabled" : "Disabled"
        ));
    }

    public String getPrefix() {
        String prefix = getConfig().getString("messages.prefix");
        if (prefix == null) {
            prefix = "&#321212&lE&#3E1111&lm&#4A0F0F&la&#560E0E&lg&#620C0C&le &8&l• ";
        }
        return colorize(prefix);
    }

    private String getRawPrefix() {
        String prefix = getConfig().getString("messages.prefix");
        if (prefix == null) {
            prefix = "&#321212&lE&#3E1111&lm&#4A0F0F&la&#560E0E&lg&#620C0C&le &8&l• ";
        }
        return prefix;
    }

    public String msg(String key, String... placeholders) {
        String message = getConfig().getString("messages." + key);
        if (message == null) return ChatColor.RED + "Missing message: " + key;

        message = getRawPrefix() + message;

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            message = message.replace(placeholders[i], value);
        }

        return colorize(message);
    }

    public String msgNoPrefix(String key, String... placeholders) {
        String message = getConfig().getString("messages." + key);
        if (message == null) return ChatColor.RED + "Missing message: " + key;

        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1] != null ? placeholders[i + 1] : "";
            message = message.replace(placeholders[i], value);
        }

        return colorize(message);
    }

    public String colorize(String message) {
        Matcher matcher = hexPattern.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            try {
                String hex = matcher.group(1);
                matcher.appendReplacement(buffer, net.md_5.bungee.api.ChatColor.of("#" + hex).toString());
            } catch (Exception ignored) {}
        }
        matcher.appendTail(buffer);

        return ChatColor.translateAlternateColorCodes('&', buffer.toString());
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public EmageManager getManager() {
        return manager;
    }

    public EmageConfig getEmageConfig() {
        return emageConfig;
    }
}