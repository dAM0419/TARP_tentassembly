package com.tarp.content;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 천막 지지대. 두 점을 찍어 직선으로 놓는다 (클릭은 클라이언트 ToolController 가 받는다). */
public class PoleItem extends Item {
    public PoleItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tarp.pole.tip").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
