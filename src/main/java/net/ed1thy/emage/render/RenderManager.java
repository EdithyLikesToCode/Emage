package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.channel.Channel;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.config.ConfigManager;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RenderManager {

    private final Emage plugin;
    private final ChunkViewerTracker chunkViewerTracker;
    private final PacketSender packetSender;

    private final int maxPacketsPerTick;

    private static final int MAX_PACKETS_PER_PLAYER_PER_TICK = 100;
    private static final int MAX_BYTES_PER_PLAYER_PER_TICK = 100_000;

    private final Map<Integer, SyncGroup> activeGroups = new ConcurrentHashMap<>();
    private final Queue<SpoofTask> pendingSpoofs = new ConcurrentLinkedQueue<>();
    private final Map<User, Queue<PacketWrapper<?>>> userPacketQueues = new ConcurrentHashMap<>();

    private final Cache<Long, MapFrameUpdate> globalFrameCache;
    private final ScheduledExecutorService executorService;
    private BukkitTask visibilityTask;

    public record SpoofTask(@NotNull User user, @NotNull FrameNode node) {}

    public RenderManager(@NotNull Emage plugin, @NotNull ChunkViewerTracker chunkViewerTracker, @NotNull PacketSender packetSender, @NotNull ConfigManager configManager, @NotNull ScheduledExecutorService scheduledExecutor) {
        this.plugin = plugin;
        this.chunkViewerTracker = chunkViewerTracker;
        this.packetSender = packetSender;
        this.maxPacketsPerTick = configManager.maxPacketsPerTick;
        this.executorService = scheduledExecutor;

        long maxBytes = (long) configManager.cacheMaxMemoryMb * 1024L * 1024L;
        this.globalFrameCache = Caffeine.newBuilder()
                .maximumWeight(maxBytes)
                .weigher((Long key, MapFrameUpdate value) -> value.totalBytes())
                .expireAfterAccess(configManager.cacheExpireMinutes, TimeUnit.MINUTES)
                .removalListener((Long key, MapFrameUpdate value, RemovalCause cause) -> {
                    if (value != null) {
                        value.freeMemory();
                    }
                })
                .build();
    }

    public Cache<Long, MapFrameUpdate> getGlobalFrameCache() {
        return globalFrameCache;
    }

    public void start() {
        executorService.scheduleWithFixedDelay(this::tickAll, 20, 20, TimeUnit.MILLISECONDS);

        visibilityTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (SyncGroup group : activeGroups.values()) {
                group.updateVisibility();
            }
        }, 20L, 20L);
    }

    private void tickAll() {
        try {
            long currentTime = System.currentTimeMillis();

            int spoofSent = 0;
            SpoofTask spoofTask;
            while (spoofSent < maxPacketsPerTick && (spoofTask = pendingSpoofs.poll()) != null) {
                packetSender.spoofItemFrameMap(spoofTask.user(), spoofTask.node());
                spoofSent++;
            }

            if (!activeGroups.isEmpty()) {
                for (SyncGroup group : activeGroups.values()) {
                    if (group.shouldTick(currentTime)) {
                        group.tick(currentTime, chunkViewerTracker, this, packetSender);
                    }
                }
            }

            Iterator<Map.Entry<User, Queue<PacketWrapper<?>>>> iterator = userPacketQueues.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<User, Queue<PacketWrapper<?>>> entry = iterator.next();
                User user = entry.getKey();
                Queue<PacketWrapper<?>> queue = entry.getValue();

                Object rawChannel = user.getChannel();
                if (!(rawChannel instanceof Channel channel) || !channel.isActive()) {
                    PacketWrapper<?> dropped;
                    while ((dropped = queue.poll()) != null) {
                        if (dropped instanceof ZeroCopyMapWrapper mapPacket) {
                            mapPacket.getDelta().freeMemory();
                        }
                    }
                    iterator.remove();
                    continue;
                }

                if (queue.size() > 500) {
                    int toDrop = queue.size() - 200;
                    for (int i = 0; i < toDrop; i++) {
                        PacketWrapper<?> dropped = queue.poll();
                        if (dropped instanceof ZeroCopyMapWrapper mapPacket) {
                            mapPacket.getDelta().freeMemory();
                        }
                    }
                }

                int sent = 0;
                int sentBytes = 0;
                PacketWrapper<?> packet;

                while (sent < MAX_PACKETS_PER_PLAYER_PER_TICK && sentBytes < MAX_BYTES_PER_PLAYER_PER_TICK) {
                    packet = queue.peek();
                    if (packet == null) break;

                    int pktSize = 8192;
                    if (packet instanceof ZeroCopyMapWrapper mapPacket) {
                        pktSize = mapPacket.getDelta().packetBuf().readableBytes() + 16;
                    }

                    if (sentBytes + pktSize > MAX_BYTES_PER_PLAYER_PER_TICK && sentBytes > 0) {
                        break;
                    }

                    queue.poll();
                    user.sendPacket(packet);
                    sent++;
                    sentBytes += pktSize;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Render Thread Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void enqueuePacket(@NotNull UUID uuid, @NotNull PacketWrapper<?> packet) {
        User user = chunkViewerTracker.getUser(uuid);
        if (user == null) {
            if (packet instanceof ZeroCopyMapWrapper mapPacket) {
                mapPacket.getDelta().freeMemory();
            }
            return;
        }
        userPacketQueues.computeIfAbsent(user, k -> new ConcurrentLinkedQueue<>()).add(packet);
    }

    public void queueSpoof(@NotNull User user, @NotNull FrameNode node) {
        pendingSpoofs.add(new SpoofTask(user, node));
    }

    public void registerSyncGroup(int syncGroupID, @NotNull SyncGroup group) {
        group.updateVisibility();
        activeGroups.put(syncGroupID, group);
    }

    public void unregisterSyncGroup(int syncGroupID) {
        SyncGroup group = activeGroups.remove(syncGroupID);
        if (group != null) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, group::cleanup, 2L);
        }
    }

    public SyncGroup getSyncGroup(int syncGroupID) {
        return activeGroups.get(syncGroupID);
    }

    public void shutdown() {
        if (visibilityTask != null) visibilityTask.cancel();

        for (SyncGroup group : activeGroups.values()) {
            group.cleanup();
        }

        activeGroups.clear();
        pendingSpoofs.clear();

        for (Queue<PacketWrapper<?>> queue : userPacketQueues.values()) {
            PacketWrapper<?> packet;
            while ((packet = queue.poll()) != null) {
                if (packet instanceof ZeroCopyMapWrapper mapPacket) {
                    mapPacket.getDelta().freeMemory();
                }
            }
        }
        userPacketQueues.clear();
    }
}