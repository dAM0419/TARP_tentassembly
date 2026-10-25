package com.tarp.content;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 0.3.11 이하의 방수포 (크기마다 하나, 색은 데이터 성분). 0.3.12부터 색마다 따로 된 아이템이라,
 * 플레이어 인벤토리에 들어오면 같은 크기·색·개수의 새 아이템으로 바뀐다. 창작 탭·제작법에는 없다.
 */
public class LegacyTarpItem extends Item {
    public final int kind;

    public LegacyTarpItem(int kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof Player p)) return;
        ItemStack now = TarpItem.of(kind, TarpItem.color(stack));
        now.setCount(stack.getCount());
        p.getInventory().setItem(slot, now);
    }
}
