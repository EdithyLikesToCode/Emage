package net.ed1thy.emage.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.Emage;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class FlatFileStorage {

    private final File dataFolder;
    private final ExecutorService ioExecutor;

    public FlatFileStorage(@NotNull Emage plugin, @NotNull ExecutorService ioExecutor) {
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        if (!this.dataFolder.exists()) {
            this.dataFolder.mkdirs();
        }
        this.ioExecutor = ioExecutor;
    }

    public CompletableFuture<Void> saveBundledFrameAsync(int syncGroupId, int frameIndex, Map<Integer, MapFrameUpdate> updates) {
        return CompletableFuture.runAsync(() -> {
            try {
                saveBundledFrame(syncGroupId, frameIndex, updates);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save frame data to disk", e);
            }
        }, ioExecutor);
    }

    private void saveBundledFrame(int syncGroupId, int frameIndex, Map<Integer, MapFrameUpdate> updates) throws IOException {
        if (updates.isEmpty()) return;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeInt(updates.size());
            for (Map.Entry<Integer, MapFrameUpdate> entry : updates.entrySet()) {
                dos.writeInt(entry.getKey());
                MapFrameUpdate update = entry.getValue();
                dos.writeInt(update.parts().length);
                for (DeltaFrame df : update.parts()) {
                    ByteBuf buf = df.packetBuf();
                    int len = buf.readableBytes();
                    dos.writeInt(len);
                    byte[] bytes = new byte[len];
                    buf.getBytes(buf.readerIndex(), bytes);
                    dos.write(bytes);
                }
            }
        }

        byte[] uncompressed = baos.toByteArray();
        byte[] compressed = new byte[(int) com.github.luben.zstd.Zstd.compressBound(uncompressed.length)];
        long compressedSize = com.github.luben.zstd.Zstd.compress(compressed, uncompressed, 3);
        byte[] finalCompressed = java.util.Arrays.copyOf(compressed, (int) compressedSize);

        File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
        if (!groupDir.exists()) groupDir.mkdirs();

        File frameFile = new File(groupDir, "frame_" + frameIndex + ".zst");
        try (FileOutputStream fos = new FileOutputStream(frameFile)) {
            fos.write(finalCompressed);
        }
    }

    @NotNull
    public Map<Integer, MapFrameUpdate> loadBundledFrame(int syncGroupId, int frameIndex) throws IOException {
        File frameFile = new File(dataFolder, syncGroupId + File.separator + "frame_" + frameIndex + ".zst");
        if (!frameFile.exists()) return java.util.Collections.emptyMap();

        byte[] compressed = Files.readAllBytes(frameFile.toPath());
        long decompressedSize = com.github.luben.zstd.Zstd.decompressedSize(compressed);
        if (decompressedSize <= 0) return java.util.Collections.emptyMap();

        byte[] uncompressed = new byte[(int) decompressedSize];
        com.github.luben.zstd.Zstd.decompress(uncompressed, compressed);

        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(uncompressed));
        int numUpdates = dis.readInt();
        Map<Integer, MapFrameUpdate> resultMap = new java.util.HashMap<>();

        for (int i = 0; i < numUpdates; i++) {
            int mapId = dis.readInt();
            int numParts = dis.readInt();
            DeltaFrame[] parts = new DeltaFrame[numParts];
            for (int p = 0; p < numParts; p++) {
                int len = dis.readInt();
                byte[] bytes = new byte[len];
                dis.readFully(bytes);

                ByteBuf buf = PooledByteBufAllocator.DEFAULT.directBuffer(len);
                buf.writeBytes(bytes);
                parts[p] = new DeltaFrame(frameIndex, mapId, buf);
            }
            resultMap.put(mapId, new MapFrameUpdate(parts));
        }
        return resultMap;
    }

    public CompletableFuture<Void> saveFrameDelaysAsync(int syncGroupId, int[] delays) {
        return CompletableFuture.runAsync(() -> {
            File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
            if (!groupDir.exists()) groupDir.mkdirs();
            File delayFile = new File(groupDir, "delays.dat");
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(delayFile))) {
                dos.writeInt(delays.length);
                for (int d : delays) dos.writeInt(d);
            } catch (IOException ignored) {}
        }, ioExecutor);
    }

    public int[] loadFrameDelays(int syncGroupId) {
        File delayFile = new File(dataFolder, syncGroupId + File.separator + "delays.dat");
        if (!delayFile.exists()) return null;
        try (DataInputStream dis = new DataInputStream(new FileInputStream(delayFile))) {
            int len = dis.readInt();
            int[] delays = new int[len];
            for (int i = 0; i < len; i++) delays[i] = dis.readInt();
            return delays;
        } catch (IOException e) {
            return null;
        }
    }

    public boolean groupExists(int syncGroupId) {
        File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
        return groupDir.exists() && groupDir.isDirectory();
    }

    public void deleteSyncGroup(int syncGroupId) {
        File groupDir = new File(dataFolder, String.valueOf(syncGroupId));
        deleteDirectory(groupDir);
    }

    public int cleanupOrphanedFrameData(Set<Integer> activeIds) {
        if (!dataFolder.exists()) return 0;
        File[] dirs = dataFolder.listFiles();
        if (dirs == null) return 0;
        int deleted = 0;
        for (File dir : dirs) {
            if (dir.isDirectory()) {
                try {
                    int id = Integer.parseInt(dir.getName());
                    if (!activeIds.contains(id)) {
                        deleteDirectory(dir);
                        deleted++;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return deleted;
    }

    private void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
        dir.delete();
    }

    public void shutdown() {}
}