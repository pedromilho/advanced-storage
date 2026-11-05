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
        Runnable open=()->{
            var base=com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations.resolveBaseBlockPosition(world.getChunkStore(),target);
            NativeStorageWindows.open(ref,world,new StorageNetwork.Pos(base.x,base.y,base.z));
        };
        if(world.isInThread())open.run();else world.execute(open);
    }
}
