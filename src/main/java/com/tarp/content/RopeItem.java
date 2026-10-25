package com.tarp.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 천막 노끈. 말뚝 머리·지지대 끝점을 차례로 찍어 잇는다. */
public class RopeItem extends Item {
    public RopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tarp.rope.tip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
