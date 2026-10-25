package com.tarp.content;

import com.tarp.world.Kit;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/** 천막 키트: 완성된 천막을 재료째 담아 다른 곳에 부품 순서대로 다시 친다. */
public class KitItem extends Item {
    public KitItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        Kit k = Kit.of(stack);
        if (k == null) {
            tooltip.add(Component.translatable("item.tarp.tent_kit.empty").withStyle(ChatFormatting.GRAY));
            return;
        }
        int[] m = k.materials();
        tooltip.add(Component.translatable("item.tarp.tent_kit.tarp",
                Component.translatable("tarp.size." + k.kind), Component.translatable("color.minecraft." + DyeColor.byId(k.color).getName()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarp.tent_kit.parts", m[0], m[1], m[2]).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(k.hasDoor() ? "item.tarp.tent_kit.door" : "item.tarp.tent_kit.no_door").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable(k.anchored ? "item.tarp.tent_kit.placing" : "item.tarp.tent_kit.howto").withStyle(ChatFormatting.DARK_GRAY));
    }
}
