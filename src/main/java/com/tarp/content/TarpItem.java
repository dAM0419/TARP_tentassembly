package com.tarp.content;

import com.tarp.core.Spec;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 방수포(소·중·대) 와 잎 덮개. 묶는 점을 차례로 찍어 투영한 뒤 첫 점을 다시 찍어 설치한다.
 * 0.3.12: 방수포는 색마다 따로 된 아이템이다 (16색 × 3크기). 색은 아이템 자체가 가진다.
 */
public class TarpItem extends Item {
    /** 0 소, 1 중, 2 대, 3 잎 덮개. */
    public final int kind;
    /** 염료 번호 0~15 (잎 덮개는 -1). */
    public final int dye;

    public TarpItem(int kind, int dye, Properties properties) {
        super(properties);
        this.kind = kind;
        this.dye = dye;
    }

    public boolean isLeaf() {
        return kind == 3;
    }

    public double maxArea() {
        return isLeaf() ? Spec.LEAF_AREA : Spec.TARP_AREA[Math.min(kind, 2)];
    }

    /** 그 크기·색의 방수포 한 장. */
    public static ItemStack of(int kind, int color) {
        return new ItemStack(TarpRegistry.tarp(kind, color));
    }

    public static ItemStack leaf(String leafId) {
        ItemStack s = new ItemStack(TarpRegistry.LEAF_COVER.get());
        s.set(TarpRegistry.LEAF.get(), leafId);
        return s;
    }

    public static int color(ItemStack s) {
        if (s.getItem() instanceof TarpItem t && t.dye >= 0) return t.dye;
        Integer c = s.get(TarpRegistry.COLOR.get());
        return c == null ? DyeColor.WHITE.getId() : c;
    }

    public static String leafId(ItemStack s) {
        String l = s.get(TarpRegistry.LEAF.get());
        return l == null || l.isEmpty() ? "minecraft:oak_leaves" : l;
    }

    public static Block leafBlock(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Block b = rl == null ? null : BuiltInRegistries.BLOCK.get(rl);
        return b == null || b == Blocks.AIR ? Blocks.OAK_LEAVES : b;
    }

    @Override
    public Component getName(ItemStack stack) {
        if (isLeaf()) return Component.translatable("item.tarp.leaf_cover.of", leafBlock(leafId(stack)).getName());
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.tarp.tarp.area", (int) maxArea()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarp.tarp.tip").withStyle(ChatFormatting.GRAY));
        if (isLeaf()) tooltip.add(Component.translatable("item.tarp.leaf_cover.tip").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.tarp.tarp.remove").withStyle(ChatFormatting.GRAY));
    }
}
