package pedro.advancedstorage;

import java.util.*;
import java.util.regex.Pattern;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.asset.type.item.config.ItemQuality;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.ui.*;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/** Page-owned replacement: never creates the client's global ItemTooltipLayer. */
public final class StorageTooltip {
    public record Content(String name,String id,String quality,String skin,boolean showQuality,String description,String stats) {}
    private Integer grid,slot;
    private Content mounted;
    private String geometry="";
    private static final String HOST="#PageTooltipHost ";
    private static final int BODY_LINE_HEIGHT=22;
    private static final Pattern FORMATTING_TAG=Pattern.compile("</?(?:i|b|u|color)(?:\\s+[^<>]*)?>",Pattern.CASE_INSENSITIVE);
    private boolean visible;
    public void hover(int grid,int slot) {this.grid=grid;this.slot=slot;}
    public void exit(int grid,int slot) {if(Objects.equals(this.grid,grid) && Objects.equals(this.slot,slot)) clear();}
    public void clear() {grid=null;slot=null;}
    public void hide(UICommandBuilder commands) {
        clear();
        if(visible)commands.set(HOST+"#StorageTooltip.Visible",false);
        visible=false;mounted=null;
    }
    void invalidate(UICommandBuilder commands) {
        hide(commands);
        geometry="";
    }
    private static String translated(String language,String key,String fallback) {
        if(key==null || key.isEmpty()) return fallback;
        String value=I18nModule.get().getMessage(language,key);
        if(value==null) value=I18nModule.get().getMessage("en-US",key);
        return value==null?fallback:value;
    }
    private static String number(double value) {return String.format(Locale.US,value==Math.rint(value)?"%.0f":"%.1f",value);}
    public static Content content(ItemStack stack,String language,Long stored) {
        var item=stack.getItem();
        var quality=ItemQuality.getAssetMap().getAsset(stack.getQualityIndex());
        if(quality==null) quality=ItemQuality.DEFAULT_ITEM_QUALITY;
        String skin=quality.getId();
        if(!Set.of("Common","Debug","Developer","Epic","Junk","Legendary","Rare","Technical","Template","Tool","Uncommon").contains(skin)) skin="Common";
        String description=stored!=null?String.format(Locale.US,"%,d stored.",stored):translated(language,item.getDescriptionTranslationKey(),"");
        var stats=new ArrayList<String>();
        if(item.getArmor()!=null) stats.add("Defense: "+number(item.getArmor().getBaseDamageResistance()));
        if(item.getWeapon()!=null && item.getWeapon().getBasicDamageBreakdown()!=null)
            for(var damage:item.getWeapon().getBasicDamageBreakdown().entries())
                stats.add(translated(language,damage.labelKey(),"Damage")+": "+number(damage.min())+(damage.min()!=damage.max()?" - "+number(damage.max()):""));
        if(stack.getMaxDurability()>0) stats.add("Durability: "+number(stack.getDurability())+" / "+number(stack.getMaxDurability()));
        return new Content(translated(language,item.getTranslationKey(),stack.getItemId()),"ID: "+stack.getItemId(),
            translated(language,quality.getLocalizationKey(),quality.getId()),skin,quality.isVisibleQualityLabel(),description,String.join("\n",stats));
    }
    private static void anchor(UICommandBuilder commands,String selector,int left,int top,int width,int height) {
        var a=new Anchor();a.setLeft(Value.of(left));a.setTop(Value.of(top));a.setWidth(Value.of(width));a.setHeight(Value.of(height));commands.setObject(selector+".Anchor",a);
    }
    static List<String> wrapBody(String text,int width) {
        String plain=FORMATTING_TAG.matcher(text).replaceAll("").strip();
        if(plain.isEmpty()) return List.of();
        var lines=new ArrayList<String>();
        for(String paragraph:plain.split("\\R",-1)) {
            paragraph=paragraph.strip();
            if(paragraph.isEmpty()) {
                lines.add("");
                continue;
            }
            int start=0;
            while(start<paragraph.length()) {
                int end=start;
                int lastSpace=-1;
                int used=0;
                while(end<paragraph.length()) {
                    int glyph=paragraph.codePointAt(end);
                    if(Character.isWhitespace(glyph)) lastSpace=end;
                    int next=used+bodyGlyphWidth(glyph);
                    if(next>width && end>start) break;
                    used=next;
                    end+=Character.charCount(glyph);
                }
                if(end<paragraph.length() && lastSpace>start) end=lastSpace;
                lines.add(paragraph.substring(start,end).stripTrailing());
                start=end;
                while(start<paragraph.length() && Character.isWhitespace(paragraph.codePointAt(start)))
                    start+=Character.charCount(paragraph.codePointAt(start));
            }
        }
        return lines;
    }
    private static int bodyGlyphWidth(int glyph) {
        // Budget generously for the client's proportional 14 px font.
        if(Character.isWhitespace(glyph) || "ilI.,:;!'|`".indexOf(glyph)>=0) return 6;
        if(glyph>127 || "mMwW@%".indexOf(glyph)>=0) return 16;
        return Character.isUpperCase(glyph)?12:10;
    }
    public void render(CatalogSettingsPage page,UICommandBuilder commands) {
        ItemStack stack=null;
        if(grid!=null && slot!=null && !page.gestures.holding()) {
            if(grid==CatalogGestures.RECIPES&&slot>=0&&slot<page.recipes.size())stack=page.recipeItem(slot);
            var section=page.gestures.sections.get(grid);
            if(section!=null && slot>=0 && slot<section.container().getCapacity()) stack=section.container().getItemStack(slot.shortValue());
        }
        if(ItemStack.isEmpty(stack)) {
            if(visible) commands.set(HOST+"#StorageTooltip.Visible",false);
            visible=false;mounted=null;return;
        }
        Long stored=null;
        if(!page.archive && grid==CatalogGestures.NETWORK) {
            stored=0L;
            for(var entry:page.catalog.network.contents()) if(entry.template.isStackableWith(stack)) stored+=entry.count;
        }
        var current=content(stack,page.tooltipLanguage(),stored);
        int width=Math.min(480,Math.max(320,Math.max(current.name.length()*10+48,current.id.length()*7+48)));
        var descriptionLines=wrapBody(current.description,width-52);
        var statLines=wrapBody(current.stats,width-52);
        int descriptionHeight=descriptionLines.isEmpty()?0:12+descriptionLines.size()*BODY_LINE_HEIGHT;
        int statsHeight=statLines.isEmpty()?0:17+statLines.size()*BODY_LINE_HEIGHT;
        int idHeight=18*Math.max(1,(int)Math.ceil(current.id.length()/((width-48)/7.0)));
        int headerHeight=Math.max(24,20*(int)Math.ceil(current.name.length()*9.0/Math.max(100,width-48-(current.showQuality?current.quality.length()*7+5:0))));
        int height=headerHeight+idHeight+45+descriptionHeight+statsHeight;
        int panelWidth=page.archive?630:1120;
        int rows=(page.inventory.getCapacity()+8)/9;
        int panelHeight=page.archive?246+rows*60+(page.backpack==null || page.backpack.getCapacity()==0?0:26+Math.min(180,((page.backpack.getCapacity()+8)/9)*60))+(page.notice.isEmpty()?0:26):page.catalogHeight();
        boolean networkSide=grid==CatalogGestures.NETWORK||grid==CatalogGestures.RECIPES;
        int step=grid==CatalogGestures.BENCHES?CatalogSettingsPage.BENCH_SLOT_PITCH:page.archive || networkSide?60:52;
        int columns=grid==CatalogGestures.BENCHES?CatalogSettingsPage.BENCH_COLUMNS:page.archive && grid==CatalogGestures.NETWORK?SealData.ARCHIVE_SLOTS:9;
        int x=page.archive || networkSide?17:591;
        int y=page.archive?(grid==CatalogGestures.NETWORK?55:147):179;
        if(grid==CatalogGestures.HOTBAR) y=page.archive?147+rows*60+26:413;
        if(grid==CatalogGestures.BENCHES) {
            x+=CatalogSettingsPage.BENCH_LEFT_INSET;
            y=547;
        }
        if(grid==CatalogGestures.BACKPACK) y=page.archive?147+rows*60+26+56+26:487+(page.benchWindow==null?0:186);
        int cellSize=page.archive?56:step;
        var cell=new pedro.advancedstorage.ui.GridViewport.Cell(x+(slot%columns)*step,y+(slot/columns)*step,cellSize,cellSize);
        var box=page.archive?pedro.advancedstorage.ui.TooltipPlacement.place(cell,width,height,panelWidth,panelHeight,true)
            :pedro.advancedstorage.ui.TooltipPlacement.above(cell,width,height,panelWidth,panelHeight);
        int left=box.left(),tooltipTop=box.top();
        String nextGeometry=panelWidth+":"+panelHeight+":"+left+":"+tooltipTop+":"+width+":"+height
            +":"+box.arrowLeft()+":"+box.below()+":"+headerHeight+":"+idHeight+":"+descriptionHeight+":"+statsHeight;
        boolean moved=!nextGeometry.equals(geometry);
        if(moved) {
            var canvas=new Anchor();canvas.setWidth(Value.of(panelWidth));canvas.setHeight(Value.of(panelHeight));
            if(page.archive) {
                canvas.setLeft(Value.of(0));
                canvas.setTop(Value.of(0));
            }
            commands.setObject(HOST+"#StorageTooltipCanvas.Anchor",canvas);
            anchor(commands,HOST+"#StorageTooltip",left,tooltipTop,width,height);
            var arrow=new Anchor();arrow.setLeft(Value.of(box.arrowLeft()));
            if(box.below()) arrow.setTop(Value.of(-8));
            else arrow.setBottom(Value.of(-8));
            arrow.setWidth(Value.of(33));arrow.setHeight(Value.of(24));commands.setObject(HOST+"#StorageTooltipArrow.Anchor",arrow);
            for(var entry:Map.of("#StorageTooltipHeader",headerHeight,"#StorageTooltipId",idHeight,"#StorageTooltipDescriptionGroup",descriptionHeight,"#StorageTooltipStatsGroup",statsHeight).entrySet()) {
                var a=new Anchor();a.setHeight(Value.of(entry.getValue()));commands.setObject(HOST+entry.getKey()+".Anchor",a);
            }
            geometry=nextGeometry;
        }
        boolean contentChanged=!current.equals(mounted);
        if(moved || contentChanged) {
            commands.set(HOST+"#StorageTooltipArrow.Background",Value.ref("AdvancedStorage/StorageTooltip.ui",(box.below()?"ArrowUp":"Arrow")+current.skin));
        }
        if(contentChanged) {
            commands.set(HOST+"#StorageTooltipName.Text",current.name);commands.set(HOST+"#StorageTooltipId.Text",current.id);
            commands.set(HOST+"#StorageTooltipQuality.Text",current.quality);commands.set(HOST+"#StorageTooltipQuality.Visible",current.showQuality);
            commands.set(HOST+"#StorageTooltipDescription.Text",String.join("\n",descriptionLines));commands.set(HOST+"#StorageTooltipDescriptionGroup.Visible",!descriptionLines.isEmpty());
            commands.set(HOST+"#StorageTooltipStats.Text",String.join("\n",statLines));commands.set(HOST+"#StorageTooltipStatsGroup.Visible",!statLines.isEmpty());
            commands.set(HOST+"#StorageTooltip.Background",Value.ref("AdvancedStorage/StorageTooltip.ui","Body"+current.skin));
            commands.set(HOST+"#StorageTooltipName.Style",Value.ref("AdvancedStorage/StorageTooltip.ui","Name"+current.skin));
            mounted=current;
        }
        if(!visible) commands.set(HOST+"#StorageTooltip.Visible",true);
        visible=true;
    }
}
