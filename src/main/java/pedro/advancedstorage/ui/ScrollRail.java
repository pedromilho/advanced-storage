package pedro.advancedstorage.ui;

/** Geometry and row mapping for a vertical Catalog scrollbar. */
public record ScrollRail(int visibleRows, int maxFirstRow, int firstRow) {
    private static final int SLOT_SIZE = 56;
    private static final int SLOT_SPACING = 4;
    private static final int ARROW_HEIGHT = 16;
    private static final int MIN_THUMB_HEIGHT = 16;

    public ScrollRail {
        if (visibleRows < 1 || maxFirstRow < 0 || firstRow < 0 || firstRow > maxFirstRow) {
            throw new IllegalArgumentException("Invalid scrollbar viewport");
        }
    }

    public int railHeight() {
        return visibleRows * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING;
    }

    public int trackHeight() {
        return railHeight() - 2 * ARROW_HEIGHT;
    }

    public int thumbHeight() {
        return Math.max(MIN_THUMB_HEIGHT,
            (int) Math.round((double) trackHeight() * visibleRows / (visibleRows + (double) maxFirstRow)));
    }

    public int thumbTop() {
        return maxFirstRow == 0 ? 0 : (int) Math.round((double) firstRow * thumbTravel() / maxFirstRow);
    }

    public boolean segmentTouchesThumb(int segment, int segmentCount) {
        if (maxFirstRow == 0 || segment < 0 || segment >= segmentCount) return false;
        double segmentTop = (double) segment * trackHeight() / segmentCount;
        double segmentBottom = (double) (segment + 1) * trackHeight() / segmentCount;
        return segmentTop < thumbTop() + thumbHeight() && segmentBottom > thumbTop();
    }

    public int rowForSegment(int segment, int segmentCount) {
        if (segmentCount <= 1) return firstRow;
        int boundedSegment = Math.clamp(segment, 0, segmentCount - 1);
        return (int) Math.round((double) boundedSegment * maxFirstRow / (segmentCount - 1));
    }

    public int rowForDrag(int originRow, int pixelDelta, double pixelsPerUiUnit) {
        int start = Math.clamp(originRow, 0, maxFirstRow);
        int travel = thumbTravel();
        if (travel == 0 || !Double.isFinite(pixelsPerUiUnit) || pixelsPerUiUnit <= 0) return start;
        double row = start + ((pixelDelta / pixelsPerUiUnit) * maxFirstRow / travel);
        return (int) Math.round(Math.clamp(row, 0, maxFirstRow));
    }

    private int thumbTravel() {
        return trackHeight() - thumbHeight();
    }
}
