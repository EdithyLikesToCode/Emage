package net.ed1thy.emage.processing;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import net.ed1thy.emage.model.DeltaFrame;
import net.ed1thy.emage.model.MapFrameUpdate;
import net.ed1thy.emage.processing.dither.BlueNoiseDither;
import net.ed1thy.emage.storage.FlatFileStorage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;

public class ImagePipeline {

    private final ColorPalette lut;
    private final FlatFileStorage storage;
    private final BlueNoiseDither dither;
    private final ExecutorService vtExecutor;

    public ImagePipeline(@NotNull ColorPalette lut, @NotNull FlatFileStorage storage, @NotNull ExecutorService vtExecutor, @NotNull ForkJoinPool computePool) {
        this.lut = lut;
        this.storage = storage;
        this.dither = new BlueNoiseDither(computePool);
        this.vtExecutor = vtExecutor;
    }

    public void shutdown() {}

    private void writeVarInt(ByteBuf buf, int value) {
        while ((value & -128) != 0) {
            buf.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    public CompletableFuture<Map<Integer, MapFrameUpdate>> processStreamAsync(
            @NotNull ImageFrameProvider decoder, int syncGroupId, @NotNull List<Integer> virtualMapIds, int columns, int rows,
            @Nullable Consumer<Double> progressCallback) {

        return CompletableFuture.supplyAsync(() -> {
            Map<Integer, MapFrameUpdate> firstFrameMap = new HashMap<>();
            List<CompletableFuture<Void>> ioTasks = new ArrayList<>();

            try (decoder) {
                while (!lut.isReady()) {
                    Thread.sleep(50);
                }

                int totalWidth = columns * 128;
                int totalHeight = rows * 128;

                BufferedImage scaledImage = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
                Graphics2D gFinal = scaledImage.createGraphics();
                gFinal.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                gFinal.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                gFinal.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                gFinal.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
                gFinal.setComposite(AlphaComposite.Src);

                int[] argbPixels = new int[totalWidth * totalHeight];
                byte[] ditheredColors = new byte[totalWidth * totalHeight];
                BufferedImage rawImage = null;

                byte[][] prevMapFrames = new byte[virtualMapIds.size()][];
                byte[][] currMapFrames = new byte[virtualMapIds.size()][];
                for (int i = 0; i < virtualMapIds.size(); i++) {
                    prevMapFrames[i] = new byte[16384];
                    currMapFrames[i] = new byte[16384];
                }

                int totalFramesCount = decoder.getFrameCount();

                int[] frameDelays = new int[totalFramesCount];
                for (int i = 0; i < totalFramesCount; i++) {
                    frameDelays[i] = decoder.getFrameDelayMs(i);
                }
                ioTasks.add(storage.saveFrameDelaysAsync(syncGroupId, frameDelays));

                for (int frameIndex = 0; frameIndex < totalFramesCount; frameIndex++) {
                    int rawWidth = decoder.getFrameWidth(frameIndex);
                    int rawHeight = decoder.getFrameHeight(frameIndex);
                    int[] rawPixels = decoder.getFramePixels(frameIndex);

                    if (rawImage == null || rawImage.getWidth() != rawWidth || rawImage.getHeight() != rawHeight) {
                        rawImage = new BufferedImage(rawWidth, rawHeight, BufferedImage.TYPE_INT_ARGB);
                    }
                    rawImage.setRGB(0, 0, rawWidth, rawHeight, rawPixels, 0, rawWidth);

                    gFinal.drawImage(rawImage, 0, 0, totalWidth, totalHeight, null);

                    scaledImage.getRGB(0, 0, totalWidth, totalHeight, argbPixels, 0, totalWidth);
                    dither.applyDither(argbPixels, totalWidth, totalHeight, lut, ditheredColors);

                    final boolean firstFrame = (frameIndex == 0);
                    final int fIndex = frameIndex;

                    Map<Integer, MapFrameUpdate> frameUpdates = new ConcurrentHashMap<>();

                    try {
                        java.util.stream.IntStream.range(0, rows * columns).parallel().forEach(mapIndex -> {
                            int row = mapIndex / columns;
                            int col = mapIndex % columns;
                            int mapId = virtualMapIds.get(mapIndex);

                            byte[] prevMap = prevMapFrames[mapIndex];
                            byte[] currMap = currMapFrames[mapIndex];

                            if (firstFrame) {
                                extractMapArray(ditheredColors, totalWidth, col * 128, row * 128, currMap);
                                ByteBuf packetBuf = PooledByteBufAllocator.DEFAULT.directBuffer(16384 + 16);
                                writeVarInt(packetBuf, mapId);
                                packetBuf.writeByte(0);
                                packetBuf.writeBoolean(true);
                                packetBuf.writeBoolean(false);
                                packetBuf.writeByte(128);
                                packetBuf.writeByte(128);
                                packetBuf.writeByte(0);
                                packetBuf.writeByte(0);
                                writeVarInt(packetBuf, 16384);
                                packetBuf.writeBytes(currMap);

                                DeltaFrame fullPart = new DeltaFrame(fIndex, mapId, packetBuf);
                                MapFrameUpdate update = new MapFrameUpdate(new DeltaFrame[]{fullPart});

                                frameUpdates.put(mapId, update);
                                firstFrameMap.put(mapId, update);

                                currMapFrames[mapIndex] = prevMap;
                                prevMapFrames[mapIndex] = currMap;
                            } else {
                                MapTileData tileData = extractMapArrayWithBounds(ditheredColors, totalWidth, col * 128, row * 128, prevMap, currMap);
                                if (tileData.maxX != -1) {
                                    MapFrameUpdate update = calculateDelta(fIndex, mapId, tileData.mapTile, tileData.minX, tileData.minY, tileData.maxX, tileData.maxY);
                                    frameUpdates.put(mapId, update);
                                }
                                currMapFrames[mapIndex] = prevMap;
                                prevMapFrames[mapIndex] = currMap;
                            }
                        });
                    } catch (Exception e) {
                        for (MapFrameUpdate update : frameUpdates.values()) {
                            update.freeMemory();
                        }
                        throw e;
                    }

                    ioTasks.add(storage.saveBundledFrameAsync(syncGroupId, fIndex, frameUpdates).whenComplete((v, ex) -> {
                        if (fIndex != 0) {
                            for (MapFrameUpdate update : frameUpdates.values()) {
                                update.freeMemory();
                            }
                        }
                    }));

                    if (progressCallback != null) {
                        progressCallback.accept((double) (frameIndex + 1) / totalFramesCount);
                    }
                }

                gFinal.dispose();

                CompletableFuture.allOf(ioTasks.toArray(new CompletableFuture[0])).join();

            } catch (Exception e) {
                throw new RuntimeException("Failed to process image stream: " + e.getMessage(), e);
            }
            return firstFrameMap;
        }, vtExecutor);
    }

    private void extractMapArray(byte[] fullGrid, int gridWidth, int startX, int startY, byte[] outMapTile) {
        for (int y = 0; y < 128; y++) {
            System.arraycopy(fullGrid, (startY + y) * gridWidth + startX, outMapTile, y * 128, 128);
        }
    }

    private MapTileData extractMapArrayWithBounds(byte[] fullGrid, int gridWidth, int startX, int startY, byte[] prev, byte[] curr) {
        int minX = 128, minY = 128, maxX = -1, maxY = -1;
        for (int y = 0; y < 128; y++) {
            int srcPos = (startY + y) * gridWidth + startX;
            int destPos = y * 128;
            System.arraycopy(fullGrid, srcPos, curr, destPos, 128);
            for (int x = 0; x < 128; x++) {
                if (curr[destPos + x] != prev[destPos + x]) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        return new MapTileData(curr, minX, minY, maxX, maxY);
    }

    private MapFrameUpdate calculateDelta(int frameIndex, int mapId, byte[] curr, int minX, int minY, int maxX, int maxY) {
        int updateWidth = (maxX - minX) + 1;
        int updateHeight = (maxY - minY) + 1;

        ByteBuf packetBuf = PooledByteBufAllocator.DEFAULT.directBuffer(updateWidth * updateHeight + 18);
        writeVarInt(packetBuf, mapId);
        packetBuf.writeByte(0);
        packetBuf.writeBoolean(true);
        packetBuf.writeBoolean(false);
        packetBuf.writeByte(updateWidth);
        packetBuf.writeByte(updateHeight);
        packetBuf.writeByte(minX);
        packetBuf.writeByte(minY);
        writeVarInt(packetBuf, updateWidth * updateHeight);

        for (int y = 0; y < updateHeight; y++) {
            packetBuf.writeBytes(curr, (minY + y) * 128 + minX, updateWidth);
        }

        DeltaFrame part = new DeltaFrame(frameIndex, mapId, packetBuf);
        return new MapFrameUpdate(new DeltaFrame[]{part});
    }

    private record MapTileData(byte[] mapTile, int minX, int minY, int maxX, int maxY) {}
}