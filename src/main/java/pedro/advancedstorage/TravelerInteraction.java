package pedro.advancedstorage;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

public final class TravelerInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<TravelerInteraction> CODEC=BuilderCodec.builder(TravelerInteraction.class,TravelerInteraction::new,SimpleInstantInteraction.CODEC).build();
    @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown) {
        var ref=context.getEntity();if(ref==null||!ref.isValid())return;
        var world=ref.getStore().getExternalData().getWorld();
        var held=context.getHeldItem();if(held==null||!TravelerLinks.ITEM.equals(held.getItemId()))return;
        var identified=TravelerLinks.identify(held);
        if(identified!=held) {
            var container=context.getHeldItemContainer();var slot=(short)context.getHeldItemSlot();
            if(container==null||!held.equals(container.getItemStack(slot))||!container.setItemStackForSlot(slot,identified).succeeded())return;
            context.setHeldItem(identified);
        }
        var id=TravelerLinks.id(identified);
        world.execute(()->{
            if(!TravelerLinks.owns(ref,world,id))return;
            var links=TravelerLinks.get(world);links.remember(identified,"Traveler's Catalog");
            NativeStorageWindows.openRemote(ref,world,new TravelerLinks.Session(world,id,links.tablets.get(id).transmitter));
        });
    }
}
