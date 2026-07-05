package net.ed1thy.emage.render;

import com.github.benmanes.caffeine.cache.Cache;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.FrameNode;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.model.MapMetadata;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;

public class SyncGroup {

    private final MapMetadata metadata;
    private final List<FrameNode> nodes;
    private final FlatFileStorage flatFileStorage;
    private final Cache<Long, MapFrameUpdate> frameCache;
    private final ExecutorService vtExecutor;

    private int currentFrameIndex = 0;
    private long lastTickTime = 0;

    private final Map<Integer, MapFrameUpdate> baseFrames = new ConcurrentHashMap<>();
    private final Set<Integer> loadingFrames = ConcurrentHashMap.newKeySet();

    private final Set<UUID> initializedUsers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> visiblePlayers = ConcurrentHashMap.newKeySet();

    private final Set<UUID> pendingVisibilityChecks = ConcurrentHashMap.newKeySet();

    private final List<Location> centerLocs = new CopyOnWriteArrayList<>();
    private volatile World world;

    private int[] frameDelays;

    private volatile double minX, minY, minZ, maxX, maxY, maxZ;

    public SyncGroup(@NotNull MapMetadata metadata, @NotNull List<FrameNode> nodes,
                     @NotNull FlatFileStorage flatFileStorage, @NotNull Cache<Long, MapFrameUpdate> frameCache,
                     @NotNull ExecutorService vtExecutor, @Nullable Map<Integer, MapFrameUpdate> preloadedBaseFrames) {
        this.metadata = metadata;
        this.nodes = nodes;
        this.flatFileStorage = flatFileStorage;
        this.frameCache = frameCache;
        this.vtExecutor = vtExecutor;

        if (preloadedBaseFrames != null) {
            baseFrames.putAll(preloadedBaseFrames);
            for (Map.Entry<Integer, MapFrameUpdate> entry : preloadedBaseFrames.entrySet()) {
                long key = (0L << 32) | (entry.getKey() & 0xFFFFFFFFL);
                frameCache.put(key, entry.getValue());
            }
        }

        CompletableFuture.runAsync(() -> {
            try {
                if (preloadedBaseFrames == null) {
                    Map<Integer, MapFrameUpdate> f0Bundle = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), 0);
                    baseFrames.putAll(f0Bundle);
                }

                frameDelays = flatFileStorage.loadFrameDelays(metadata.syncGroupID());
                if (frameDelays == null || frameDelays.length == 0) {
                    frameDelays = new int[] { metadata.delayMs() };
                }
            } catch (Exception ignored) {}
        }, vtExecutor);

        if (frameDelays == null) {
            frameDelays = new int[] { metadata.delayMs() };
        }

        if (!nodes.isEmpty()) {
            this.world = Bukkit.getWorld(nodes.get(0).getWorldUUID());
            addNewWallBounds(nodes);
        }
    }

    public void addNewWall(List<FrameNode> newNodes) {
        this.nodes.addAll(newNodes);
        if (this.world == null && !newNodes.isEmpty()) {
            this.world = Bukkit.getWorld(newNodes.get(0).getWorldUUID());
        }
        addNewWallBounds(newNodes);
    }

    private void addNewWallBounds(List<FrameNode> wallNodes) {
        if (wallNodes.isEmpty() || this.world == null) return;

        double cMinX = Double.MAX_VALUE, cMinY = Double.MAX_VALUE, cMinZ = Double.MAX_VALUE;
        double cMaxX = -Double.MAX_VALUE, cMaxY = -Double.MAX_VALUE, cMaxZ = -Double.MAX_VALUE;

        for (FrameNode n : wallNodes) {
            cMinX = Math.min(cMinX, n.getBlockX()); cMinY = Math.min(cMinY, n.getBlockY()); cMinZ = Math.min(cMinZ, n.getBlockZ());
            cMaxX = Math.max(cMaxX, n.getBlockX() + 1.0); cMaxY = Math.max(cMaxY, n.getBlockY() + 1.0); cMaxZ = Math.max(cMaxZ, n.getBlockZ() + 1.0);
        }

        if (centerLocs.isEmpty()) {
            this.minX = cMinX; this.minY = cMinY; this.minZ = cMinZ;
            this.maxX = cMaxX; this.maxY = cMaxY; this.maxZ = cMaxZ;
        } else {
            this.minX = Math.min(this.minX, cMinX); this.minY = Math.min(this.minY, cMinY); this.minZ = Math.min(this.minZ, cMinZ);
            this.maxX = Math.max(this.maxX, cMaxX); this.maxY = Math.max(this.maxY, cMaxY); this.maxZ = Math.max(this.maxZ, cMaxZ);
        }

        Location center = new Location(world, (cMinX + cMaxX) / 2.0, (cMinY + cMaxY) / 2.0, (cMinZ + cMaxZ) / 2.0);
        this.centerLocs.add(center);
    }

    public void updateVisibility() {
        if (world == null || centerLocs.isEmpty()) return;

        for (Player player : world.getPlayers()) {
            Location pLoc = player.getLocation();
            if (pLoc.getX() < minX - 32 || pLoc.getX() > maxX + 32 ||
                    pLoc.getY() < minY - 32 || pLoc.getY() > maxY + 32 ||
                    pLoc.getZ() < minZ - 32 || pLoc.getZ() > maxZ + 32) {
                continue;
            }

            UUID uuid = player.getUniqueId();
            if (pendingVisibilityChecks.contains(uuid)) continue;

            boolean inRange = false;
            for (Location center : centerLocs) {
                if (center.distanceSquared(pLoc) <= 1024.0) {
                    inRange = true;
                    break;
                }
            }
            if (!inRange) continue;

            Location eyeLoc = player.getEyeLocation().clone();
            Vector lookDir = eyeLoc.getDirection().clone();

            pendingVisibilityChecks.add(uuid);

            CompletableFuture.runAsync(() -> {
                boolean canSee = calculateVisibilityAsync(eyeLoc, lookDir);

                if (canSee) {
                    visiblePlayers.add(uuid);
                } else {
                    visiblePlayers.remove(uuid);
                    initializedUsers.remove(uuid);
                }

                pendingVisibilityChecks.remove(uuid);
            }, vtExecutor);
        }

        initializedUsers.removeIf(uuid -> !visiblePlayers.contains(uuid) && Bukkit.getPlayer(uuid) == null);
    }

    private boolean calculateVisibilityAsync(Location eyeLoc, Vector lookDir) {
        Vector eye = eyeLoc.toVector();

        Vector[] corners = new Vector[] {
                new Vector(minX, minY, minZ),
                new Vector(maxX, minY, minZ),
                new Vector(minX, maxY, minZ),
                new Vector(maxX, maxY, minZ),
                new Vector(minX, minY, maxZ),
                new Vector(maxX, minY, maxZ),
                new Vector(minX, maxY, maxZ),
                new Vector(maxX, maxY, maxZ)
        };

        for (Vector corner : corners) {
            Vector toCorner = corner.clone().subtract(eye);
            double distSq = toCorner.lengthSquared();

            if (distSq < 9.0) return true;

            if (distSq > 1024.0) continue;

            toCorner.normalize();
            double dot = toCorner.dot(lookDir);

            if (dot > 0.0) {
                return true;
            }
        }

        return false;
    }

    public boolean shouldTick(long currentTimeMillis) {
        if (!metadata.isAnimated()) {
            return (currentTimeMillis - lastTickTime) >= 1000;
        }
        int delay = (frameDelays != null && currentFrameIndex < frameDelays.length) ? frameDelays[currentFrameIndex] : metadata.delayMs();
        if (delay <= 0) delay = metadata.delayMs();
        return (currentTimeMillis - lastTickTime) >= delay;
    }

    public void tick(long currentTimeMillis, @NotNull ChunkViewerTracker tracker, @NotNull RenderManager renderManager, @NotNull PacketSender sender) {
        if (visiblePlayers.isEmpty()) return;

        boolean missingCurrent = false;
        for (FrameNode node : nodes) {
            if (frameCache.getIfPresent(((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL)) == null) {
                missingCurrent = true; break;
            }
        }

        if (missingCurrent) {
            if (!loadingFrames.contains(currentFrameIndex)) {
                loadingFrames.add(currentFrameIndex);
                final int cFrame = currentFrameIndex;
                CompletableFuture.runAsync(() -> {
                    try {
                        Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), cFrame);
                        for (FrameNode node : nodes) {
                            long key = ((long) cFrame << 32) | (node.getMapID() & 0xFFFFFFFFL);
                            MapFrameUpdate update = bundled.get(node.getMapID());
                            frameCache.put(key, update != null ? update : new MapFrameUpdate(new DeltaFrame[0]));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        loadingFrames.remove(cFrame);
                    }
                }, vtExecutor);
            }
            return;
        }

        Set<UUID> newlyInitialized = new HashSet<>();

        for (FrameNode node : nodes) {
            long cacheKey = ((long) currentFrameIndex << 32) | (node.getMapID() & 0xFFFFFFFFL);
            MapFrameUpdate update = frameCache.getIfPresent(cacheKey);

            if (update != null) {
                for (UUID uuid : visiblePlayers) {
                    boolean isNewUser = !initializedUsers.contains(uuid);

                    if (isNewUser && currentFrameIndex != 0) {
                        MapFrameUpdate base = baseFrames.get(node.getMapID());
                        if (base != null) {
                            for (DeltaFrame subChunk : base.parts()) {
                                renderManager.enqueuePacket(uuid, sender.createMapPacket(subChunk));
                            }
                        }
                    }

                    if (isNewUser || metadata.isAnimated()) {
                        for (DeltaFrame subChunk : update.parts()) {
                            renderManager.enqueuePacket(uuid, sender.createMapPacket(subChunk));
                        }
                    }
                    newlyInitialized.add(uuid);
                }
            }
        }

        initializedUsers.addAll(newlyInitialized);

        lastTickTime = currentTimeMillis;

        if (metadata.isAnimated()) {
            currentFrameIndex++;
            if (currentFrameIndex >= metadata.totalFrames()) {
                currentFrameIndex = 0;
            }

            int nextFrame = currentFrameIndex;
            boolean missingNext = false;
            for (FrameNode node : nodes) {
                if (frameCache.getIfPresent(((long) nextFrame << 32) | (node.getMapID() & 0xFFFFFFFFL)) == null) {
                    missingNext = true; break;
                }
            }

            if (missingNext && !loadingFrames.contains(nextFrame)) {
                loadingFrames.add(nextFrame);
                final int nxtFrame = nextFrame;
                CompletableFuture.runAsync(() -> {
                    try {
                        Map<Integer, MapFrameUpdate> bundled = flatFileStorage.loadBundledFrame(metadata.syncGroupID(), nxtFrame);
                        for (FrameNode node : nodes) {
                            long key = ((long) nxtFrame << 32) | (node.getMapID() & 0xFFFFFFFFL);
                            MapFrameUpdate update = bundled.get(node.getMapID());
                            frameCache.put(key, update != null ? update : new MapFrameUpdate(new DeltaFrame[0]));
                        }
                    } catch (Exception ignored) {
                    } finally {
                        loadingFrames.remove(nxtFrame);
                    }
                }, vtExecutor);
            }
        } else {
            currentFrameIndex = 0;
        }
    }

    public List<FrameNode> getNodes() {
        return nodes;
    }

    public void cleanup() {
        for (MapFrameUpdate update : baseFrames.values()) {
            update.freeMemory();
        }
        baseFrames.clear();

        for (FrameNode node : nodes) {
            for (int i = 0; i < metadata.totalFrames(); i++) {
                long key = ((long) i << 32) | (node.getMapID() & 0xFFFFFFFFL);
                frameCache.invalidate(key);
            }
        }
    }
}