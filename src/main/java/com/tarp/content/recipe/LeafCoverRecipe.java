package com.tarp.content.recipe;

import com.tarp.content.TarpItem;
import com.tarp.content.TarpRegistry;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/** 나뭇잎 블록 2×2 (아무 나뭇잎) = 잎 덮개. 왼쪽 위 잎의 종류를 따른다. */
public class LeafCoverRecipe extends CustomRecipe {
    public LeafCoverRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        if (input.width() != 2 || input.height() != 2 || input.ingredientCount() != 4) return false;
        for (int i = 0; i < 4; i++) if (!input.getItem(i).is(ItemTags.LEAVES)) return false;
        return true;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        return TarpItem.leaf(BuiltInRegistries.ITEM.getKey(input.getItem(0).getItem()).toString());
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width >= 2 && height >= 2;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return TarpRegistry.LEAF_COVER_RECIPE.get();
    }
}
