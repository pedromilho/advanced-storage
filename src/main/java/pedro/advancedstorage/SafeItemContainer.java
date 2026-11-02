package pedro.advancedstorage;

import com.hypixel.hytale.server.core.inventory.container.*;
import com.hypixel.hytale.server.core.inventory.transaction.*;

/** Hytale 0.7.5 can return null for a blocked source; native sendUpdate requires a transaction. */
abstract class SafeItemContainer extends SimpleItemContainer {
    SafeItemContainer(short capacity) {super(capacity);}
    private SlotTransaction failed(short slot) {
        return new SlotTransaction(false,ActionType.REMOVE,slot,null,null,null,true,true,true);
    }
    @Override protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(short slot,ItemContainer target,boolean all,boolean filter) {
        var result=super.internal_moveItemStackFromSlot(slot,target,all,filter);
        return result!=null?result:new MoveTransaction<>(false,failed(slot),MoveType.MOVE_FROM_SELF,target,ItemStackTransaction.FAILED_ADD);
    }
    @Override protected MoveTransaction<ItemStackTransaction> internal_moveItemStackFromSlot(short slot,int quantity,ItemContainer target,boolean all,boolean filter) {
        var result=super.internal_moveItemStackFromSlot(slot,quantity,target,all,filter);
        return result!=null?result:new MoveTransaction<>(false,failed(slot),MoveType.MOVE_FROM_SELF,target,ItemStackTransaction.FAILED_ADD);
    }
    @Override protected MoveTransaction<SlotTransaction> internal_moveItemStackFromSlot(short slot,int quantity,ItemContainer target,short targetSlot,boolean filter) {
        var result=super.internal_moveItemStackFromSlot(slot,quantity,target,targetSlot,filter);
        return result!=null?result:new MoveTransaction<>(false,failed(slot),MoveType.MOVE_FROM_SELF,target,SlotTransaction.FAILED_ADD);
    }
}
