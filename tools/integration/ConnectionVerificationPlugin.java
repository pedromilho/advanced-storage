// Test-only server plugin. Built under .runtime; never packaged with the mod.
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockOperations;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.connectedblocks.ConnectedBlocksUtil;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.RotationTuple;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.Rotation;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import org.joml.Vector3i;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.*;

public class ConnectionVerificationPlugin extends JavaPlugin {
    static final String BASE="AdvancedStorage_Runic_Conduit";
    static final String[] IDS={BASE,BASE+"_Curve",BASE+"_T",BASE+"_Cross",BASE+"_Up",BASE+"_UpDown",BASE+"_All",BASE+"_Corner3"};
    static final Vector3i CENTER=new Vector3i(8,272,8);
    static final Vector3i UP=new Vector3i(0,1,0);
    static final Vector3i[] OFFSETS={new Vector3i(0,0,-1),new Vector3i(1,0,0),new Vector3i(0,0,1),new Vector3i(-1,0,0),new Vector3i(0,1,0),new Vector3i(0,-1,0)};
    static final int RAW=SetBlockSettings.NO_UPDATE_NEIGHBOR_CONNECTIONS|SetBlockSettings.NO_SEND_PARTICLES|SetBlockSettings.NO_DROP_ITEMS;
    public ConnectionVerificationPlugin(JavaPluginInit init){super(init);}
    @Override protected void setup(){
        StorageVerification.register(this);
        getCommandRegistry().registerCommand(new AbstractAsyncCommand("as-verify-connections","Verify native conduit rules"){
            @Override protected CompletableFuture<Void> executeAsync(CommandContext context){
                return Universe.get().getUniverseReady().thenCompose(ignored->{
                    World world=Universe.get().getDefaultWorld();
                    if(world==null)throw new IllegalStateException("No test world");
                    ChunkStore chunks=world.getChunkStore();
                    return chunks.getChunkSectionReferenceAtBlockAsync(CENTER.x,CENTER.y,CENTER.z)
                        .thenCompose(ref->CompletableFuture.supplyAsync(()->runChecks(chunks,ref),world)
                            .thenCompose(cases->CompletableFuture.runAsync(()->{
                                BlockSection section=chunks.getStore().getComponent(ref,BlockSection.getComponentType());
                                String actual=BlockType.getAssetMap().getAsset(section.get(CENTER.x,CENTER.y,CENTER.z)).getId();
                                if(!actual.equals("AdvancedStorage_Seal_Archive"))
                                    throw new AssertionError("Archive disappeared after support removal: "+actual);
                                clear(chunks,ref);
                                System.out.println("ADVANCED_STORAGE_CONNECTION_CHECKS_PASS cases="+(cases+1));
                            },CompletableFuture.delayedExecutor(1200,TimeUnit.MILLISECONDS,world))));
                }).handle((unused,error)->{
                    try{
                        Files.writeString(Path.of("connection-check-result.txt"),error==null?"PASS\n":("FAIL\n"+error));
                    }catch(Exception e){throw new RuntimeException(e);}
                    if(error!=null)error.printStackTrace();
                    return null;
                });
            }
        });
    }
    static void put(ChunkStore chunks,Ref<ChunkStore> ref,Vector3i p,String name,int rotation){
        BlockType type=BlockType.getAssetMap().getAsset(name);
        if(type==null)throw new AssertionError("Missing block "+name);
        int id=BlockType.getAssetMap().getIndex(name);
        BlockOperations.setBlock(chunks,ref,p.x,p.y,p.z,id,type,rotation,0,RAW);
    }
    static void clear(ChunkStore chunks,Ref<ChunkStore> ref){
        put(chunks,ref,CENTER,"Empty",0);
        for(Vector3i offset:OFFSETS)put(chunks,ref,new Vector3i(CENTER).add(offset),"Empty",0);
    }
    static String expected(int mask){
        int count=Integer.bitCount(mask);
        int pairs=((mask&5)==5?1:0)+((mask&10)==10?1:0)+((mask&48)==48?1:0);
        if(count==6)return IDS[6];
        if(count==5)return IDS[5];
        if(count==4)return pairs==2?IDS[3]:IDS[4];
        if(count==3)return pairs==1?IDS[2]:IDS[7];
        if(count==2 && pairs==0)return IDS[1];
        return IDS[0];
    }
    static int ports(String id,int rotation){
        int[] local=id.equals(IDS[1])?new int[]{2,3}:id.equals(IDS[2])?new int[]{1,2,3}:id.equals(IDS[3])?new int[]{0,1,2,3}:id.equals(IDS[4])?new int[]{1,2,3,4}:id.equals(IDS[5])?new int[]{1,2,3,4,5}:id.equals(IDS[6])?new int[]{0,1,2,3,4,5}:id.equals(IDS[7])?new int[]{2,3,4}:new int[]{0,2};
        int mask=0;
        for(int i:local){
            Vector3i v=new Vector3i(OFFSETS[i]);RotationTuple.get(rotation).applyRotationTo(v);
            for(int j=0;j<6;j++)if(v.equals(OFFSETS[j]))mask|=1<<j;
        }
        return mask;
    }
    static void checkResult(ChunkStore chunks,String initial,int rotation,int mask){
        BlockType type=BlockType.getAssetMap().getAsset(initial);
        var result=ConnectedBlocksUtil.getDesiredConnectedBlockType(chunks,CENTER,type,rotation,UP,false)
            .orElseThrow(()->new AssertionError("No result for mask "+mask));
        if(!result.blockTypeKey().equals(expected(mask)))
            throw new AssertionError("Mask "+mask+" expected "+expected(mask)+" got "+result);
        int physical=ports(result.blockTypeKey(),result.rotationIndex());
        if((physical&mask)!=mask || (Integer.bitCount(mask)>=2 && physical!=mask))
            throw new AssertionError("Incorrect physical orientation: mask "+mask+" got "+result);
    }
    static void placeConnected(ChunkStore chunks,Ref<ChunkStore> ref,Vector3i p,String name){
        placeConnected(chunks,ref,p,name,RotationTuple.NONE.index());
    }
    static void placeConnected(ChunkStore chunks,Ref<ChunkStore> ref,Vector3i p,String name,int rotation){
        put(chunks,ref,p,name,rotation);
        BlockSection section=chunks.getStore().getComponent(ref,BlockSection.getComponentType());
        ConnectedBlocksUtil.setConnectedBlockAndNotifyNeighbors(chunks,BlockType.getAssetMap().getIndex(name),RotationTuple.get(rotation),UP,p,ref,section);
    }
    static void assertCenter(ChunkStore chunks,Ref<ChunkStore> ref,int mask){
        BlockSection section=chunks.getStore().getComponent(ref,BlockSection.getComponentType());
        int index=section.get(CENTER.x,CENTER.y,CENTER.z);
        String id=BlockType.getAssetMap().getAsset(index).getId();
        int rotation=section.getRotationIndex(CENTER.x,CENTER.y,CENTER.z);
        if(!id.equals(expected(mask)) || (ports(id,rotation)&mask)!=mask)
            throw new AssertionError("Live update mask "+mask+" got "+id+" rotation "+rotation);
    }
    static int runChecks(ChunkStore chunks,Ref<ChunkStore> ref){
        int cases=0;
        for(String initial:IDS)for(Rotation yaw:Rotation.values())for(int mask=0;mask<64;mask++){
            clear(chunks,ref);
            int rotation=RotationTuple.of(yaw,Rotation.None).index();
            put(chunks,ref,CENTER,initial,rotation);
            for(int side=0;side<6;side++)if((mask&(1<<side))!=0){
                String neighbor=side%2==0?"AdvancedStorage_Seal_Archive":IDS[(side+mask)%IDS.length];
                put(chunks,ref,new Vector3i(CENTER).add(OFFSETS[side]),neighbor,rotation);
            }
            checkResult(chunks,initial,rotation,mask);cases++;
        }
        // Exercise all 24 distinct 3D initial orientations, including upside down.
        Set<String> seen=new HashSet<>();
        for(RotationTuple r:RotationTuple.VALUES){
            Vector3i a=new Vector3i(1,0,0),b=new Vector3i(0,1,0);
            r.applyRotationTo(a);r.applyRotationTo(b);
            if(!seen.add(a.toString()+b.toString()))continue;
            for(int mask=0;mask<64;mask++){
                clear(chunks,ref);put(chunks,ref,CENTER,BASE,r.index());
                for(int side=0;side<6;side++)if((mask&(1<<side))!=0)
                    put(chunks,ref,new Vector3i(CENTER).add(OFFSETS[side]),IDS[(side+mask)%IDS.length],r.index());
                checkResult(chunks,BASE,r.index(),mask);cases++;
            }
        }
        clear(chunks,ref);put(chunks,ref,CENTER,BASE,0);
        put(chunks,ref,new Vector3i(CENTER).add(OFFSETS[0]),"Rock_Stone",0);
        checkResult(chunks,BASE,0,0);cases++;
        // The same entry point used for placement: existing neighbors must update.
        clear(chunks,ref);placeConnected(chunks,ref,CENTER,BASE);
        int mask=0;
        for(int side:new int[]{2,3,4,1,5,0}){
            placeConnected(chunks,ref,new Vector3i(CENTER).add(OFFSETS[side]),BASE);
            mask|=1<<side;assertCenter(chunks,ref,mask);cases++;
        }
        for(int side:new int[]{0,5,4,1,3,2}){
            placeConnected(chunks,ref,new Vector3i(CENTER).add(OFFSETS[side]),"Empty");
            mask&=~(1<<side);assertCenter(chunks,ref,mask);cases++;
        }
        // A container switches to generated block IDs when opened and closed.
        // Match those states, not only the base item's ID.
        BlockType archive=BlockType.getAssetMap().getAsset("AdvancedStorage_Seal_Archive");
        List<BlockType> archives=new ArrayList<>();archives.add(archive);
        for(String state:new String[]{"OpenWindow","CloseWindow"}){
            BlockType type=archive.getBlockForState(state);
            if(type==null)throw new AssertionError("Missing archive state "+state);
            archives.add(type);
        }
        for(BlockType archiveState:archives)for(Rotation yaw:Rotation.values())for(int side=0;side<6;side++){
            int perpendicular=0;
            while(OFFSETS[side].x*OFFSETS[perpendicular].x+OFFSETS[side].y*OFFSETS[perpendicular].y+OFFSETS[side].z*OFFSETS[perpendicular].z!=0)perpendicular++;
            int expectedMask=(1<<side)|(1<<perpendicular);
            int rotation=RotationTuple.of(yaw,Rotation.None).index();
            clear(chunks,ref);
            put(chunks,ref,CENTER,BASE,0);
            put(chunks,ref,new Vector3i(CENTER).add(OFFSETS[perpendicular]),BASE,0);
            put(chunks,ref,new Vector3i(CENTER).add(OFFSETS[side]),archiveState.getId(),rotation);
            try{checkResult(chunks,BASE,0,expectedMask);}
            catch(AssertionError error){throw new AssertionError("Archive state="+archiveState.getId()+" side="+side+" yaw="+yaw,error);}
            cases++;
            for(boolean archiveFirst:new boolean[]{false,true}){
                clear(chunks,ref);
                Vector3i archivePos=new Vector3i(CENTER).add(OFFSETS[side]);
                if(archiveFirst)placeConnected(chunks,ref,archivePos,archiveState.getId(),rotation);
                placeConnected(chunks,ref,CENTER,BASE);
                placeConnected(chunks,ref,new Vector3i(CENTER).add(OFFSETS[perpendicular]),BASE);
                if(!archiveFirst)placeConnected(chunks,ref,archivePos,archiveState.getId(),rotation);
                try{assertCenter(chunks,ref,expectedMask);}
                catch(AssertionError error){throw new AssertionError("Live archive first="+archiveFirst+" state="+archiveState.getId()+" side="+side,error);}
                cases++;
            }
        }
        for(BlockType archiveState:archives){
            if(archiveState.hasSupport())throw new AssertionError("Archive still requires support: "+archiveState.getId());
            if(!archiveState.shouldIgnoreSupportWhenPlaced())throw new AssertionError("Archive cannot be freely placed: "+archiveState.getId());
            cases++;
        }
        // The core's authored side sockets must attract pipes in both orders.
        for(Rotation yaw:Rotation.values())for(int side=0;side<4;side++)for(boolean coreFirst:new boolean[]{false,true}){
            clear(chunks,ref);
            int perpendicular=(side+1)%4;
            int expectedMask=(1<<side)|(1<<perpendicular);
            Vector3i corePos=new Vector3i(CENTER).add(OFFSETS[side]);
            int rotation=RotationTuple.of(yaw,Rotation.None).index();
            if(coreFirst)placeConnected(chunks,ref,corePos,"AdvancedStorage_Arcane_Core",rotation);
            placeConnected(chunks,ref,CENTER,BASE);
            placeConnected(chunks,ref,new Vector3i(CENTER).add(OFFSETS[perpendicular]),BASE);
            if(!coreFirst)placeConnected(chunks,ref,corePos,"AdvancedStorage_Arcane_Core",rotation);
            assertCenter(chunks,ref,expectedMask);cases++;
        }
        clear(chunks,ref);put(chunks,ref,CENTER,BASE,0);
        put(chunks,ref,new Vector3i(CENTER).add(OFFSETS[4]),"AdvancedStorage_Arcane_Core",0);
        checkResult(chunks,BASE,0,0);cases++;
        // Catalog sockets exist at local north/east/west; its banner front is
        // deliberately not a port. Check rotation and both placement orders.
        for(Rotation yaw:Rotation.values())for(int side=0;side<4;side++)for(boolean catalogFirst:new boolean[]{false,true}){
            clear(chunks,ref);
            int rotation=RotationTuple.of(yaw,Rotation.None).index();
            Vector3i localTowardPipe=new Vector3i(OFFSETS[side]).negate();
            RotationTuple.get(rotation).undoRotationTo(localTowardPipe);
            boolean compatible=localTowardPipe.equals(OFFSETS[0])||localTowardPipe.equals(OFFSETS[1])||localTowardPipe.equals(OFFSETS[3]);
            int perpendicular=(side+1)%4;
            int expectedMask=(1<<perpendicular)|(compatible?(1<<side):0);
            Vector3i catalogPos=new Vector3i(CENTER).add(OFFSETS[side]);
            if(catalogFirst)placeConnected(chunks,ref,catalogPos,"AdvancedStorage_Runic_Catalog",rotation);
            placeConnected(chunks,ref,CENTER,BASE);
            placeConnected(chunks,ref,new Vector3i(CENTER).add(OFFSETS[perpendicular]),BASE);
            if(!catalogFirst)placeConnected(chunks,ref,catalogPos,"AdvancedStorage_Runic_Catalog",rotation);
            assertCenter(chunks,ref,expectedMask);cases++;
        }
        clear(chunks,ref);
        Vector3i floor=new Vector3i(CENTER).add(0,-1,0);
        put(chunks,ref,floor,"Rock_Stone",0);
        put(chunks,ref,CENTER,"AdvancedStorage_Seal_Archive",0);
        BlockType empty=BlockType.getAssetMap().getAsset("Empty");
        BlockOperations.setBlock(chunks,ref,floor.x,floor.y,floor.z,0,empty,0,0,
            SetBlockSettings.PERFORM_BLOCK_UPDATE|SetBlockSettings.NO_DROP_ITEMS|SetBlockSettings.NO_SEND_PARTICLES);
        return cases;
    }
}
