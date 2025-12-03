package org.flowerion.emage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class GifRenderer extends MapRenderer {

    private static final Map<Long, SyncGroup> SYNC_GROUPS = new ConcurrentHashMap<>();
    private static final Map<Integer, Location> MAP_LOCATIONS = new ConcurrentHashMap<>();

    private static final Map<UUID, Set<Integer>> PLAYER_VISIBLE_MAPS = new ConcurrentHashMap<>();
    private static final Map<Integer, long[]> MAP_LAST_SENT = new ConcurrentHashMap<>();

    private static volatile boolean running = false;
    private static JavaPlugin plugin;
    private static EmageConfig config;

    private static int tickCounter = 0;
    private static final int LOCATION_UPDATE_INTERVAL = 400;
    private static final int VISIBILITY_UPDATE_INTERVAL = 100;
    private static final int MIN_SEND_INTERVAL_MS = 40;

    private static final AtomicInteger ID_COUNTER = new AtomicInteger(0);

    private final int id;
    private final long syncId;
    private final byte[][] frames;
    private final int frameCount;
    private final long totalDuration;
    private final long[] frameEndTimes;

    private volatile MapView mapView;
    private volatile int lastRenderedFrame = -1;
    private volatile boolean dirty = true;

    private static class SyncGroup {
        final long syncId;
        final Set<GifRenderer> renderers = ConcurrentHashMap.newKeySet();
        final long[] frameEndTimes;
        final long totalDuration;
        final int frameCount;

        volatile long startTime;
        volatile int currentFrame = 0;
        volatile boolean frameChanged = false;

        private int consecutiveSkips = 0;
        private static final int MAX_SKIPS_BEFORE_THROTTLE = 5;
        private int throttleMultiplier = 1;

        SyncGroup(long syncId, List<Integer> delays) {
            this.syncId = syncId;
            this.startTime = System.currentTimeMillis();
            this.frameCount = delays != null ? delays.size() : 0;

            if (frameCount == 0) {
                this.frameEndTimes = new long[0];
                this.totalDuration = 1;
                return;
            }

            this.frameEndTimes = new long[frameCount];
            long cumulative = 0;
            for (int i = 0; i < frameCount; i++) {
                int delay = (delays != null && i < delays.size()) ? delays.get(i) : 100;
                cumulative += Math.max(20, delay);
                frameEndTimes[i] = cumulative;
            }
            this.totalDuration = Math.max(1, cumulative);
        }

        boolean update(long now) {
            if (frameCount <= 1) return false;

            long elapsed = now - startTime;
            long cyclePos = elapsed % totalDuration;

            int newFrame = findFrame(cyclePos);

            if (newFrame != currentFrame) {
                int expectedNext = (currentFrame + 1) % frameCount;
                if (newFrame != expectedNext) {
                    consecutiveSkips++;
                    if (consecutiveSkips > MAX_SKIPS_BEFORE_THROTTLE) {
                        throttleMultiplier = Math.min(4, throttleMultiplier + 1);
                    }
                } else {
                    consecutiveSkips = 0;
                    throttleMultiplier = Math.max(1, throttleMultiplier - 1);
                }

                currentFrame = newFrame;
                frameChanged = true;
                return true;
            }
            return false;
        }

        private int findFrame(long cyclePos) {
            if (frameCount <= 8) {
                for (int i = 0; i < frameCount; i++) {
                    if (cyclePos < frameEndTimes[i]) return i;
                }
                return frameCount - 1;
            }

            int low = 0, high = frameCount - 1;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (frameEndTimes[mid] <= cyclePos) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        int getThrottleMultiplier() {
            return throttleMultiplier;
        }

        void reset() {
            startTime = System.currentTimeMillis();
            currentFrame = 0;
            frameChanged = true;
            consecutiveSkips = 0;
            throttleMultiplier = 1;
        }
    }

    public static void init(JavaPlugin pl, EmageConfig cfg) {
        plugin = pl;
        config = cfg;

        if (running) return;
        running = true;

        Bukkit.getScheduler().runTaskTimer(plugin, GifRenderer::onTick, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, GifRenderer::updateMapLocationsSync,
                LOCATION_UPDATE_INTERVAL, LOCATION_UPDATE_INTERVAL);
        Bukkit.getScheduler().runTaskTimer(plugin, GifRenderer::updatePlayerVisibility,
                VISIBILITY_UPDATE_INTERVAL, VISIBILITY_UPDATE_INTERVAL);

    }

    public static void stop() {
        running = false;
        SYNC_GROUPS.clear();
        MAP_LOCATIONS.clear();
        PLAYER_VISIBLE_MAPS.clear();
        MAP_LAST_SENT.clear();
        tickCounter = 0;
    }

    public static void resetSyncTime(long syncId) {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) group.reset();
    }

    public static void registerMapLocation(int mapId, Location location) {
        if (location != null) {
            MAP_LOCATIONS.put(mapId, location.clone());
        }
    }

    public static int getActiveCount() {
        int count = 0;
        for (SyncGroup group : SYNC_GROUPS.values()) {
            count += group.renderers.size();
        }
        return count;
    }

    private static void onTick() {
        if (!running || SYNC_GROUPS.isEmpty()) return;

        tickCounter++;
        long now = System.currentTimeMillis();

        List<SyncGroup> dirtyGroups = null;

        for (SyncGroup group : SYNC_GROUPS.values()) {
            if (group.update(now)) {
                if (dirtyGroups == null) dirtyGroups = new ArrayList<>();
                dirtyGroups.add(group);

                for (GifRenderer renderer : group.renderers) {
                    renderer.dirty = true;
                }
            }
        }

        if (dirtyGroups == null) return;

        int sendInterval = calculateSendInterval();
        if (tickCounter % sendInterval != 0) return;

        List<MapUpdateInfo> updates = new ArrayList<>();

        for (SyncGroup group : dirtyGroups) {
            if (!group.frameChanged) continue;
            group.frameChanged = false;

            int throttle = group.getThrottleMultiplier();

            for (GifRenderer renderer : group.renderers) {
                if (renderer.mapView == null || !renderer.dirty) continue;

                @SuppressWarnings("deprecation")
                int mapId = renderer.mapView.getId();
                Location loc = MAP_LOCATIONS.get(mapId);

                updates.add(new MapUpdateInfo(renderer.mapView, mapId, loc, throttle));
            }
        }

        if (!updates.isEmpty()) {
            sendMapUpdates(updates, now);
        }
    }

    private static int calculateSendInterval() {
        int playerCount = Bukkit.getOnlinePlayers().size();
        int mapCount = getActiveCount();

        if (playerCount == 0) return 10;
        if (mapCount > 100 || playerCount > 50) return 4;
        if (mapCount > 50 || playerCount > 20) return 3;
        return 2;
    }

    private static class MapUpdateInfo {
        final MapView view;
        final int mapId;
        final Location location;
        final int throttle;

        MapUpdateInfo(MapView view, int mapId, Location location, int throttle) {
            this.view = view;
            this.mapId = mapId;
            this.location = location;
            this.throttle = throttle;
        }
    }

    private static void sendMapUpdates(List<MapUpdateInfo> updates, long now) {
        Collection<? extends Player> players = Bukkit.getOnlinePlayers();
        if (players.isEmpty()) return;

        int renderDistance = config != null ? config.getRenderDistance() : 48;
        int renderDistSq = renderDistance * renderDistance;

        List<PlayerData> playerDataList = new ArrayList<>(players.size());
        for (Player p : players) {
            if (!p.isOnline()) continue;

            try {
                Location loc = p.getLocation();
                Set<Integer> visible = PLAYER_VISIBLE_MAPS.get(p.getUniqueId());
                playerDataList.add(new PlayerData(p, loc, visible));
            } catch (Exception ignored) {}
        }

        if (playerDataList.isEmpty()) return;

        int maxUpdates = Math.max(10, 100 / Math.max(1, playerDataList.size()));
        int updatesSent = 0;

        for (MapUpdateInfo update : updates) {
            if (updatesSent >= maxUpdates) break;

            long[] lastSent = MAP_LAST_SENT.computeIfAbsent(update.mapId,
                    k -> new long[playerDataList.size() + 10]);

            boolean sentAny = false;

            for (int i = 0; i < playerDataList.size(); i++) {
                PlayerData pd = playerDataList.get(i);

                int effectiveInterval = MIN_SEND_INTERVAL_MS * update.throttle;
                if (i < lastSent.length && now - lastSent[i] < effectiveInterval) {
                    continue;
                }

                if (pd.visibleMaps != null && !pd.visibleMaps.contains(update.mapId)) {
                    continue;
                }

                if (update.location != null) {
                    if (!update.location.getWorld().equals(pd.location.getWorld())) {
                        continue;
                    }

                    double dx = pd.location.getX() - update.location.getX();
                    double dy = pd.location.getY() - update.location.getY();
                    double dz = pd.location.getZ() - update.location.getZ();

                    if (dx * dx + dy * dy + dz * dz > renderDistSq) {
                        continue;
                    }
                }

                try {
                    pd.player.sendMap(update.view);
                    if (i < lastSent.length) {
                        lastSent[i] = now;
                    }
                    sentAny = true;
                } catch (Exception ignored) {}
            }

            if (sentAny) updatesSent++;
        }
    }

    private static class PlayerData {
        final Player player;
        final Location location;
        final Set<Integer> visibleMaps;

        PlayerData(Player player, Location location, Set<Integer> visibleMaps) {
            this.player = player;
            this.location = location;
            this.visibleMaps = visibleMaps;
        }
    }

    private static void updatePlayerVisibility() {
        if (!running) return;

        int renderDistance = config != null ? config.getRenderDistance() : 48;
        int renderDistSq = renderDistance * renderDistance;

        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                Location playerLoc = player.getLocation();
                Set<Integer> visible = new HashSet<>();

                for (Map.Entry<Integer, Location> entry : MAP_LOCATIONS.entrySet()) {
                    Location mapLoc = entry.getValue();

                    if (!playerLoc.getWorld().equals(mapLoc.getWorld())) continue;

                    double dx = playerLoc.getX() - mapLoc.getX();
                    double dy = playerLoc.getY() - mapLoc.getY();
                    double dz = playerLoc.getZ() - mapLoc.getZ();

                    if (dx * dx + dy * dy + dz * dz <= renderDistSq) {
                        visible.add(entry.getKey());
                    }
                }

                PLAYER_VISIBLE_MAPS.put(player.getUniqueId(), visible);
            } catch (Exception ignored) {}
        }

        PLAYER_VISIBLE_MAPS.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private static void updateMapLocationsSync() {
        if (!running) return;

        Set<Integer> animatedMapIds = new HashSet<>();
        for (SyncGroup group : SYNC_GROUPS.values()) {
            for (GifRenderer renderer : group.renderers) {
                if (renderer.mapView != null) {
                    @SuppressWarnings("deprecation")
                    int mapId = renderer.mapView.getId();
                    animatedMapIds.add(mapId);
                }
            }
        }

        if (animatedMapIds.isEmpty()) return;

        Map<Integer, Location> newLocations = new HashMap<>();

        for (World world : Bukkit.getWorlds()) {
            try {
                for (Entity entity : world.getEntities()) {
                    if (!(entity instanceof ItemFrame frame)) continue;

                    ItemStack item = frame.getItem();
                    if (item == null || item.getType() != Material.FILLED_MAP) continue;

                    try {
                        MapMeta meta = (MapMeta) item.getItemMeta();
                        if (meta != null && meta.hasMapView()) {
                            MapView view = meta.getMapView();
                            if (view != null) {
                                @SuppressWarnings("deprecation")
                                int mapId = view.getId();
                                if (animatedMapIds.contains(mapId)) {
                                    newLocations.put(mapId, frame.getLocation().clone());
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }

        MAP_LOCATIONS.clear();
        MAP_LOCATIONS.putAll(newLocations);
    }

    public GifRenderer(List<byte[]> frameList, List<Integer> delays, long syncId) {
        super(false);

        this.id = ID_COUNTER.incrementAndGet();
        this.syncId = syncId;
        this.frameCount = frameList.size();

        this.frames = new byte[frameCount][];
        for (int i = 0; i < frameCount; i++) {
            byte[] src = frameList.get(i);
            this.frames[i] = new byte[src.length];
            System.arraycopy(src, 0, this.frames[i], 0, src.length);
        }

        this.frameEndTimes = new long[frameCount];
        long cumulative = 0;
        for (int i = 0; i < frameCount; i++) {
            int delay = (delays != null && i < delays.size()) ? delays.get(i) : 100;
            cumulative += Math.max(20, delay);
            this.frameEndTimes[i] = cumulative;
        }
        this.totalDuration = Math.max(1, cumulative);

        SyncGroup group = SYNC_GROUPS.computeIfAbsent(syncId, k -> new SyncGroup(syncId, delays));
        group.renderers.add(this);

        if (config != null) {
            config.incrementAnimationCount();
        }
    }

    public GifRenderer(List<byte[]> frames, List<Integer> delays) {
        this(frames, delays, System.currentTimeMillis());
    }

    public GifRenderer(List<byte[]> frames, int delayMs) {
        this(frames, createDelayList(frames.size(), delayMs), System.currentTimeMillis());
    }

    public GifRenderer(List<byte[]> frames, int delayMs, long syncId) {
        this(frames, createDelayList(frames.size(), delayMs), syncId);
    }

    private static List<Integer> createDelayList(int size, int delay) {
        List<Integer> delays = new ArrayList<>(size);
        int clamped = Math.max(20, delay);
        for (int i = 0; i < size; i++) {
            delays.add(clamped);
        }
        return delays;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (this.mapView == null) {
            this.mapView = map;
        }

        SyncGroup group = SYNC_GROUPS.get(syncId);
        int frameIndex = (group != null) ? group.currentFrame : 0;

        if (frameIndex < 0 || frameIndex >= frameCount) {
            frameIndex = 0;
        }

        if (frameIndex == lastRenderedFrame && !dirty) {
            return;
        }

        byte[] data = frames[frameIndex];
        if (data == null || data.length < EmageCore.MAP_SIZE) return;

        for (int y = 0; y < 128; y++) {
            int offset = y << 7;
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, data[offset + x]);
            }
        }

        lastRenderedFrame = frameIndex;
        dirty = false;
    }

    public void remove() {
        SyncGroup group = SYNC_GROUPS.get(syncId);
        if (group != null) {
            group.renderers.remove(this);
            if (group.renderers.isEmpty()) {
                SYNC_GROUPS.remove(syncId);
            }
        }

        if (mapView != null) {
            @SuppressWarnings("deprecation")
            int mapId = mapView.getId();
            MAP_LOCATIONS.remove(mapId);
            MAP_LAST_SENT.remove(mapId);
        }

        if (config != null) {
            config.decrementAnimationCount();
        }
    }

    public void setMapView(MapView view) {
        this.mapView = view;
    }

    public MapView getMapView() {
        return this.mapView;
    }

    public long getSyncId() {
        return syncId;
    }

    public int getId() {
        return id;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public List<byte[]> getFrames() {
        List<byte[]> list = new ArrayList<>(frameCount);
        for (byte[] frame : frames) {
            byte[] copy = new byte[frame.length];
            System.arraycopy(frame, 0, copy, 0, frame.length);
            list.add(copy);
        }
        return list;
    }

    public List<Integer> getDelays() {
        List<Integer> delays = new ArrayList<>(frameCount);
        long prev = 0;
        for (int i = 0; i < frameCount; i++) {
            delays.add((int) (frameEndTimes[i] - prev));
            prev = frameEndTimes[i];
        }
        return delays;
    }

    public int getAverageDelay() {
        return frameCount > 0 ? (int) (totalDuration / frameCount) : 100;
    }
}