package pedro.advancedstorage;

import java.util.*;
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
    private boolean visible;
    public void hover(int grid,int slot) {this.grid=grid;this.slot=slot;}
    public void exit(int grid,int slot) {if(Objects.equals(this.grid,grid) && Objects.equals(this.slot,slot)) clear();}
    public void clear() {grid=null;slot=null;}
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
    public void render(CatalogSettingsPage page,UICommandBuilder commands) {
        ItemStack stack=null;
        if(grid!=null && slot!=null && !page.gestures.holding()) {
            var section=page.gestures.sections.get(grid);
            if(section!=null && slot>=0 && slot<section.container().getCapacity()) stack=section.container().getItemStack(slot.shortValue());
        }
        if(ItemStack.isEmpty(stack)) {
            if(visible) commands.set("#StorageTooltip.Visible",false);
            visible=false;mounted=null;return;
        }
        Long stored=null;
        if(!page.archive && grid==CatalogGestures.NETWORK) {
            stored=0L;
            for(var entry:page.catalog.network.contents()) if(entry.template.isStackableWith(stack)) stored+=entry.count;
        }
        var current=content(stack,page.tooltipLanguage(),stored);
        int width=Math.min(480,Math.max(320,Math.max(current.name.length()*10+48,current.id.length()*7+48)));
        int descriptionLines=current.description.isEmpty()?0:Math.max(1,(int)Math.ceil(current.description.length()/((width-48)/7.0)));
        int statLines=current.stats.isEmpty()?0:Arrays.stream(current.stats.split("\n")).mapToInt(line->Math.max(1,(int)Math.ceil(line.length()/((width-48)/7.0)))).sum();
        int idHeight=18*Math.max(1,(int)Math.ceil(current.id.length()/((width-48)/7.0)));
        int headerHeight=Math.max(24,20*(int)Math.ceil(current.name.length()*9.0/Math.max(100,width-48-(current.showQuality?current.quality.length()*7+5:0))));
        int height=headerHeight+idHeight+45+(descriptionLines>0?12+descriptionLines*18:0)+(statLines>0?17+statLines*18:0);
        int panelWidth=page.archive?570:1120;
        int rows=(page.inventory.getCapacity()+8)/9;
        int panelHeight=page.archive?246+rows*60+(page.backpack==null || page.backpack.getCapacity()==0?0:26+Math.min(180,((page.backpack.getCapacity()+8)/9)*60))+(page.notice.isEmpty()?0:26):700;
        int step=page.archive || grid==CatalogGestures.NETWORK?60:52;
        int columns=page.archive && grid==CatalogGestures.NETWORK?4:9;
        int x=page.archive || grid==CatalogGestures.NETWORK?17:591;
        int y=page.archive?(grid==CatalogGestures.NETWORK?55:147):179;
        if(grid==CatalogGestures.HOTBAR) y=page.archive?147+rows*60+26:413;
        if(grid==CatalogGestures.BACKPACK) y=page.archive?147+rows*60+26+56+26:487;
        int center=x+(slot%columns)*step+step/2;
        int top=y+(slot/columns)*step;
        int left=Math.max(0,Math.min(panelWidth-width,center-width/2));
        String nextGeometry=panelWidth+":"+panelHeight+":"+left+":"+top+":"+width+":"+height;
        if(!nextGeometry.equals(geometry)) {
            var canvas=new Anchor();canvas.setWidth(Value.of(panelWidth));canvas.setHeight(Value.of(panelHeight));
            commands.setObject("#StorageTooltipCanvas.Anchor",canvas);
            anchor(commands,"#StorageTooltip",left,Math.max(0,top-height-8),width,height);
            var arrow=new Anchor();arrow.setLeft(Value.of(Math.max(12,Math.min(width-45,center-left-16))));arrow.setBottom(Value.of(-8));arrow.setWidth(Value.of(33));arrow.setHeight(Value.of(24));commands.setObject("#StorageTooltipArrow.Anchor",arrow);
            for(var entry:Map.of("#StorageTooltipHeader",headerHeight,"#StorageTooltipId",idHeight,"#StorageTooltipDescriptionGroup",12+descriptionLines*18,"#StorageTooltipStatsGroup",17+statLines*18).entrySet()) {
                var a=new Anchor();a.setHeight(Value.of(entry.getValue()));commands.setObject(entry.getKey()+".Anchor",a);
            }
            geometry=nextGeometry;
        }
        if(!current.equals(mounted)) {
            commands.set("#StorageTooltipName.Text",current.name);commands.set("#StorageTooltipId.Text",current.id);
            commands.set("#StorageTooltipQuality.Text",current.quality);commands.set("#StorageTooltipQuality.Visible",current.showQuality);
            commands.set("#StorageTooltipDescription.Text",current.description);commands.set("#StorageTooltipDescriptionGroup.Visible",!current.description.isEmpty());
            commands.set("#StorageTooltipStats.Text",current.stats);commands.set("#StorageTooltipStatsGroup.Visible",!current.stats.isEmpty());
            commands.set("#StorageTooltip.Background",Value.ref("AdvancedStorage/StorageTooltip.ui","Body"+current.skin));
            commands.set("#StorageTooltipArrow.Background",Value.ref("AdvancedStorage/StorageTooltip.ui","Arrow"+current.skin));
            commands.set("#StorageTooltipName.Style",Value.ref("AdvancedStorage/StorageTooltip.ui","Name"+current.skin));
            mounted=current;
        }
        if(!visible) commands.set("#StorageTooltip.Visible",true);
        visible=true;
    }
}
