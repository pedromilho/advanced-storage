package pedro.advancedstorage;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;

public final class NativeStorageInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<NativeStorageInteraction> CODEC=BuilderCodec.builder(NativeStorageInteraction.class,NativeStorageInteraction::new,SimpleInstantInteraction.CODEC).build();
    @Override protected void firstRun(InteractionType type,InteractionContext context,CooldownHandler cooldown) {
        var target=context.getTargetBlock();
        var ref=context.getEntity();
        if(target==null || ref==null || !ref.isValid()) return;
        var world=ref.getStore().getExternalData().getWorld();
        var pos=new StorageNetwork.Pos(target.x,target.y,target.z);
        if(world.isInThread()) NativeStorageWindows.open(ref,world,pos);
        else world.execute(()->NativeStorageWindows.open(ref,world,pos));
    }
}
