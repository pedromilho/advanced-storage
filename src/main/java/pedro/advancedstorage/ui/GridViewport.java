package pedro.advancedstorage.ui;

/** Logical item list -> fixed visible cells. Independent of Hytale rendering and inventories. */
public final class GridViewport {
    private final int columns,rows;
    private int itemCount,firstRow;
    public GridViewport(int columns,int rows){if(columns<1||rows<1)throw new IllegalArgumentException("Invalid viewport");this.columns=columns;this.rows=rows;}
    public int capacity(){return columns*rows;}
    public int firstRow(){return firstRow;}
    public int maxFirstRow(){return Math.max(0,(itemCount+columns-1)/columns-rows);}
    public int start(){return firstRow*columns;}
    public int itemCount(){return itemCount;}
    public void setItemCount(int count){if(count<0)throw new IllegalArgumentException("Negative items");itemCount=count;setFirstRow(firstRow);}
    public boolean setFirstRow(int row){int next=Math.max(0,Math.min(row,maxFirstRow()));boolean changed=firstRow!=next;firstRow=next;return changed;}
    public int absoluteIndex(int visibleSlot){if(visibleSlot<0||visibleSlot>=capacity())throw new IndexOutOfBoundsException(visibleSlot);return start()+visibleSlot;}
    public int visibleCount(){return Math.max(0,Math.min(capacity(),itemCount-start()));}
    public Cell cell(int visibleSlot,int left,int top,int step){if(visibleSlot<0||visibleSlot>=capacity())throw new IndexOutOfBoundsException(visibleSlot);return new Cell(left+(visibleSlot%columns)*step,top+(visibleSlot/columns)*step,step,step);}
    public record Cell(int x,int y,int width,int height){}
}
