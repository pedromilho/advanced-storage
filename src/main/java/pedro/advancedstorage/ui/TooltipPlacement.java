package pedro.advancedstorage.ui;

/** Places the tooltip in the same coordinate space as its cell. */
public final class TooltipPlacement {
    private TooltipPlacement(){}
    public record Box(int left,int top,int width,int height,int arrowLeft,boolean below){}
    public static Box above(GridViewport.Cell cell,int width,int height,int pageWidth,int pageHeight) {
        return place(cell,width,height,pageWidth,pageHeight,false);
    }
    public static Box place(GridViewport.Cell cell,int width,int height,int pageWidth,int pageHeight,boolean allowBelow){
        int center=cell.x()+cell.width()/2;
        int left=Math.max(0,Math.min(pageWidth-width,center-width/2));
        int above=cell.y()-height-8;
        int below=cell.y()+cell.height()+8;
        boolean placeBelow=allowBelow && above<0 && below+height<=pageHeight;
        int top=placeBelow?below:Math.max(0,Math.min(Math.max(0,pageHeight-height-8),above));
        return new Box(left,top,width,height,Math.max(12,Math.min(width-45,center-left-16)),placeBelow);
    }
}
