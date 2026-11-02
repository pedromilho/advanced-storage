package pedro.advancedstorage;

import java.util.*;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.asset.type.item.config.*;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/** Supplies the direct ingredients for one batch of a currently loaded mod recipe. */
public final class IngredientsCommand extends AbstractPlayerCommand {
    public static final List<String> OPTIONS=List.of("seal_archive","runic_conduit","arcane_core","runic_catalog",
        "blank_seal","runic_book","storage_seal_tier_1","storage_seal_tier_2","storage_seal_tier_3",
        "storage_seal_tier_4","storage_seal_tier_5");
    private final RequiredArg<String> target;
    public IngredientsCommand() {
        super("advancedstorage","Receive the ingredients for one Advanced Storage craft");
        setPermissionGroup(com.hypixel.hytale.protocol.GameMode.Creative);
        target=withRequiredArg("item","Item to craft: "+String.join(", ",OPTIONS),ArgTypes.STRING);
    }
    public static String resolve(String name) {
        String normalized=name.toLowerCase(Locale.ROOT).replace('-','_');
        if(normalized.startsWith("advancedstorage_")) normalized=normalized.substring("advancedstorage_".length());
        for(String option:OPTIONS) if(option.equals(normalized)) return "AdvancedStorage_"+Arrays.stream(option.split("_"))
            .map(s->Character.toUpperCase(s.charAt(0))+s.substring(1)).collect(java.util.stream.Collectors.joining("_"));
        throw new IllegalArgumentException("Unknown item. Options: "+String.join(", ",OPTIONS));
    }
    public static List<ItemStack> ingredients(String name) {
        String id=resolve(name);
        Item item=Item.getAssetMap().getAsset(id);
        if(item==null) throw new IllegalArgumentException("Item is not loaded: "+id);
        var recipes=new ArrayList<CraftingRecipe>();
        item.collectRecipesToGenerate(recipes);
        if(recipes.size()!=1) throw new IllegalArgumentException("No unique recipe loaded for "+id);
        var result=new ArrayList<ItemStack>();
        for(var material:recipes.getFirst().getInput()) {
            String ingredient=material.getItemId();
            if(ingredient==null) ingredient=switch(String.valueOf(material.getResourceTypeId())) {
                case "Wood_Trunk" -> "Wood_Oak_Trunk";
                case "Crystal_Shards" -> "Ingredient_Crystal_White";
                case "Bricks" -> "Rock_Stone_Brick";
                case "AdvancedStorage_Small_Chest" -> "Furniture_Crude_Chest_Small";
                default -> throw new IllegalArgumentException("Unsupported ingredient group: "+material.getResourceTypeId());
            };
            Item asset=Item.getAssetMap().getAsset(ingredient);
            if(asset==null) throw new IllegalArgumentException("Ingredient is not loaded: "+ingredient);
            int remaining=material.getQuantity();
            while(remaining>0) {
                int count=Math.min(remaining,Math.max(1,asset.getMaxStack()));
                result.add(new ItemStack(ingredient,count));remaining-=count;
            }
        }
        return result;
    }
    public static boolean give(ItemContainer inventory,List<ItemStack> ingredients) {
        // Simulate sequential inserts: native list success can mean only part of a kit fits.
        var preview=new com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer(inventory.getCapacity());
        for(short slot=0;slot<inventory.getCapacity();slot++) {
            var existing=inventory.getItemStack(slot);
            if(!ItemStack.isEmpty(existing)) preview.setItemStackForSlot(slot,existing);
        }
        for(var stack:ingredients) {
            var transaction=preview.addItemStack(stack,true,false,true);
            if(!ItemStack.isEmpty(transaction.getRemainder())) return false;
        }
        for(var stack:ingredients) inventory.addItemStack(stack,true,false,true);
        return true;
    }
    @Override protected void execute(CommandContext context,Store<EntityStore> store,Ref<EntityStore> ref,
                                     PlayerRef playerRef,World world) {
        var player=store.getComponent(ref,Player.getComponentType());
        if(player==null) return;
        try {
            var ingredients=ingredients(context.get(target));
            if(!give(player.getInventory().getCombinedBackpackStorageHotbar(),ingredients)) {
                context.sendMessage(Message.raw("Not enough inventory space for the ingredients."));return;
            }
            context.sendMessage(Message.raw("Received crafting ingredients for "+resolve(context.get(target))+
                ": "+ingredients.stream().map(s->s.getQuantity()+" x "+s.getItemId()).collect(java.util.stream.Collectors.joining(", "))));
        } catch(IllegalArgumentException ex) {context.sendMessage(Message.raw(ex.getMessage()));}
    }
}
