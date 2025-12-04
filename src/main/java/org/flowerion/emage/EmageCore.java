package org.flowerion.emage;

import org.bukkit.map.MapPalette;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NodeList;

public final class EmageCore {

    private EmageCore() {}

    public static final int MAP_SIZE = 16384;
    public static final int MAP_WIDTH = 128;

    private static final int THREAD_COUNT = Math.max(2, Runtime.getRuntime().availableProcessors());
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(
            2, THREAD_COUNT,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(200),
            r -> {
                Thread t = new Thread(r, "Emage-Worker");
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private static final Queue<byte[]> BYTE_ARRAY_POOL = new ConcurrentLinkedQueue<>();
    private static volatile int maxPoolSize = 100;
    private static volatile boolean usePool = true;

    public static void setUsePool(boolean use) {
        usePool = use;
        if (!use) BYTE_ARRAY_POOL.clear();
    }

    public static void setMaxPoolSize(int size) {
        maxPoolSize = size;
        while (BYTE_ARRAY_POOL.size() > maxPoolSize) {
            BYTE_ARRAY_POOL.poll();
        }
    }

    public static byte[] acquireBuffer() {
        if (usePool) {
            byte[] buffer = BYTE_ARRAY_POOL.poll();
            if (buffer != null) return buffer;
        }
        return new byte[MAP_SIZE];
    }

    public static void releaseBuffer(byte[] buffer) {
        if (usePool && buffer != null && buffer.length == MAP_SIZE && BYTE_ARRAY_POOL.size() < maxPoolSize) {
            BYTE_ARRAY_POOL.offer(buffer);
        }
    }

    public static int getPoolSize() {
        return BYTE_ARRAY_POOL.size();
    }

    public enum Quality {
        FAST,
        BALANCED,
        HIGH
    }

    @SuppressWarnings("deprecation")
    public static byte matchColor(int r, int g, int b) {
        return MapPalette.matchColor(clamp(r), clamp(g), clamp(b));
    }

    @SuppressWarnings("deprecation")
    public static Color getColor(byte index) {
        try {
            return MapPalette.getColor(index);
        } catch (Exception e) {
            return Color.BLACK;
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static BufferedImage sharpenSubtle(BufferedImage img, Quality quality) {
        if (quality != Quality.HIGH) {
            return img;
        }

        int width = img.getWidth();
        int height = img.getHeight();

        if (width < 3 || height < 3) {
            return img;
        }

        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);

        int[] pixels = new int[width * height];
        img.getRGB(0, 0, width, height, pixels, 0, width);

        int[] output = new int[width * height];

        float amount = 0.2f;

        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                int idx = y * width + x;

                int center = pixels[idx];
                int a = (center >> 24) & 0xFF;
                int cr = (center >> 16) & 0xFF;
                int cg = (center >> 8) & 0xFF;
                int cb = center & 0xFF;

                int sumR = 0, sumG = 0, sumB = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int p = pixels[(y + dy) * width + (x + dx)];
                        sumR += (p >> 16) & 0xFF;
                        sumG += (p >> 8) & 0xFF;
                        sumB += p & 0xFF;
                    }
                }

                int avgR = sumR / 8;
                int avgG = sumG / 8;
                int avgB = sumB / 8;

                int nr = clamp((int) (cr + (cr - avgR) * amount));
                int ng = clamp((int) (cg + (cg - avgG) * amount));
                int nb = clamp((int) (cb + (cb - avgB) * amount));

                output[idx] = (a << 24) | (nr << 16) | (ng << 8) | nb;
            }
        }

        for (int x = 0; x < width; x++) {
            output[x] = pixels[x];
            output[(height - 1) * width + x] = pixels[(height - 1) * width + x];
        }
        for (int y = 0; y < height; y++) {
            output[y * width] = pixels[y * width];
            output[y * width + width - 1] = pixels[y * width + width - 1];
        }

        result.setRGB(0, 0, width, height, output, 0, width);
        return result;
    }

    private static final int SMALL_BUFFER_SIZE = 4096;
    private static final int MEDIUM_BUFFER_SIZE = 16384;
    private static final Queue<byte[]> SMALL_BUFFER_POOL = new ConcurrentLinkedQueue<>();
    private static final Queue<byte[]> MEDIUM_BUFFER_POOL = new ConcurrentLinkedQueue<>();
    private static final Queue<int[]> INT_ARRAY_POOL = new ConcurrentLinkedQueue<>();

    public static byte[] acquireSmallBuffer() {
        byte[] buffer = SMALL_BUFFER_POOL.poll();
        return buffer != null ? buffer : new byte[SMALL_BUFFER_SIZE];
    }

    public static void releaseSmallBuffer(byte[] buffer) {
        if (buffer != null && buffer.length == SMALL_BUFFER_SIZE && SMALL_BUFFER_POOL.size() < 50) {
            SMALL_BUFFER_POOL.offer(buffer);
        }
    }

    public static int[] acquireIntArray(int size) {
        if (size == MAP_SIZE) {
            int[] arr = INT_ARRAY_POOL.poll();
            if (arr != null) return arr;
        }
        return new int[size];
    }

    public static void releaseIntArray(int[] arr) {
        if (arr != null && arr.length == MAP_SIZE && INT_ARRAY_POOL.size() < 20) {
            INT_ARRAY_POOL.offer(arr);
        }
    }

    public static void clearAllPools() {
        BYTE_ARRAY_POOL.clear();
        SMALL_BUFFER_POOL.clear();
        MEDIUM_BUFFER_POOL.clear();
        INT_ARRAY_POOL.clear();
    }

    public static byte[] dither(BufferedImage img) {
        return dither(img, Quality.BALANCED);
    }

    public static byte[] dither(BufferedImage img, Quality quality) {
        BufferedImage processed = sharpenSubtle(img, quality);

        BufferedImage prepared = prepareImage(processed, MAP_WIDTH, MAP_WIDTH);
        int[] pixels = new int[MAP_SIZE];
        prepared.getRGB(0, 0, MAP_WIDTH, MAP_WIDTH, pixels, 0, MAP_WIDTH);

        return switch (quality) {
            case FAST -> ditherNone(pixels);
            case BALANCED -> ditherFloydSteinberg(pixels);
            case HIGH -> ditherFloydSteinbergHQ(pixels);
        };
    }

    public static byte[] ditherPixels(int[] pixels, Quality quality) {
        int[] copy = new int[pixels.length];
        System.arraycopy(pixels, 0, copy, 0, pixels.length);

        return switch (quality) {
            case FAST -> ditherNone(copy);
            case BALANCED -> ditherFloydSteinberg(copy);
            case HIGH -> ditherFloydSteinbergHQ(copy);
        };
    }

    private static BufferedImage prepareImage(BufferedImage src, int width, int height) {
        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dest;
    }

    private static byte[] ditherNone(int[] pixels) {
        byte[] result = new byte[MAP_SIZE];

        for (int i = 0; i < MAP_SIZE; i++) {
            int rgb = pixels[i];
            int alpha = (rgb >> 24) & 0xFF;

            if (alpha < 128) {
                result[i] = 0;
                continue;
            }

            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;

            result[i] = matchColor(r, g, b);
        }

        return result;
    }

    private static byte[] ditherFloydSteinberg(int[] pixels) {
        byte[] result = new byte[MAP_SIZE];

        float[] errR = new float[MAP_SIZE];
        float[] errG = new float[MAP_SIZE];
        float[] errB = new float[MAP_SIZE];

        for (int y = 0; y < MAP_WIDTH; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                int idx = y * MAP_WIDTH + x;
                int rgb = pixels[idx];
                int alpha = (rgb >> 24) & 0xFF;

                if (alpha < 128) {
                    result[idx] = 0;
                    continue;
                }

                int r = clamp(Math.round(((rgb >> 16) & 0xFF) + errR[idx]));
                int g = clamp(Math.round(((rgb >> 8) & 0xFF) + errG[idx]));
                int b = clamp(Math.round((rgb & 0xFF) + errB[idx]));

                byte match = matchColor(r, g, b);
                result[idx] = match;

                Color palColor = getColor(match);
                float eR = r - palColor.getRed();
                float eG = g - palColor.getGreen();
                float eB = b - palColor.getBlue();

                if (x + 1 < MAP_WIDTH) {
                    int ni = idx + 1;
                    errR[ni] += eR * 0.4375f;
                    errG[ni] += eG * 0.4375f;
                    errB[ni] += eB * 0.4375f;
                }

                if (y + 1 < MAP_WIDTH && x > 0) {
                    int ni = idx + MAP_WIDTH - 1;
                    errR[ni] += eR * 0.1875f;
                    errG[ni] += eG * 0.1875f;
                    errB[ni] += eB * 0.1875f;
                }

                if (y + 1 < MAP_WIDTH) {
                    int ni = idx + MAP_WIDTH;
                    errR[ni] += eR * 0.3125f;
                    errG[ni] += eG * 0.3125f;
                    errB[ni] += eB * 0.3125f;
                }

                if (y + 1 < MAP_WIDTH && x + 1 < MAP_WIDTH) {
                    int ni = idx + MAP_WIDTH + 1;
                    errR[ni] += eR * 0.0625f;
                    errG[ni] += eG * 0.0625f;
                    errB[ni] += eB * 0.0625f;
                }
            }
        }

        return result;
    }

    private static byte[] ditherFloydSteinbergHQ(int[] pixels) {
        byte[] result = new byte[MAP_SIZE];

        float[] errR = new float[MAP_SIZE];
        float[] errG = new float[MAP_SIZE];
        float[] errB = new float[MAP_SIZE];

        for (int y = 0; y < MAP_WIDTH; y++) {
            boolean leftToRight = (y % 2 == 0);

            int xStart = leftToRight ? 0 : MAP_WIDTH - 1;
            int xEnd = leftToRight ? MAP_WIDTH : -1;
            int xStep = leftToRight ? 1 : -1;

            for (int x = xStart; x != xEnd; x += xStep) {
                int idx = y * MAP_WIDTH + x;
                int rgb = pixels[idx];
                int alpha = (rgb >> 24) & 0xFF;

                if (alpha < 128) {
                    result[idx] = 0;
                    continue;
                }

                int r = clamp(Math.round(((rgb >> 16) & 0xFF) + errR[idx]));
                int g = clamp(Math.round(((rgb >> 8) & 0xFF) + errG[idx]));
                int b = clamp(Math.round((rgb & 0xFF) + errB[idx]));

                byte match = matchColor(r, g, b);
                result[idx] = match;

                Color palColor = getColor(match);
                float eR = (r - palColor.getRed()) * 0.8f;
                float eG = (g - palColor.getGreen()) * 0.8f;
                float eB = (b - palColor.getBlue()) * 0.8f;

                eR = Math.max(-32, Math.min(32, eR));
                eG = Math.max(-32, Math.min(32, eG));
                eB = Math.max(-32, Math.min(32, eB));

                int dx = xStep;

                if (x + dx >= 0 && x + dx < MAP_WIDTH) {
                    int ni = idx + dx;
                    errR[ni] += eR * 0.4375f;
                    errG[ni] += eG * 0.4375f;
                    errB[ni] += eB * 0.4375f;
                }

                if (y + 1 < MAP_WIDTH) {
                    if (x - dx >= 0 && x - dx < MAP_WIDTH) {
                        int ni = (y + 1) * MAP_WIDTH + (x - dx);
                        errR[ni] += eR * 0.1875f;
                        errG[ni] += eG * 0.1875f;
                        errB[ni] += eB * 0.1875f;
                    }

                    {
                        int ni = (y + 1) * MAP_WIDTH + x;
                        errR[ni] += eR * 0.3125f;
                        errG[ni] += eG * 0.3125f;
                        errB[ni] += eB * 0.3125f;
                    }

                    if (x + dx >= 0 && x + dx < MAP_WIDTH) {
                        int ni = (y + 1) * MAP_WIDTH + (x + dx);
                        errR[ni] += eR * 0.0625f;
                        errG[ni] += eG * 0.0625f;
                        errB[ni] += eB * 0.0625f;
                    }
                }
            }
        }

        return result;
    }

    public static BufferedImage resize(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return copyImage(src);
        }

        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double scaleX = (double) width / src.getWidth();
        double scaleY = (double) height / src.getHeight();

        if (scaleX < 0.5 || scaleY < 0.5) {
            BufferedImage temp = src;
            int tempW = src.getWidth();
            int tempH = src.getHeight();

            while (tempW > width * 2 || tempH > height * 2) {
                int nextW = Math.max(width, tempW / 2);
                int nextH = Math.max(height, tempH / 2);

                BufferedImage step = new BufferedImage(nextW, nextH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D stepG = step.createGraphics();
                stepG.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                stepG.drawImage(temp, 0, 0, nextW, nextH, null);
                stepG.dispose();

                temp = step;
                tempW = nextW;
                tempH = nextH;
            }

            g.drawImage(temp, 0, 0, width, height, null);
        } else {
            g.drawImage(src, 0, 0, width, height, null);
        }

        g.dispose();
        return dest;
    }

    private static BufferedImage copyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    public static byte[] compressMap(byte[] data) {
        return EmageCompression.compressSingleStatic(data);
    }

    public static byte[] decompressMap(byte[] compressed) {
        return EmageCompression.decompressSingleStatic(compressed);
    }

    public static AnimData decompressAnim(byte[] data) {
        try {
            EmageCompression.AnimGridData grid = EmageCompression.decompressAnimGrid(data);
            if (grid != null && !grid.cells.isEmpty()) {
                List<byte[]> frames = grid.cells.values().iterator().next();
                int avg = grid.delays.isEmpty() ? 100 :
                        (int) grid.delays.stream().mapToInt(i -> i).average().orElse(100);
                return new AnimData(frames, grid.delays, avg, grid.syncId);
            }
        } catch (Exception ignored) {}

        List<byte[]> frames = new ArrayList<>();
        frames.add(new byte[MAP_SIZE]);
        List<Integer> delays = new ArrayList<>();
        delays.add(100);
        return new AnimData(frames, delays, 100, 0L);
    }

    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames) throws Exception {
        return processGifGrid(url, gridW, gridH, maxFrames, Quality.BALANCED);
    }

    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames, Quality quality) throws Exception {
        int totalW = gridW * MAP_WIDTH;
        int totalH = gridH * MAP_WIDTH;

        GifData gifData = readGif(url, maxFrames);

        if (gifData.frames.isEmpty()) {
            throw new Exception("No frames found in GIF");
        }

        int frameCount = gifData.frames.size();

        @SuppressWarnings("unchecked")
        List<byte[]>[][] grid = new List[gridW][gridH];
        for (int x = 0; x < gridW; x++) {
            for (int y = 0; y < gridH; y++) {
                grid[x][y] = new ArrayList<>(frameCount);
                for (int f = 0; f < frameCount; f++) {
                    grid[x][y].add(null);
                }
            }
        }

        CountDownLatch latch = new CountDownLatch(frameCount);
        Exception[] errors = new Exception[1];

        for (int i = 0; i < frameCount; i++) {
            final int frameIdx = i;
            final BufferedImage frameCopy = deepCopyImage(gifData.frames.get(i));

            EXECUTOR.submit(() -> {
                try {
                    BufferedImage resized = resize(frameCopy, totalW, totalH);

                    int[] allPixels = new int[totalW * totalH];
                    resized.getRGB(0, 0, totalW, totalH, allPixels, 0, totalW);

                    for (int gy = 0; gy < gridH; gy++) {
                        for (int gx = 0; gx < gridW; gx++) {
                            int startX = gx * MAP_WIDTH;
                            int startY = gy * MAP_WIDTH;

                            int[] chunkPixels = new int[MAP_SIZE];
                            for (int cy = 0; cy < MAP_WIDTH; cy++) {
                                int srcOffset = (startY + cy) * totalW + startX;
                                int dstOffset = cy * MAP_WIDTH;
                                System.arraycopy(allPixels, srcOffset, chunkPixels, dstOffset, MAP_WIDTH);
                            }

                            byte[] dithered = ditherPixels(chunkPixels, quality);

                            synchronized (grid[gx][gy]) {
                                grid[gx][gy].set(frameIdx, dithered);
                            }
                        }
                    }
                } catch (Exception e) {
                    errors[0] = e;
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(60, TimeUnit.SECONDS);

        if (!completed) {
            throw new Exception("GIF processing timed out");
        }

        if (errors[0] != null) {
            throw errors[0];
        }

        for (int x = 0; x < gridW; x++) {
            for (int y = 0; y < gridH; y++) {
                for (int f = 0; f < frameCount; f++) {
                    if (grid[x][y].get(f) == null) {
                        throw new Exception("Frame " + f + " at grid [" + x + "," + y + "] was not processed");
                    }
                }
            }
        }

        gifData.frames.clear();

        int avgDelay = gifData.delays.isEmpty() ? 100 :
                (int) gifData.delays.stream().mapToInt(Integer::intValue).average().orElse(100);

        return new GifGridData(grid, gifData.delays, avgDelay, gridW, gridH);
    }

    private static GifData readGif(URL url, int maxFrames) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        try (InputStream is = new BufferedInputStream(url.openStream(), 65536);
             ImageInputStream iis = ImageIO.createImageInputStream(is)) {

            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(iis, false, false);

            int numFrames;
            try {
                numFrames = reader.getNumImages(true);
            } catch (Exception e) {
                numFrames = maxFrames;
            }
            numFrames = Math.min(numFrames, maxFrames);

            int canvasWidth = 0;
            int canvasHeight = 0;
            Color backgroundColor = Color.WHITE;

            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    String format = streamMeta.getNativeMetadataFormatName();
                    IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(format);

                    NodeList lsdNodes = root.getElementsByTagName("LogicalScreenDescriptor");
                    if (lsdNodes.getLength() > 0) {
                        IIOMetadataNode lsd = (IIOMetadataNode) lsdNodes.item(0);
                        String w = lsd.getAttribute("logicalScreenWidth");
                        String h = lsd.getAttribute("logicalScreenHeight");
                        if (w != null && !w.isEmpty()) canvasWidth = Integer.parseInt(w);
                        if (h != null && !h.isEmpty()) canvasHeight = Integer.parseInt(h);
                    }

                    NodeList gctNodes = root.getElementsByTagName("GlobalColorTable");
                    if (gctNodes.getLength() > 0) {
                        NodeList lsdList = root.getElementsByTagName("LogicalScreenDescriptor");
                        if (lsdList.getLength() > 0) {
                            IIOMetadataNode lsd = (IIOMetadataNode) lsdList.item(0);
                            String bgIndex = lsd.getAttribute("backgroundColorIndex");
                            if (bgIndex != null && !bgIndex.isEmpty()) {
                                int bgIdx = Integer.parseInt(bgIndex);
                                IIOMetadataNode gct = (IIOMetadataNode) gctNodes.item(0);
                                NodeList entries = gct.getElementsByTagName("ColorTableEntry");
                                if (bgIdx < entries.getLength()) {
                                    IIOMetadataNode entry = (IIOMetadataNode) entries.item(bgIdx);
                                    int r = Integer.parseInt(entry.getAttribute("red"));
                                    int g = Integer.parseInt(entry.getAttribute("green"));
                                    int b = Integer.parseInt(entry.getAttribute("blue"));
                                    backgroundColor = new Color(r, g, b);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (canvasWidth <= 0 || canvasHeight <= 0) {
                BufferedImage first = reader.read(0);
                canvasWidth = first.getWidth();
                canvasHeight = first.getHeight();
            }

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D canvasG = canvas.createGraphics();
            canvasG.setBackground(backgroundColor);
            canvasG.clearRect(0, 0, canvasWidth, canvasHeight);

            BufferedImage restoreCanvas = null;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage rawFrame;
                try {
                    rawFrame = reader.read(i);
                } catch (Exception e) {
                    break;
                }
                if (rawFrame == null) break;

                IIOMetadata meta = reader.getImageMetadata(i);
                int delay = 100;
                String disposal = "none";
                int frameX = 0, frameY = 0;
                int frameW = rawFrame.getWidth();
                int frameH = rawFrame.getHeight();

                if (meta != null) {
                    try {
                        String format = meta.getNativeMetadataFormatName();
                        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

                        NodeList gceNodes = root.getElementsByTagName("GraphicControlExtension");
                        if (gceNodes.getLength() > 0) {
                            IIOMetadataNode gce = (IIOMetadataNode) gceNodes.item(0);
                            String delayStr = gce.getAttribute("delayTime");
                            if (delayStr != null && !delayStr.isEmpty()) {
                                int d = Integer.parseInt(delayStr);
                                delay = Math.max(20, d * 10);
                            }
                            disposal = gce.getAttribute("disposalMethod");
                            if (disposal == null) disposal = "none";
                        }

                        NodeList descNodes = root.getElementsByTagName("ImageDescriptor");
                        if (descNodes.getLength() > 0) {
                            IIOMetadataNode desc = (IIOMetadataNode) descNodes.item(0);
                            String xStr = desc.getAttribute("imageLeftPosition");
                            String yStr = desc.getAttribute("imageTopPosition");
                            String wStr = desc.getAttribute("imageWidth");
                            String hStr = desc.getAttribute("imageHeight");
                            if (xStr != null && !xStr.isEmpty()) frameX = Integer.parseInt(xStr);
                            if (yStr != null && !yStr.isEmpty()) frameY = Integer.parseInt(yStr);
                            if (wStr != null && !wStr.isEmpty()) frameW = Integer.parseInt(wStr);
                            if (hStr != null && !hStr.isEmpty()) frameH = Integer.parseInt(hStr);
                        }
                    } catch (Exception ignored) {}
                }

                if ("restoretoprevious".equalsIgnoreCase(disposal)) {
                    restoreCanvas = deepCopyImage(canvas);
                }

                canvasG.setComposite(AlphaComposite.SrcOver);
                canvasG.drawImage(rawFrame, frameX, frameY, null);

                frames.add(deepCopyImage(canvas));
                delays.add(delay);

                if ("restoretobackgroundcolor".equalsIgnoreCase(disposal)) {
                    canvasG.setComposite(AlphaComposite.Src);
                    canvasG.setColor(backgroundColor);
                    canvasG.fillRect(frameX, frameY, frameW, frameH);
                    canvasG.setComposite(AlphaComposite.SrcOver);
                } else if ("restoretoprevious".equalsIgnoreCase(disposal)) {
                    if (restoreCanvas != null) {
                        canvasG.setComposite(AlphaComposite.Src);
                        canvasG.drawImage(restoreCanvas, 0, 0, null);
                        canvasG.setComposite(AlphaComposite.SrcOver);
                    }
                }
            }

            canvasG.dispose();
            reader.dispose();
        }

        return new GifData(frames, delays);
    }

    private static BufferedImage deepCopyImage(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        int[] pixels = new int[src.getWidth() * src.getHeight()];
        src.getRGB(0, 0, src.getWidth(), src.getHeight(), pixels, 0, src.getWidth());
        copy.setRGB(0, 0, copy.getWidth(), copy.getHeight(), pixels, 0, copy.getWidth());
        return copy;
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException ignored) {
            EXECUTOR.shutdownNow();
        }
        BYTE_ARRAY_POOL.clear();
    }

    private static class GifData {
        final List<BufferedImage> frames;
        final List<Integer> delays;

        GifData(List<BufferedImage> frames, List<Integer> delays) {
            this.frames = frames;
            this.delays = delays;
        }
    }

    public static class AnimData {
        public final List<byte[]> frames;
        public final List<Integer> delays;
        public final int avgDelay;
        public final long syncId;

        public AnimData(List<byte[]> frames, List<Integer> delays, int avgDelay, long syncId) {
            this.frames = frames;
            this.delays = delays;
            this.avgDelay = avgDelay;
            this.syncId = syncId;
        }
    }

    private static byte[] ditherFloydSteinbergOptimized(int[] pixels) {
        byte[] result = new byte[MAP_SIZE];

        int[] errR = new int[MAP_SIZE];
        int[] errG = new int[MAP_SIZE];
        int[] errB = new int[MAP_SIZE];

        for (int y = 0; y < MAP_WIDTH; y++) {
            int rowOffset = y * MAP_WIDTH;
            int nextRowOffset = rowOffset + MAP_WIDTH;

            for (int x = 0; x < MAP_WIDTH; x++) {
                int idx = rowOffset + x;
                int rgb = pixels[idx];
                int alpha = (rgb >>> 24);

                if (alpha < 128) {
                    result[idx] = 0;
                    continue;
                }

                int r = clamp(((rgb >> 16) & 0xFF) + (errR[idx] >> 4));
                int g = clamp(((rgb >> 8) & 0xFF) + (errG[idx] >> 4));
                int b = clamp((rgb & 0xFF) + (errB[idx] >> 4));

                byte match = matchColor(r, g, b);
                result[idx] = match;

                Color palColor = getColor(match);
                int eR = (r - palColor.getRed());
                int eG = (g - palColor.getGreen());
                int eB = (b - palColor.getBlue());

                if (x + 1 < MAP_WIDTH) {
                    int ni = idx + 1;
                    errR[ni] += eR * 7;
                    errG[ni] += eG * 7;
                    errB[ni] += eB * 7;
                }

                if (y + 1 < MAP_WIDTH) {
                    if (x > 0) {
                        int ni = nextRowOffset + x - 1;
                        errR[ni] += eR * 3;
                        errG[ni] += eG * 3;
                        errB[ni] += eB * 3;
                    }

                    int ni = nextRowOffset + x;
                    errR[ni] += eR * 5;
                    errG[ni] += eG * 5;
                    errB[ni] += eB * 5;

                    if (x + 1 < MAP_WIDTH) {
                        ni = nextRowOffset + x + 1;
                        errR[ni] += eR;
                        errG[ni] += eG;
                        errB[ni] += eB;
                    }
                }
            }
        }

        return result;
    }

    private static final byte[] COLOR_CACHE = new byte[256 * 256 * 256 / 8];
    private static final boolean USE_COLOR_CACHE = false;

    @SuppressWarnings("deprecation")
    public static byte matchColorCached(int r, int g, int b) {
        if (!USE_COLOR_CACHE) {
            return MapPalette.matchColor(clamp(r), clamp(g), clamp(b));
        }

        int r5 = (r >> 3) & 0x1F;
        int g5 = (g >> 3) & 0x1F;
        int b5 = (b >> 3) & 0x1F;
        int cacheIdx = (r5 << 10) | (g5 << 5) | b5;

        byte cached = COLOR_CACHE[cacheIdx];
        if (cached != 0) {
            return (byte) (cached - 1);
        }

        byte result = MapPalette.matchColor(clamp(r), clamp(g), clamp(b));
        COLOR_CACHE[cacheIdx] = (byte) (result + 1);
        return result;
    }

    public static BufferedImage resizeOptimized(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return copyImage(src);
        }

        double scaleX = (double) width / src.getWidth();
        double scaleY = (double) height / src.getHeight();

        if (scaleX < 0.5 && scaleY < 0.5) {
            return resizeAreaAverage(src, width, height);
        }

        return resize(src, width, height);
    }

    private static BufferedImage resizeAreaAverage(BufferedImage src, int dstWidth, int dstHeight) {
        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();

        int[] srcPixels = new int[srcWidth * srcHeight];
        src.getRGB(0, 0, srcWidth, srcHeight, srcPixels, 0, srcWidth);

        int[] dstPixels = new int[dstWidth * dstHeight];

        float xRatio = (float) srcWidth / dstWidth;
        float yRatio = (float) srcHeight / dstHeight;

        for (int dy = 0; dy < dstHeight; dy++) {
            int srcY1 = (int) (dy * yRatio);
            int srcY2 = Math.min((int) ((dy + 1) * yRatio), srcHeight);

            for (int dx = 0; dx < dstWidth; dx++) {
                int srcX1 = (int) (dx * xRatio);
                int srcX2 = Math.min((int) ((dx + 1) * xRatio), srcWidth);

                int rSum = 0, gSum = 0, bSum = 0, aSum = 0;
                int count = 0;

                for (int sy = srcY1; sy < srcY2; sy++) {
                    int rowOffset = sy * srcWidth;
                    for (int sx = srcX1; sx < srcX2; sx++) {
                        int pixel = srcPixels[rowOffset + sx];
                        aSum += (pixel >>> 24);
                        rSum += (pixel >> 16) & 0xFF;
                        gSum += (pixel >> 8) & 0xFF;
                        bSum += pixel & 0xFF;
                        count++;
                    }
                }

                if (count > 0) {
                    int a = aSum / count;
                    int r = rSum / count;
                    int g = gSum / count;
                    int b = bSum / count;
                    dstPixels[dy * dstWidth + dx] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
        }

        BufferedImage result = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_ARGB);
        result.setRGB(0, 0, dstWidth, dstHeight, dstPixels, 0, dstWidth);
        return result;
    }

    public static class GifGridData {
        public final List<byte[]>[][] grid;
        public final List<Integer> delays;
        public final int avgDelay;
        public final int gridWidth;
        public final int gridHeight;

        public GifGridData(List<byte[]>[][] grid, List<Integer> delays, int avgDelay, int gridWidth, int gridHeight) {
            this.grid = grid;
            this.delays = delays;
            this.avgDelay = avgDelay;
            this.gridWidth = gridWidth;
            this.gridHeight = gridHeight;
        }
    }
}