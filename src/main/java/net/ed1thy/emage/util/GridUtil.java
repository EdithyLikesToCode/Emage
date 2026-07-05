package net.ed1thy.emage.util;

import org.bukkit.block.BlockFace;
import org.bukkit.entity.ItemFrame;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;

public class GridUtil {

    public record GridData(List<ItemFrame> frames, int columns, int rows) {}

    private record GridVectors(int colDx, int colDy, int colDz, int rowDx, int rowDy, int rowDz) {
        public int getColOffset(int dx, int dy, int dz) {
            return dx * colDx + dy * colDy + dz * colDz;
        }
        public int getRowOffset(int dx, int dy, int dz) {
            return dx * rowDx + dy * rowDy + dz * rowDz;
        }
    }

    public static class MissingFrameException extends Exception {
        public final int x, y, z;
        public MissingFrameException(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    }

    private static GridVectors getVectors(BlockFace facing) {
        return switch (facing) {
            case NORTH -> new GridVectors(-1, 0, 0, 0, -1, 0);
            case SOUTH -> new GridVectors(1, 0, 0, 0, -1, 0);
            case EAST  -> new GridVectors(0, 0, -1, 0, -1, 0);
            case WEST  -> new GridVectors(0, 0, 1, 0, -1, 0);
            case UP    -> new GridVectors(1, 0, 0, 0, 0, 1);
            case DOWN  -> new GridVectors(1, 0, 0, 0, 0, 1);
            default -> new GridVectors(0, 0, 0, 0, 0, 0);
        };
    }

    private static long getBlockKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    @Nullable
    public static GridData detectGrid(@NotNull ItemFrame clickedFrame, int inputCols, int inputRows, int maxLimit, @NotNull Predicate<ItemFrame> frameFilter) throws MissingFrameException {
        GridVectors v = getVectors(clickedFrame.getFacing());
        if (v.colDx() == 0 && v.colDy() == 0 && v.colDz() == 0) return null;

        int searchRadius = maxLimit + 2;
        Map<Long, ItemFrame> cache = new HashMap<>();
        for (org.bukkit.entity.Entity e : clickedFrame.getWorld().getNearbyEntities(clickedFrame.getLocation(), searchRadius, searchRadius, searchRadius)) {
            if (e instanceof ItemFrame f && f.getFacing() == clickedFrame.getFacing()) {
                cache.put(getBlockKey(f.getLocation().getBlockX(), f.getLocation().getBlockY(), f.getLocation().getBlockZ()), f);
            }
        }

        Set<Long> visitedCoords = new HashSet<>();
        Set<ItemFrame> visitedFrames = new HashSet<>();
        Queue<ItemFrame> queue = new LinkedList<>();
        queue.add(clickedFrame);
        visitedFrames.add(clickedFrame);

        int minCol = 0, maxCol = 0;
        int minRow = 0, maxRow = 0;

        int startX = clickedFrame.getLocation().getBlockX();
        int startY = clickedFrame.getLocation().getBlockY();
        int startZ = clickedFrame.getLocation().getBlockZ();

        while (!queue.isEmpty()) {
            ItemFrame curr = queue.poll();
            int dx = curr.getLocation().getBlockX() - startX;
            int dy = curr.getLocation().getBlockY() - startY;
            int dz = curr.getLocation().getBlockZ() - startZ;

            int col = v.getColOffset(dx, dy, dz);
            int row = v.getRowOffset(dx, dy, dz);

            long coordKey = ((long) col << 32) | (row & 0xFFFFFFFFL);
            visitedCoords.add(coordKey);

            minCol = Math.min(minCol, col);
            maxCol = Math.max(maxCol, col);
            minRow = Math.min(minRow, row);
            maxRow = Math.max(maxRow, row);

            for (int dc = -1; dc <= 1; dc++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (dc == 0 && dr == 0) continue;
                    int nx = curr.getLocation().getBlockX() + dc * v.colDx() + dr * v.rowDx();
                    int ny = curr.getLocation().getBlockY() + dc * v.colDy() + dr * v.rowDy();
                    int nz = curr.getLocation().getBlockZ() + dc * v.colDz() + dr * v.rowDz();

                    ItemFrame neighbor = cache.get(getBlockKey(nx, ny, nz));
                    if (neighbor != null && !visitedFrames.contains(neighbor) && frameFilter.test(neighbor)) {
                        visitedFrames.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
        }

        int columns, rows;
        int idealTopLeftCol = minCol;
        int idealTopLeftRow = minRow;

        if (inputCols == -1 && inputRows == -1) {
            int bestArea = 0;
            int bestMinCol = 0, bestMaxCol = 0, bestMinRow = 0, bestMaxRow = 0;

            for (int c1 = minCol; c1 <= 0; c1++) {
                for (int c2 = 0; c2 <= maxCol; c2++) {
                    int width = c2 - c1 + 1;
                    if (width > maxLimit) continue;

                    int r1 = 0;
                    while (r1 > minRow) {
                        boolean valid = true;
                        for (int c = c1; c <= c2; c++) {
                            long key = ((long) c << 32) | ((r1 - 1) & 0xFFFFFFFFL);
                            if (!visitedCoords.contains(key)) {
                                valid = false;
                                break;
                            }
                        }
                        if (!valid) break;
                        r1--;
                    }

                    int r2 = 0;
                    while (r2 < maxRow) {
                        boolean valid = true;
                        for (int c = c1; c <= c2; c++) {
                            long key = ((long) c << 32) | ((r2 + 1) & 0xFFFFFFFFL);
                            if (!visitedCoords.contains(key)) {
                                valid = false;
                                break;
                            }
                        }
                        if (!valid) break;
                        r2++;
                    }

                    int height = r2 - r1 + 1;
                    int actualR1 = r1;
                    int actualR2 = r2;
                    if (height > maxLimit) {
                        actualR2 = actualR1 + maxLimit - 1;
                        if (actualR2 < 0) {
                            actualR1 = -maxLimit + 1;
                            actualR2 = 0;
                        }
                    }

                    int area = width * (actualR2 - actualR1 + 1);
                    if (area > bestArea) {
                        bestArea = area;
                        bestMinCol = c1;
                        bestMaxCol = c2;
                        bestMinRow = actualR1;
                        bestMaxRow = actualR2;
                    }
                }
            }

            if (bestArea == 0) return null;

            columns = bestMaxCol - bestMinCol + 1;
            rows = bestMaxRow - bestMinRow + 1;
            idealTopLeftCol = bestMinCol;
            idealTopLeftRow = bestMinRow;
        } else {
            columns = inputCols == -1 ? (maxCol - minCol + 1) : inputCols;
            rows = inputRows == -1 ? (maxRow - minRow + 1) : inputRows;

            if (columns > maxLimit || rows > maxLimit) {
                return null;
            }

            if (maxCol - minCol + 1 > columns) {
                idealTopLeftCol = -(columns / 2);
                if (idealTopLeftCol < minCol) idealTopLeftCol = minCol;
                if (idealTopLeftCol + columns - 1 > maxCol) idealTopLeftCol = maxCol - columns + 1;
            }
            if (maxRow - minRow + 1 > rows) {
                idealTopLeftRow = -(rows / 2);
                if (idealTopLeftRow < minRow) idealTopLeftRow = minRow;
                if (idealTopLeftRow + rows - 1 > maxRow) idealTopLeftRow = maxRow - rows + 1;
            }
        }

        int topLeftX = startX + idealTopLeftCol * v.colDx() + idealTopLeftRow * v.rowDx();
        int topLeftY = startY + idealTopLeftCol * v.colDy() + idealTopLeftRow * v.rowDy();
        int topLeftZ = startZ + idealTopLeftCol * v.colDz() + idealTopLeftRow * v.rowDz();

        List<ItemFrame> grid = new ArrayList<>(columns * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < columns; c++) {
                int tx = topLeftX + c * v.colDx() + r * v.rowDx();
                int ty = topLeftY + c * v.colDy() + r * v.rowDy();
                int tz = topLeftZ + c * v.colDz() + r * v.rowDz();

                ItemFrame f = cache.get(getBlockKey(tx, ty, tz));
                if (f == null || !frameFilter.test(f)) {
                    throw new MissingFrameException(tx, ty, tz);
                }
                grid.add(f);
            }
        }

        return new GridData(grid, columns, rows);
    }
}