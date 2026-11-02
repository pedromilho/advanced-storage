package pedro.advancedstorage;

import com.google.gson.*;

/** The ItemGrid client adds slot, modifier and drag-source fields to each event envelope. */
public final class CatalogInput {
    public String action="",session="",search,itemId;
    public Integer section,slot,sourceSection,sourceSlot,quantity,clickCount,gridVersion;
    public boolean shift,right,dragRight,explicitButton;
    public static CatalogInput parse(String raw) {
        if(raw==null || raw.length()>16384) throw new IllegalArgumentException("Invalid UI event");
        var object=JsonParser.parseString(raw).getAsJsonObject();
        var data=new CatalogInput();
        data.action=text(object,"Action","");data.session=text(object,"Session","");
        data.search=text(object,"@Search",null);
        data.section=number(object,"Section");data.slot=number(object,"SlotIndex");
        data.sourceSection=first(number(object,"DragSourceInventorySectionId"),number(object,"SourceInventorySectionId"));
        data.sourceSlot=first(number(object,"DragSourceSlotId"),number(object,"SourceSlotId"));
        data.gridVersion=number(object,"GridVersion");
        data.clickCount=number(object,"ClickCount");
        data.quantity=first(number(object,"DragItemStackQuantity"),number(object,"ItemStackQuantity"));
        data.itemId=text(object,"DragItemStackId",text(object,"ItemStackId",null));
        data.shift=object.has("ShiftHeld") && !object.get("ShiftHeld").isJsonNull() && object.get("ShiftHeld").getAsBoolean();
        String button=text(object,"ClickMouseButton",text(object,"MouseButton",text(object,"Button",null)));
        // PressedMouseButton on Dropped is the pickup button, not necessarily the placement button.
        if(button==null && (data.action.equals("pick") || data.action.equals("double"))) button=text(object,"PressedMouseButton",null);
        data.explicitButton=button!=null;
        data.right=isRight(button);
        data.dragRight=isRight(text(object,"DragPressedMouseButton",text(object,"PressedMouseButton",null)));
        return data;
    }
    private static boolean isRight(String button) {
        return button!=null && (button.equals("3") || button.toLowerCase(java.util.Locale.ROOT).contains("right"));
    }
    private static String text(JsonObject object,String key,String fallback) {
        var value=object.get(key);return value==null || value.isJsonNull()?fallback:value.getAsString();
    }
    private static Integer number(JsonObject object,String key) {
        String text=text(object,key,null);if(text==null) return null;
        try{return Integer.valueOf(text);}catch(NumberFormatException ex){return null;}
    }
    private static <T> T first(T first,T second) {return first!=null?first:second;}
}
