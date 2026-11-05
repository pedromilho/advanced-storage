package pedro.advancedstorage;

import com.hypixel.hytale.server.core.Options;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import pedro.advancedstorage.ui.WindowsMouseInput;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Objects;

/** Converts local mouse gestures into changes to the Catalog's row viewport. */
final class CatalogMouseControls implements AutoCloseable {
    private static final int WHEEL_NOTCH = 120;
    private static final int ROWS_PER_NOTCH = 3;
    private static final int QUEUE_LIMIT = 64;

    private final CatalogSettingsPage page;
    private final WindowsMouseInput input;
    private final ArrayDeque<WindowsMouseInput.Sample> queue = new ArrayDeque<>();
    private boolean scheduled;
    private boolean closed;
    private boolean pointerButtonHeld;

    private volatile WindowsMouseInput.Sample pointer;
    private volatile int trackSegment = -1;
    private int grid = -1;
    private int slot = -1;
    private int wheelRemainder;
    private int scaleSegment = -1;
    private int scaleCursorY;
    private int scaleTrackHeight;
    private double pixelsPerUiUnit = 1;
    private int dragGrid = -1;
    private int dragStartRow;
    private int dragStartY;
    private double dragScale;
    private long suppressClickUntil;

    static int clientPid(PlayerRef player) {
        if(player==null || !System.getProperty("os.name","").toLowerCase(Locale.ROOT).startsWith("windows")) return -1;
        var options=Options.getOptionSet();
        if(options==null || !options.has(Options.SINGLEPLAYER)
            || !Objects.equals(options.valueOf(Options.OWNER_UUID),player.getUuid())) return -1;
        Integer pid=options.valueOf(Options.CLIENT_PID);
        return pid==null?-1:pid;
    }

    static CatalogMouseControls start(CatalogSettingsPage page,PlayerRef player) {
        int pid=clientPid(player);
        if(pid<=0) return null;
        try {
            return new CatalogMouseControls(page,pid);
        } catch(RuntimeException | LinkageError failure) {
            CatalogSettingsPage.LOGGER.atWarning().withCause(failure).log("Could not start local Catalog mouse input");
            return null;
        }
    }

    private CatalogMouseControls(CatalogSettingsPage page,int pid) {
        this.page=page;
        input=WindowsMouseInput.start(pid,this::receive);
    }

    private void receive(WindowsMouseInput.Sample sample) {
        pointer=sample;
        synchronized(queue) {
            if(closed) return;
            boolean release=(sample.leftUp() || sample.focusLost()) && pointerButtonHeld;
            if(sample.leftDown()) pointerButtonHeld=trackSegment>=0;
            if(sample.leftUp() || sample.focusLost()) pointerButtonHeld=false;
            boolean movement=sample.motion() && pointerButtonHeld && !sample.leftDown() && !sample.leftUp();
            if(sample.wheelDelta()==0 && !sample.focusLost() && !release
                && !(sample.leftDown() && pointerButtonHeld) && !movement) return;
            if(movement && motionOnly(queue.peekLast())) queue.removeLast();
            if(queue.size()==QUEUE_LIMIT) queue.removeFirst();
            queue.addLast(sample);
            if(scheduled) return;
            scheduled=true;
        }
        page.world.execute(this::drain);
    }

    private static boolean motionOnly(WindowsMouseInput.Sample sample) {
        return sample!=null && sample.motion() && sample.wheelDelta()==0
            && !sample.leftDown() && !sample.leftUp() && !sample.focusLost();
    }

    private void drain() {
        var samples=new ArrayDeque<WindowsMouseInput.Sample>();
        synchronized(queue) {
            scheduled=false;
            if(closed) return;
            samples.addAll(queue);
            queue.clear();
        }
        if(!page.active) return;
        var rail=page.scrollRail();
        int activeGrid=page.scrollGrid();
        int target=rail.firstRow();
        for(var sample:samples) {
            if(sample.focusLost()) {
                dragGrid=-1;
                trackSegment=-1;
                grid=-1;
                scaleSegment=-1;
                wheelRemainder=0;
                suppressClickUntil=0;
                continue;
            }
            if(dragGrid!=activeGrid) dragGrid=-1;
            if(sample.leftDown()) {
                suppressClickUntil=0;
                if(rail.segmentTouchesThumb(trackSegment,CatalogSettingsPage.SCROLL_SEGMENTS)) {
                    dragGrid=activeGrid;
                    dragStartRow=target;
                    dragStartY=sample.cursorY();
                    dragScale=pixelsPerUiUnit;
                }
            }
            if(dragGrid==activeGrid && (sample.motion() || sample.leftUp())) {
                target=rail.rowForDrag(dragStartRow,sample.cursorY()-dragStartY,dragScale);
            }
            if(sample.leftUp() && dragGrid==activeGrid) {
                dragGrid=-1;
                // The legacy button may also activate when the pointer is released over the track.
                suppressClickUntil=System.nanoTime()+500_000_000L;
            }
            if(sample.wheelDelta()!=0 && dragGrid<0 && (grid==activeGrid || trackSegment>=0)) {
                wheelRemainder+=sample.wheelDelta();
                int notches=wheelRemainder/WHEEL_NOTCH;
                wheelRemainder%=WHEEL_NOTCH;
                target=Math.max(0,Math.min(rail.maxFirstRow(),target-notches*ROWS_PER_NOTCH));
            }
        }
        if(target!=rail.firstRow()) page.scrollToRow(target);
    }

    void gridHover(int grid,int slot,boolean entered) {
        if(entered) {
            this.grid=grid;
            this.slot=slot;
            trackSegment=-1;
        } else if(this.grid==grid && this.slot==slot) {
            this.grid=-1;
        }
    }

    void trackHover(int segment,boolean entered) {
        if(!entered) {
            if(trackSegment==segment) trackSegment=-1;
            return;
        }
        grid=-1;
        trackSegment=segment;
        var position=pointer;
        if(position==null || position.focusLost()) return;
        int height=page.scrollRail().trackHeight();
        if(height==scaleTrackHeight && Math.abs(segment-scaleSegment)==1 && scaleSegment>=0) {
            double scale=(position.cursorY()-scaleCursorY)*(double)CatalogSettingsPage.SCROLL_SEGMENTS
                /((segment-scaleSegment)*height);
            if(scale>=0.5 && scale<=3) pixelsPerUiUnit=(pixelsPerUiUnit+scale)/2;
        }
        scaleSegment=segment;
        scaleCursorY=position.cursorY();
        scaleTrackHeight=height;
    }

    boolean consumeTrackClick() {
        if(dragGrid>=0 || System.nanoTime()<suppressClickUntil) {
            suppressClickUntil=0;
            return true;
        }
        return false;
    }

    @Override public void close() {
        synchronized(queue) {
            closed=true;
            queue.clear();
        }
        input.close();
    }
}
