package net.edithymaster.emage.Processing;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;

import net.edithymaster.emage.Config.EmageConfig;
import org.w3c.dom.NodeList;

public final class EmageCore {

    private EmageCore() {}

    private static final Logger logger = Logger.getLogger(EmageCore.class.getName());

    public static final int MAP_SIZE = 16384;
    public static final int MAP_WIDTH = 128;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static volatile EmageConfig activeConfig = null;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "Emage-Processor-" + THREAD_COUNTER.incrementAndGet());
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
    );

    private static final ThreadLocal<double[]> TL_LIN_R = new ThreadLocal<>();
    private static final ThreadLocal<double[]> TL_LIN_G = new ThreadLocal<>();
    private static final ThreadLocal<double[]> TL_LIN_B = new ThreadLocal<>();
    private static final ThreadLocal<boolean[]> TL_TRANSPARENT = new ThreadLocal<>();

    private static final ConcurrentLinkedQueue<byte[]> BUFFER_POOL = new ConcurrentLinkedQueue<>();
    private static volatile boolean usePool = true;
    private static volatile int maxPoolSize = 100;

    public enum Quality {
        FAST,
        BALANCED,
        HIGH
    }

    public static void setUsePool(boolean use) {
        usePool = use;
    }

    public static boolean isUsePool() {
        return usePool;
    }

    public static int getPoolSize() {
        return BUFFER_POOL.size();
    }

    public static void setMaxPoolSize(int size) {
        maxPoolSize = size;
    }

    public static byte[] acquireBuffer() {
        if (usePool) {
            byte[] buf = BUFFER_POOL.poll();
            if (buf != null) {
                return buf;
            }
        }
        return new byte[MAP_SIZE];
    }

    public static void setConfig(EmageConfig config) {
        activeConfig = config;
    }

    private static long getMaxDownloadBytes() {
        return activeConfig != null ? activeConfig.getMaxDownloadBytes() : 50L * 1024 * 1024;
    }

    private static int getMaxRedirects() {
        return activeConfig != null ? activeConfig.getMaxRedirects() : 5;
    }

    private static int getConnectTimeout() {
        return activeConfig != null ? activeConfig.getConnectTimeout() : 10000;
    }

    private static int getReadTimeout() {
        return activeConfig != null ? activeConfig.getReadTimeout() : 30000;
    }

    private static boolean shouldBlockInternalUrls() {
        return activeConfig != null ? activeConfig.blockInternalUrls() : true;
    }

    public static void releaseBuffer(byte[] buffer) {
        if (usePool && buffer != null && buffer.length == MAP_SIZE && BUFFER_POOL.size() < maxPoolSize) {
            Arrays.fill(buffer, (byte) 0);
            BUFFER_POOL.offer(buffer);
        }
    }

    public static void clearAllPools() {
        BUFFER_POOL.clear();
    }

    public static void initColorSystem() {
        EmageColors.initCache();
    }

    public static byte matchColor(int r, int g, int b) {
        return EmageColors.matchColor(r, g, b);
    }

    public static Color getColor(byte index) {
        return EmageColors.getColor(index);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    public static BufferedImage resize(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return src;
        }

        if (src.getWidth() > width * 2 || src.getHeight() > height * 2) {
            return progressiveResize(src, width, height);
        }

        BufferedImage dest = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dest.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return dest;
    }

    private static BufferedImage progressiveResize(BufferedImage src, int targetW, int targetH) {
        BufferedImage current = src;
        int w = current.getWidth();
        int h = current.getHeight();

        while (w > targetW * 2 || h > targetH * 2) {
            int nextW = Math.max(targetW, w / 2);
            int nextH = Math.max(targetH, h / 2);

            BufferedImage next = new BufferedImage(nextW, nextH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, nextW, nextH, null);
            g.dispose();

            current = next;
            w = nextW;
            h = nextH;
        }

        if (w != targetW || h != targetH) {
            BufferedImage dest = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = dest.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, targetW, targetH, null);
            g.dispose();
            current = dest;
        }

        return current;
    }

    public static byte[] dither(BufferedImage img) {
        return dither(img, Quality.BALANCED);
    }

    public static byte[] dither(BufferedImage img, Quality quality) {
        BufferedImage prepared = prepareImage(img, MAP_WIDTH, MAP_WIDTH);
        int[] pixels = new int[MAP_SIZE];
        prepared.getRGB(0, 0, MAP_WIDTH, MAP_WIDTH, pixels, 0, MAP_WIDTH);
        return ditherPixels(pixels, quality);
    }

    public static byte[] ditherPixels(int[] pixels, Quality quality) {
        return switch (quality) {
            case FAST -> ditherOrdered(pixels);
            case BALANCED -> ditherFloydSteinberg(pixels);
            case HIGH -> ditherJarvisGammaCorrected(pixels);
        };
    }

    public static byte[] ditherPixelsStable(int[] pixels, int[] prevPixels, byte[] prevResult, Quality quality) {
        if (prevPixels == null || prevResult == null) {
            return ditherPixels(pixels, quality);
        }

        boolean[] changed = new boolean[MAP_SIZE];
        int changeCount = 0;
        for (int i = 0; i < MAP_SIZE; i++) {
            if (pixels[i] != prevPixels[i]) {
                changed[i] = true;
                changeCount++;
            }
        }

        if (changeCount == 0) {
            byte[] result = acquireBuffer();
            System.arraycopy(prevResult, 0, result, 0, MAP_SIZE);
            return result;
        }

        if (changeCount > MAP_SIZE / 2) {
            return ditherPixels(pixels, quality);
        }

        boolean[] dilated = new boolean[MAP_SIZE];
        for (int y = 0; y < MAP_WIDTH; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                if (!changed[y * MAP_WIDTH + x]) continue;
                int yMin = Math.max(0, y - 2);
                int yMax = Math.min(MAP_WIDTH - 1, y + 2);
                int xMin = Math.max(0, x - 2);
                int xMax = Math.min(MAP_WIDTH - 1, x + 2);
                for (int dy = yMin; dy <= yMax; dy++) {
                    int rowOff = dy * MAP_WIDTH;
                    for (int dx = xMin; dx <= xMax; dx++) {
                        dilated[rowOff + dx] = true;
                    }
                }
            }
        }

        byte[] fullDither = ditherPixels(pixels, quality);

        for (int i = 0; i < MAP_SIZE; i++) {
            if (!dilated[i]) {
                fullDither[i] = prevResult[i];
            }
        }

        return fullDither;
    }

    private static BufferedImage prepareImage(BufferedImage src, int width, int height) {
        if (src.getWidth() == width && src.getHeight() == height) {
            return src;
        }
        return resize(src, width, height);
    }

    private static final int[][] BAYER_8X8 = {
            { 0, 32,  8, 40,  2, 34, 10, 42},
            {48, 16, 56, 24, 50, 18, 58, 26},
            {12, 44,  4, 36, 14, 46,  6, 38},
            {60, 28, 52, 20, 62, 30, 54, 22},
            { 3, 35, 11, 43,  1, 33,  9, 41},
            {51, 19, 59, 27, 49, 17, 57, 25},
            {15, 47,  7, 39, 13, 45,  5, 37},
            {63, 31, 55, 23, 61, 29, 53, 21}
    };

    private static byte[] ditherOrdered(int[] pixels) {
        byte[] result = acquireBuffer();

        for (int i = 0; i < MAP_SIZE; i++) {
            int rgb = pixels[i];
            if (((rgb >> 24) & 0xFF) < 128) {
                result[i] = 0;
                continue;
            }

            int x = i & 127;
            int y = i >> 7;
            float threshold = (BAYER_8X8[y & 7][x & 7] / 64.0f) - 0.5f;
            float strength = 24.0f;

            int r = clamp((int) (((rgb >> 16) & 0xFF) + threshold * strength));
            int g = clamp((int) (((rgb >> 8) & 0xFF) + threshold * strength));
            int b = clamp((int) ((rgb & 0xFF) + threshold * strength));

            result[i] = matchColor(r, g, b);
        }

        return result;
    }

    private static byte[] ditherFloydSteinberg(int[] pixels) {
        byte[] result = acquireBuffer();

        double[] linR = getLinR();
        double[] linG = getLinG();
        double[] linB = getLinB();
        boolean[] transparent = getTransparent();

        java.util.Arrays.fill(linR, 0.0);
        java.util.Arrays.fill(linG, 0.0);
        java.util.Arrays.fill(linB, 0.0);
        java.util.Arrays.fill(transparent, false);

        for (int i = 0; i < MAP_SIZE; i++) {
            int rgb = pixels[i];
            if (((rgb >> 24) & 0xFF) < 128) {
                transparent[i] = true;
                continue;
            }
            linR[i] = EmageColors.linearize((rgb >> 16) & 0xFF);
            linG[i] = EmageColors.linearize((rgb >> 8) & 0xFF);
            linB[i] = EmageColors.linearize(rgb & 0xFF);
        }

        for (int y = 0; y < MAP_WIDTH; y++) {
            boolean leftToRight = (y % 2 == 0);
            int startX = leftToRight ? 0 : MAP_WIDTH - 1;
            int endX = leftToRight ? MAP_WIDTH : -1;
            int stepX = leftToRight ? 1 : -1;

            for (int x = startX; x != endX; x += stepX) {
                int idx = y * MAP_WIDTH + x;

                if (transparent[idx]) {
                    result[idx] = 0;
                    continue;
                }

                double clampedR = Math.max(0.0, Math.min(1.0, linR[idx]));
                double clampedG = Math.max(0.0, Math.min(1.0, linG[idx]));
                double clampedB = Math.max(0.0, Math.min(1.0, linB[idx]));

                int sr = EmageColors.delinearize(clampedR);
                int sg = EmageColors.delinearize(clampedG);
                int sb = EmageColors.delinearize(clampedB);

                byte match = matchColor(sr, sg, sb);
                result[idx] = match;

                double[] palLin = EmageColors.getLinearRGB(match);
                double eR = clampedR - palLin[0];
                double eG = clampedG - palLin[1];
                double eB = clampedB - palLin[2];

                int nextX = x + stepX;
                int prevX = x - stepX;

                if (nextX >= 0 && nextX < MAP_WIDTH) {
                    int ni = idx + stepX;
                    linR[ni] += eR * 7.0 / 16.0;
                    linG[ni] += eG * 7.0 / 16.0;
                    linB[ni] += eB * 7.0 / 16.0;
                }
                if (y + 1 < MAP_WIDTH) {
                    int nextRow = (y + 1) * MAP_WIDTH;
                    if (prevX >= 0 && prevX < MAP_WIDTH) {
                        int ni = nextRow + prevX;
                        linR[ni] += eR * 3.0 / 16.0;
                        linG[ni] += eG * 3.0 / 16.0;
                        linB[ni] += eB * 3.0 / 16.0;
                    }
                    {
                        int ni = nextRow + x;
                        linR[ni] += eR * 5.0 / 16.0;
                        linG[ni] += eG * 5.0 / 16.0;
                        linB[ni] += eB * 5.0 / 16.0;
                    }
                    if (nextX >= 0 && nextX < MAP_WIDTH) {
                        int ni = nextRow + nextX;
                        linR[ni] += eR * 1.0 / 16.0;
                        linG[ni] += eG * 1.0 / 16.0;
                        linB[ni] += eB * 1.0 / 16.0;
                    }
                }
            }
        }

        return result;
    }

    private static byte[] ditherJarvisGammaCorrected(int[] pixels) {
        byte[] result = acquireBuffer();

        double[] linR = getLinR();
        double[] linG = getLinG();
        double[] linB = getLinB();
        boolean[] transparent = getTransparent();

        java.util.Arrays.fill(linR, 0.0);
        java.util.Arrays.fill(linG, 0.0);
        java.util.Arrays.fill(linB, 0.0);
        java.util.Arrays.fill(transparent, false);

        for (int i = 0; i < MAP_SIZE; i++) {
            int rgb = pixels[i];
            if (((rgb >> 24) & 0xFF) < 128) {
                transparent[i] = true;
                continue;
            }
            linR[i] = EmageColors.linearize((rgb >> 16) & 0xFF);
            linG[i] = EmageColors.linearize((rgb >> 8) & 0xFF);
            linB[i] = EmageColors.linearize(rgb & 0xFF);
        }

        for (int y = 0; y < MAP_WIDTH; y++) {
            for (int x = 0; x < MAP_WIDTH; x++) {
                int idx = y * MAP_WIDTH + x;

                if (transparent[idx]) {
                    result[idx] = 0;
                    continue;
                }

                double clampedR = Math.max(0.0, Math.min(1.0, linR[idx]));
                double clampedG = Math.max(0.0, Math.min(1.0, linG[idx]));
                double clampedB = Math.max(0.0, Math.min(1.0, linB[idx]));

                int sr = EmageColors.delinearize(clampedR);
                int sg = EmageColors.delinearize(clampedG);
                int sb = EmageColors.delinearize(clampedB);

                byte match = matchColor(sr, sg, sb);
                result[idx] = match;

                double[] palLin = EmageColors.getLinearRGB(match);
                double eR = clampedR - palLin[0];
                double eG = clampedG - palLin[1];
                double eB = clampedB - palLin[2];

                distributeError(linR, linG, linB, x + 1, y,     eR, eG, eB, 7.0 / 48.0);
                distributeError(linR, linG, linB, x + 2, y,     eR, eG, eB, 5.0 / 48.0);

                distributeError(linR, linG, linB, x - 2, y + 1, eR, eG, eB, 3.0 / 48.0);
                distributeError(linR, linG, linB, x - 1, y + 1, eR, eG, eB, 5.0 / 48.0);
                distributeError(linR, linG, linB, x,     y + 1, eR, eG, eB, 7.0 / 48.0);
                distributeError(linR, linG, linB, x + 1, y + 1, eR, eG, eB, 5.0 / 48.0);
                distributeError(linR, linG, linB, x + 2, y + 1, eR, eG, eB, 3.0 / 48.0);

                distributeError(linR, linG, linB, x - 2, y + 2, eR, eG, eB, 1.0 / 48.0);
                distributeError(linR, linG, linB, x - 1, y + 2, eR, eG, eB, 3.0 / 48.0);
                distributeError(linR, linG, linB, x,     y + 2, eR, eG, eB, 5.0 / 48.0);
                distributeError(linR, linG, linB, x + 1, y + 2, eR, eG, eB, 3.0 / 48.0);
                distributeError(linR, linG, linB, x + 2, y + 2, eR, eG, eB, 1.0 / 48.0);
            }
        }

        return result;
    }

    private static void distributeError(double[] linR, double[] linG, double[] linB,
                                        int x, int y, double eR, double eG, double eB, double weight) {
        if (x < 0 || x >= MAP_WIDTH || y < 0 || y >= MAP_WIDTH) return;
        int idx = y * MAP_WIDTH + x;
        linR[idx] += eR * weight;
        linG[idx] += eG * weight;
        linB[idx] += eB * weight;
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int current, int total, String stage);
    }

    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames) throws Exception {
        return processGifGrid(url, gridW, gridH, maxFrames, Quality.BALANCED, null);
    }

    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames, Quality quality) throws Exception {
        return processGifGrid(url, gridW, gridH, maxFrames, quality, null);
    }

    @SuppressWarnings("unchecked")
    public static GifGridData processGifGrid(URL url, int gridW, int gridH, int maxFrames, Quality quality, ProgressCallback progress) throws Exception {
        int totalW = gridW * MAP_WIDTH;
        int totalH = gridH * MAP_WIDTH;

        GifData gifData = readGif(url, maxFrames);
        if (gifData.frames.isEmpty()) {
            throw new Exception("No frames found in GIF");
        }

        int frameCount = gifData.frames.size();

        List<byte[]>[][] grid = new List[gridW][gridH];
        for (int gx = 0; gx < gridW; gx++) {
            for (int gy = 0; gy < gridH; gy++) {
                grid[gx][gy] = new ArrayList<>(frameCount);
            }
        }

        int[] allPixels = new int[totalW * totalH];

        int[][][] prevChunkPixels = new int[gridW][gridH][];
        byte[][][] prevChunkResults = new byte[gridW][gridH][];

        for (int f = 0; f < frameCount; f++) {
            BufferedImage frame = gifData.frames.get(f);
            int srcW = frame.getWidth();
            int srcH = frame.getHeight();

            final int frameIdx = f;

            List<Future<?>> tasks = new ArrayList<>(gridW * gridH);
            for (int gy = 0; gy < gridH; gy++) {
                for (int gx = 0; gx < gridW; gx++) {
                    final int cx = gx, cy = gy;

                    final int srcX = cx * srcW / gridW;
                    final int srcY = cy * srcH / gridH;
                    final int srcChunkW = (cx + 1) * srcW / gridW - srcX;
                    final int srcChunkH = (cy + 1) * srcH / gridH - srcY;

                    tasks.add(EXECUTOR.submit(() -> {
                        BufferedImage chunk;
                        if (srcChunkW > 0 && srcChunkH > 0) {
                            BufferedImage sub = frame.getSubimage(srcX, srcY, srcChunkW, srcChunkH);
                            chunk = resize(sub, MAP_WIDTH, MAP_WIDTH);
                        } else {
                            chunk = new BufferedImage(MAP_WIDTH, MAP_WIDTH, BufferedImage.TYPE_INT_ARGB);
                        }

                        int[] chunkPx = new int[MAP_SIZE];
                        chunk.getRGB(0, 0, MAP_WIDTH, MAP_WIDTH, chunkPx, 0, MAP_WIDTH);

                        byte[] dithered;
                        if (frameIdx > 0 && prevChunkPixels[cx][cy] != null) {
                            dithered = ditherPixelsStable(chunkPx, prevChunkPixels[cx][cy],
                                    prevChunkResults[cx][cy], quality);
                        } else {
                            dithered = ditherPixels(chunkPx, quality);
                        }

                        synchronized (grid[cx][cy]) {
                            grid[cx][cy].add(dithered);
                        }

                        if (prevChunkPixels[cx][cy] == null) {
                            prevChunkPixels[cx][cy] = new int[MAP_SIZE];
                        }
                        System.arraycopy(chunkPx, 0, prevChunkPixels[cx][cy], 0, MAP_SIZE);
                        prevChunkResults[cx][cy] = dithered;
                    }));
                }
            }

            for (Future<?> task : tasks) {
                task.get();
            }

            gifData.frames.set(f, null);

            if (progress != null && (f % 10 == 0 || f == frameCount - 1)) {
                int pct = (int) ((f + 1) * 100.0 / frameCount);
                progress.onProgress(f + 1, frameCount, "Dithering frame " + (f + 1) + "/" + frameCount + " (" + pct + "%)");
            }
        }

        int avgDelay = gifData.delays.isEmpty() ? 100 :
                (int) gifData.delays.stream().mapToInt(Integer::intValue).average().orElse(100);

        return new GifGridData(grid, gifData.delays, avgDelay, gridW, gridH);
    }

    private static InputStream openLimitedStream(URL url) throws IOException {
        return openLimitedStream(url, getMaxRedirects());
    }

    private static InputStream openLimitedStream(URL url, int remainingRedirects) throws IOException {
        if (!ALLOWED_SCHEMES.contains(url.getProtocol().toLowerCase())) {
            throw new IOException("Only HTTP/HTTPS URLs are allowed");
        }

        if (remainingRedirects <= 0) {
            throw new IOException("Too many redirects");
        }

        resolveAndValidate(url.getHost());

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(getConnectTimeout());
        conn.setReadTimeout(getReadTimeout());
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 Emage-Plugin");
        conn.setRequestProperty("Host", url.getHost());

        conn.connect();

        resolveAndValidate(url.getHost());

        int status = conn.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String redirect = conn.getHeaderField("Location");
            conn.disconnect();
            if (redirect != null) {
                URL redirectUrl = new URL(url, redirect);
                resolveAndValidate(redirectUrl.getHost());
                return openLimitedStream(redirectUrl, remainingRedirects - 1);
            }
            throw new IOException("Redirect without Location header");
        }

        if (status != 200) {
            conn.disconnect();
            throw new IOException("HTTP error: " + status);
        }

        long maxBytes = getMaxDownloadBytes();
        long contentLength = conn.getContentLengthLong();
        if (contentLength > maxBytes) {
            conn.disconnect();
            throw new IOException("File too large: " + contentLength + " bytes (max " + maxBytes + ")");
        }

        return new LimitedInputStream(conn.getInputStream(), maxBytes);
    }

    private static java.net.InetAddress resolveAndValidate(String host) throws IOException {
        java.net.InetAddress address;
        try {
            Future<java.net.InetAddress> dnsFuture = EXECUTOR.submit(
                    () -> java.net.InetAddress.getByName(host));
            address = dnsFuture.get(5, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IOException("DNS resolution timed out for host: " + host);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("DNS resolution interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException("DNS resolution failed: " +
                    (cause != null ? cause.getMessage() : e.getMessage()));
        }

        validateAddress(address);
        return address;
    }

    private static void validateAddress(java.net.InetAddress address) throws IOException {
        if (!shouldBlockInternalUrls()) return;

        if (address.isLoopbackAddress() ||
                address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() ||
                address.isAnyLocalAddress()) {
            throw new IOException("URLs pointing to internal/local networks are not allowed");
        }

        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            boolean isMapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) { isMapped = false; break; }
            }
            if (isMapped && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                byte[] v4 = new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
                java.net.InetAddress v4Addr = java.net.InetAddress.getByAddress(v4);
                if (v4Addr.isLoopbackAddress() ||
                        v4Addr.isLinkLocalAddress() ||
                        v4Addr.isSiteLocalAddress() ||
                        v4Addr.isAnyLocalAddress()) {
                    throw new IOException("URLs pointing to internal/local networks are not allowed");
                }
            }
        }
    }

    private static class LimitedInputStream extends FilterInputStream {
        private long remaining;
        private final long limit;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) throw new IOException("Download size limit exceeded (" + limit + " bytes)");
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) throw new IOException("Download size limit exceeded (" + limit + " bytes)");
            int toRead = (int) Math.min(len, remaining);
            int read = super.read(b, off, toRead);
            if (read > 0) remaining -= read;
            return read;
        }
    }

    private static GifData readGif(URL url, int maxFrames) throws Exception {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();

        long maxDecodedBytes = activeConfig != null
                ? activeConfig.getMaxMemoryMB() * 1024 * 1024
                : 256L * 1024 * 1024;
        long totalDecodedBytes = 0;

        try (InputStream is = new BufferedInputStream(openLimitedStream(url), 65536);
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

            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(
                            streamMeta.getNativeMetadataFormatName());
                    NodeList lsdNodes = root.getElementsByTagName("LogicalScreenDescriptor");
                    if (lsdNodes.getLength() > 0) {
                        IIOMetadataNode lsd = (IIOMetadataNode) lsdNodes.item(0);
                        String w = lsd.getAttribute("logicalScreenWidth");
                        String h = lsd.getAttribute("logicalScreenHeight");
                        if (w != null && !w.isEmpty()) canvasWidth = Integer.parseInt(w);
                        if (h != null && !h.isEmpty()) canvasHeight = Integer.parseInt(h);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not read GIF logical screen descriptor", e);
            }

            if (canvasWidth > 4096 || canvasHeight > 4096) {
                reader.dispose();
                throw new Exception("GIF dimensions too large: " + canvasWidth + "x" + canvasHeight +
                        " (max 4096x4096)");
            }

            Color gifBackgroundColor = null;
            try {
                IIOMetadata streamMeta = reader.getStreamMetadata();
                if (streamMeta != null) {
                    IIOMetadataNode root = (IIOMetadataNode) streamMeta.getAsTree(
                            streamMeta.getNativeMetadataFormatName());

                    NodeList lsdNodes = root.getElementsByTagName("LogicalScreenDescriptor");
                    int bgColorIndex = 0;
                    if (lsdNodes.getLength() > 0) {
                        IIOMetadataNode lsd = (IIOMetadataNode) lsdNodes.item(0);
                        String bgIdx = lsd.getAttribute("backgroundColorIndex");
                        if (bgIdx != null && !bgIdx.isEmpty()) {
                            bgColorIndex = Integer.parseInt(bgIdx);
                        }
                    }

                    NodeList gctNodes = root.getElementsByTagName("GlobalColorTable");
                    if (gctNodes.getLength() > 0) {
                        IIOMetadataNode gct = (IIOMetadataNode) gctNodes.item(0);
                        NodeList entries = gct.getElementsByTagName("ColorTableEntry");
                        for (int ei = 0; ei < entries.getLength(); ei++) {
                            IIOMetadataNode entry = (IIOMetadataNode) entries.item(ei);
                            String idxStr = entry.getAttribute("index");
                            if (idxStr != null && Integer.parseInt(idxStr) == bgColorIndex) {
                                int red = Integer.parseInt(entry.getAttribute("red"));
                                int green = Integer.parseInt(entry.getAttribute("green"));
                                int blue = Integer.parseInt(entry.getAttribute("blue"));
                                gifBackgroundColor = new Color(red, green, blue, 255);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.log(Level.FINE, "Could not read GIF background color", e);
            }

            BufferedImage firstFrame = reader.read(0);
            if (canvasWidth <= 0 || canvasHeight <= 0) {
                canvasWidth = firstFrame.getWidth();
                canvasHeight = firstFrame.getHeight();
            }

            long canvasBytes = (long) canvasWidth * canvasHeight * 4;
            if (canvasBytes > maxDecodedBytes / 4) {
                reader.dispose();
                throw new Exception("GIF canvas too large: would require " +
                        (canvasBytes / 1024 / 1024) + "MB per frame");
            }

            BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D canvasG = canvas.createGraphics();
            canvasG.setBackground(new Color(0, 0, 0, 0));
            canvasG.clearRect(0, 0, canvasWidth, canvasHeight);

            BufferedImage restoreCanvas = null;

            final Color finalBgColor = gifBackgroundColor;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage rawFrame;
                try {
                    rawFrame = (i == 0) ? firstFrame : reader.read(i);
                } catch (Exception e) {
                    logger.log(Level.FINE, "Failed to read GIF frame " + i, e);
                    break;
                }
                if (rawFrame == null) break;

                long frameBytes = (long) rawFrame.getWidth() * rawFrame.getHeight() * 4;
                totalDecodedBytes += frameBytes + canvasBytes; // frame + canvas copy
                if (totalDecodedBytes > maxDecodedBytes) {
                    logger.warning("GIF exceeded decoded memory limit at frame " + i +
                            " (" + (totalDecodedBytes / 1024 / 1024) + "MB). Truncating.");
                    break;
                }

                int delay = 50;
                String disposal = "none";
                int frameX = 0, frameY = 0;
                int frameW = rawFrame.getWidth();
                int frameH = rawFrame.getHeight();

                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    if (meta != null) {
                        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(
                                meta.getNativeMetadataFormatName());

                        NodeList gceNodes = root.getElementsByTagName("GraphicControlExtension");
                        if (gceNodes.getLength() > 0) {
                            IIOMetadataNode gce = (IIOMetadataNode) gceNodes.item(0);
                            String delayStr = gce.getAttribute("delayTime");
                            if (delayStr != null && !delayStr.isEmpty()) {
                                int d = Integer.parseInt(delayStr);
                                delay = d <= 1 ? 50 : d * 10;
                            }
                            String disp = gce.getAttribute("disposalMethod");
                            if (disp != null) disposal = disp;
                        }

                        NodeList descNodes = root.getElementsByTagName("ImageDescriptor");
                        if (descNodes.getLength() > 0) {
                            IIOMetadataNode desc = (IIOMetadataNode) descNodes.item(0);
                            String x = desc.getAttribute("imageLeftPosition");
                            String y = desc.getAttribute("imageTopPosition");
                            if (x != null && !x.isEmpty()) frameX = Integer.parseInt(x);
                            if (y != null && !y.isEmpty()) frameY = Integer.parseInt(y);
                        }
                    }
                } catch (Exception e) {
                    logger.log(Level.FINE, "Failed to read metadata for GIF frame " + i, e);
                }

                if ("restoreToPrevious".equalsIgnoreCase(disposal)) {
                    restoreCanvas = copyImage(canvas);
                }

                canvasG.drawImage(rawFrame, frameX, frameY, null);

                frames.add(copyImage(canvas));
                delays.add(Math.max(20, delay));

                if ("restoreToBackgroundColor".equalsIgnoreCase(disposal)) {
                    if (finalBgColor != null) {
                        canvasG.setComposite(AlphaComposite.Src);
                        canvasG.setColor(finalBgColor);
                        canvasG.fillRect(frameX, frameY, frameW, frameH);
                        canvasG.setComposite(AlphaComposite.SrcOver);
                    } else {
                        canvasG.setComposite(AlphaComposite.Clear);
                        canvasG.fillRect(frameX, frameY, frameW, frameH);
                        canvasG.setComposite(AlphaComposite.SrcOver);
                    }
                } else if ("restoreToPrevious".equalsIgnoreCase(disposal) && restoreCanvas != null) {
                    canvasG.setComposite(AlphaComposite.Src);
                    canvasG.drawImage(restoreCanvas, 0, 0, null);
                    canvasG.setComposite(AlphaComposite.SrcOver);
                }
            }

            canvasG.dispose();
            reader.dispose();
        }

        if (frames.isEmpty()) {
            throw new Exception("No frames could be decoded from GIF");
        }

        return new GifData(frames, delays);
    }

    private static double[] getLinR() {
        double[] arr = TL_LIN_R.get();
        if (arr == null) {
            arr = new double[MAP_SIZE];
            TL_LIN_R.set(arr);
        }
        return arr;
    }

    private static double[] getLinG() {
        double[] arr = TL_LIN_G.get();
        if (arr == null) {
            arr = new double[MAP_SIZE];
            TL_LIN_G.set(arr);
        }
        return arr;
    }

    private static double[] getLinB() {
        double[] arr = TL_LIN_B.get();
        if (arr == null) {
            arr = new double[MAP_SIZE];
            TL_LIN_B.set(arr);
        }
        return arr;
    }

    private static boolean[] getTransparent() {
        boolean[] arr = TL_TRANSPARENT.get();
        if (arr == null) {
            arr = new boolean[MAP_SIZE];
            TL_TRANSPARENT.set(arr);
        }
        return arr;
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
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to decompress animation data via grid format", e);
        }

        try {
            byte[] mapData = EmageCompression.decompressSingleStatic(data);
            if (mapData != null && mapData.length == MAP_SIZE) {
                List<byte[]> frames = new ArrayList<>();
                frames.add(mapData);
                List<Integer> delays = new ArrayList<>();
                delays.add(100);
                return new AnimData(frames, delays, 100, 0L);
            }
        } catch (Exception e) {
            logger.log(Level.FINE, "Failed to decompress animation as static fallback", e);
        }

        List<byte[]> frames = new ArrayList<>();
        frames.add(new byte[MAP_SIZE]);
        List<Integer> delays = new ArrayList<>();
        delays.add(100);
        return new AnimData(frames, delays, 100, 0L);
    }

    public static void shutdown() {
        EXECUTOR.shutdown();
        try {
            if (!EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                EXECUTOR.shutdownNow();
            }
        } catch (InterruptedException e) {
            EXECUTOR.shutdownNow();
            Thread.currentThread().interrupt();
        }

        TL_LIN_R.remove();
        TL_LIN_G.remove();
        TL_LIN_B.remove();
        TL_TRANSPARENT.remove();

        clearAllPools();
    }

    public static BufferedImage downloadImage(URL url) throws Exception {
        try (InputStream is = new BufferedInputStream(openLimitedStream(url), 65536)) {
            BufferedImage img = ImageIO.read(is);
            if (img == null) {
                throw new IOException("Failed to decode image");
            }
            return img;
        }
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