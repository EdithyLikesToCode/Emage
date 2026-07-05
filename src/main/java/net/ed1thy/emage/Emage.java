package net.ed1thy.emage;

import org.bstats.bukkit.Metrics;
import net.ed1thy.emage.command.CommandRegistry;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.config.MessageManager;
import net.ed1thy.emage.listener.ChunkTrackerListener;
import net.ed1thy.emage.listener.FrameInteractListener;
import net.ed1thy.emage.listener.PersistenceListener;
import net.ed1thy.emage.listener.UpdateNotifyListener;
import net.ed1thy.emage.network.ImageDownloader;
import net.ed1thy.emage.network.DnsResolver;
import net.ed1thy.emage.network.EHttpClient;
import net.ed1thy.emage.network.UpdateChecker;
import net.ed1thy.emage.processing.ColorPalette;
import net.ed1thy.emage.processing.ImagePipeline;
import net.ed1thy.emage.render.ChunkViewerTracker;
import net.ed1thy.emage.render.PacketSender;
import net.ed1thy.emage.render.RenderManager;
import net.ed1thy.emage.storage.DatabaseManager;
import net.ed1thy.emage.storage.FlatFileStorage;
import net.ed1thy.emage.storage.MapMetadataRepository;
import net.ed1thy.emage.storage.SchemaInitializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ScheduledExecutorService;

public class Emage extends JavaPlugin {

    private DatabaseManager databaseManager;
    private RenderManager renderManager;
    private FlatFileStorage flatFileStorage;
    private MapMetadataRepository mapMetadataRepository;
    private ColorPalette colorLUT;
    private ImagePipeline imagePipeline;
    private CommandRegistry commandRegistry;

    private ExecutorService virtualThreadExecutor;
    private ExecutorService boundedIoExecutor;
    private ScheduledExecutorService scheduledExecutor;
    private ForkJoinPool computePool;

    @Override
    public void onEnable() {
        int pluginID = 29638;
        Metrics metrics = new Metrics(this, pluginID);

        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.boundedIoExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors() / 2), r -> {
            Thread t = new Thread(r, "Emage-IO-Worker");
            t.setDaemon(true);
            return t;
        });
        this.scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Emage-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.computePool = new ForkJoinPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));

        ConfigManager configManager = new ConfigManager(this);
        configManager.load();

        MessageManager messageManager = new MessageManager(configManager);
        messageManager.load();

        this.databaseManager = new DatabaseManager(this, configManager);
        new SchemaInitializer(databaseManager).initialize();

        this.mapMetadataRepository = new MapMetadataRepository(databaseManager, boundedIoExecutor);
        this.flatFileStorage = new FlatFileStorage(this, boundedIoExecutor);

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            try {
                java.util.Set<Integer> activeIds = mapMetadataRepository.getAllSyncGroupIDs();

                int brokenDbEntries = 0;
                for (int id : activeIds) {
                    if (!flatFileStorage.groupExists(id)) {
                        mapMetadataRepository.deleteSyncGroup(id);
                        brokenDbEntries++;
                    }
                }

                int deletedOrphans = flatFileStorage.cleanupOrphanedFrameData(activeIds);

                if (brokenDbEntries > 0 || deletedOrphans > 0) {
                    getLogger().info(String.format("Removed %d broken DB entries and %d orphaned frame data records.", brokenDbEntries, deletedOrphans));
                }
            } catch (Exception e) {
                getLogger().warning("Failed to run Storage Sweeper: " + e.getMessage());
            }
        });

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            databaseManager.runWalCheckpoint();
        }, 20L * 60 * 60, 20L * 60 * 60);

        DnsResolver dnsChecker = new DnsResolver(configManager);
        EHttpClient httpClient = new EHttpClient(configManager, virtualThreadExecutor);
        ImageDownloader imageDownloader = new ImageDownloader(httpClient, dnsChecker, configManager);

        this.colorLUT = new ColorPalette(computePool);
        colorLUT.generateLUT().thenRun(() -> getLogger().info("Color LUT successfully generated."));

        this.imagePipeline = new ImagePipeline(colorLUT, flatFileStorage, virtualThreadExecutor, computePool);

        ChunkViewerTracker viewerTracker = new ChunkViewerTracker();
        PacketSender packetSender = new PacketSender();
        this.renderManager = new RenderManager(this, viewerTracker, packetSender, configManager, scheduledExecutor);

        renderManager.start();

        for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
            viewerTracker.registerPlayer(player);
        }

        ChunkTrackerListener chunkListener = new ChunkTrackerListener(viewerTracker, renderManager);
        FrameInteractListener interactListener = new FrameInteractListener(this, messageManager);

        getServer().getPluginManager().registerEvents(chunkListener, this);
        getServer().getPluginManager().registerEvents(interactListener, this);
        PersistenceListener persistenceListener = new PersistenceListener(this, interactListener, mapMetadataRepository, renderManager, chunkListener, configManager, flatFileStorage, virtualThreadExecutor);
        getServer().getPluginManager().registerEvents(persistenceListener, this);

        this.commandRegistry = new CommandRegistry(
                this, configManager, messageManager, imageDownloader, mapMetadataRepository,
                imagePipeline, flatFileStorage, renderManager, packetSender, chunkListener,
                interactListener, virtualThreadExecutor
        );
        commandRegistry.registerCommands();

        if (configManager.checkForUpdates) {
            UpdateChecker updateChecker = new UpdateChecker(this, messageManager);

            getServer().getScheduler().runTaskTimerAsynchronously(this, updateChecker::checkForUpdates, 0L, 20L * 60 * 30);

            getServer().getPluginManager().registerEvents(new UpdateNotifyListener(updateChecker), this);
        } else {
            getLogger().warning("=======================================================");
            getLogger().warning("Update checking is DISABLED in the config.");
            getLogger().warning("This is NOT recommended. You will not be notified");
            getLogger().warning("of critical bug fixes or performance improvements!");
            getLogger().warning("=======================================================");
        }

        getLogger().info("Emage enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Emage shutting down..");

        if (commandRegistry != null) commandRegistry.shutdown();
        if (imagePipeline != null) imagePipeline.shutdown();
        if (colorLUT != null) colorLUT.shutdown();

        if (renderManager != null) {
            renderManager.shutdown();
        }

        if (mapMetadataRepository != null) mapMetadataRepository.shutdown();
        if (flatFileStorage != null) flatFileStorage.shutdown();

        if (scheduledExecutor != null) scheduledExecutor.shutdownNow();
        if (boundedIoExecutor != null) boundedIoExecutor.shutdownNow();
        if (virtualThreadExecutor != null) virtualThreadExecutor.shutdownNow();
        if (computePool != null) computePool.shutdownNow();

        if (databaseManager != null) {
            databaseManager.closePool();
        }
    }
}