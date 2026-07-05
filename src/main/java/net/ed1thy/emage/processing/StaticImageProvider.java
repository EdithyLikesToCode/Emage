package net.ed1thy.emage.processing;

import java.awt.image.BufferedImage;

public class StaticImageProvider implements ImageFrameProvider {
    private final BufferedImage image;
    private int[] pixels;

    public StaticImageProvider(BufferedImage image) {
        this.image = image;
    }

    @Override public int getFrameCount() { return 1; }
    @Override public int getDelayMs() { return 100; }
    @Override public int getFrameDelayMs(int index) { return 100; }
    @Override public int getFrameWidth(int index) { return image.getWidth(); }
    @Override public int getFrameHeight(int index) { return image.getHeight(); }

    @Override
    public int[] getFramePixels(int index) {
        if (pixels == null) {
            pixels = new int[image.getWidth() * image.getHeight()];
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
        }
        return pixels;
    }

    @Override
    public void close() throws Exception {}
}