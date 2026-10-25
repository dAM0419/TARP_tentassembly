package com.tarp.content;

import com.mojang.serialization.Codec;
import com.tarp.Tarp;
import com.tarp.content.recipe.LeafCoverRecipe;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class TarpRegistry {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Tarp.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Tarp.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Tarp.MODID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, Tarp.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> RECIPES = DeferredRegister.create(Registries.RECIPE_SERIALIZER, Tarp.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Tarp.MODID);

    /** 방수포 색 (염료 번호 0~15). */
    public static final Supplier<DataComponentType<Integer>> COLOR = COMPONENTS.register("color",
            () -> DataComponentType.<Integer>builder().persistent(Codec.intRange(0, 15)).networkSynchronized(ByteBufCodecs.VAR_INT).build());
    /** 잎 덮개의 잎 블록 id. */
    public static final Supplier<DataComponentType<String>> LEAF = COMPONENTS.register("leaf",
            () -> DataComponentType.<String>builder().persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8).build());

    /** 천막 키트에 담긴 설계도와 설치 진행 상태. */
    public static final Supplier<DataComponentType<net.minecraft.nbt.CompoundTag>> KIT = COMPONENTS.register("kit",
            () -> DataComponentType.<net.minecraft.nbt.CompoundTag>builder().persistent(net.minecraft.nbt.CompoundTag.CODEC)
                    .networkSynchronized(ByteBufCodecs.COMPOUND_TAG).build());

    /** 지지대 점유 칸: 보이지 않고, 충돌·조준·부수기를 맡는다. */
    public static final DeferredBlock<PoleCellBlock> POLE_CELL = BLOCKS.register("pole_cell", () -> new PoleCellBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.4F).sound(SoundType.BAMBOO_WOOD)
                    .noOcclusion().dynamicShape().pushReaction(PushReaction.BLOCK).noLootTable()
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false)));
    /** 노끈 점유 칸 (0.4.7): 보이지 않고, 충돌·조준·부수기(노끈 한 가닥 회수)를 맡는다. */
    public static final DeferredBlock<RopeCellBlock> ROPE_CELL = BLOCKS.register("rope_cell", () -> new RopeCellBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.1F).sound(SoundType.WOOL)
                    .noOcclusion().dynamicShape().forceSolidOn().pushReaction(PushReaction.DESTROY).noLootTable()
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false)));
    /** 말뚝: 블록의 어느 면에나 박힌다. */
    public static final DeferredBlock<StakeBlock> STAKE = BLOCKS.register("stake", () -> new StakeBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(0.3F).sound(SoundType.WOOD)
                    .noOcclusion().pushReaction(PushReaction.DESTROY)));
    /** 천 충돌 칸: 보이지 않는 4×4×4 작은 칸 모양. 비·눈을 막는다. */
    public static final DeferredBlock<CanvasCellBlock> CANVAS_CELL = BLOCKS.register("canvas_cell", () -> new CanvasCellBlock(
            BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.8F, 0.8F).sound(SoundType.WOOL)
                    .noOcclusion().dynamicShape().forceSolidOn().pushReaction(PushReaction.BLOCK).noLootTable()
                    .isSuffocating((s, l, p) -> false).isViewBlocking((s, l, p) -> false).isValidSpawn((s, l, p, e) -> false)));

    public static final DeferredItem<PoleItem> POLE = ITEMS.register("pole", () -> new PoleItem(new Item.Properties()));
    public static final DeferredItem<RopeItem> ROPE = ITEMS.register("rope", () -> new RopeItem(new Item.Properties()));
    public static final DeferredItem<BlockItem> STAKE_ITEM = ITEMS.registerSimpleBlockItem("stake", STAKE);
    /** 크기 이름 (아이템 id 뒷부분): 소·중·대. */
    public static final String[] SIZES = {"small", "medium", "large"};
    /** 방수포 [크기][염료 번호] — 0.3.12 부터 색마다 따로 된 아이템. id 는 "<색>_<크기>_tarp" (예: red_small_tarp). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static final DeferredItem<TarpItem>[][] TARPS = new DeferredItem[3][16];

    static {
        for (int k = 0; k < 3; k++)
            for (DyeColor c : DyeColor.values()) {
                final int kind = k, dye = c.getId();
                TARPS[k][dye] = ITEMS.register(c.getName() + "_" + SIZES[k] + "_tarp", () -> new TarpItem(kind, dye, new Item.Properties().stacksTo(16)));
            }
    }

    /** 0.3.11 이하 방수포: 인벤토리에 들어오면 새 아이템으로 바뀐다 (LegacyTarpItem). */
    public static final DeferredItem<LegacyTarpItem> LEGACY_SMALL = ITEMS.register("tarp_small", () -> new LegacyTarpItem(0, new Item.Properties().stacksTo(16)));
    public static final DeferredItem<LegacyTarpItem> LEGACY_MEDIUM = ITEMS.register("tarp_medium", () -> new LegacyTarpItem(1, new Item.Properties().stacksTo(16)));
    public static final DeferredItem<LegacyTarpItem> LEGACY_LARGE = ITEMS.register("tarp_large", () -> new LegacyTarpItem(2, new Item.Properties().stacksTo(16)));
    public static final DeferredItem<KitItem> KIT_ITEM = ITEMS.register("tent_kit", () -> new KitItem(new Item.Properties().stacksTo(1)));
    public static final DeferredItem<TarpItem> LEAF_COVER = ITEMS.register("leaf_cover", () -> new TarpItem(3, -1, new Item.Properties().stacksTo(16)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PoleCellBlockEntity>> POLE_CELL_BE =
            BLOCK_ENTITIES.register("pole_cell", () -> BlockEntityType.Builder.of(PoleCellBlockEntity::new, POLE_CELL.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RopeCellBlockEntity>> ROPE_CELL_BE =
            BLOCK_ENTITIES.register("rope_cell", () -> BlockEntityType.Builder.of(RopeCellBlockEntity::new, ROPE_CELL.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CanvasCellBlockEntity>> CANVAS_CELL_BE =
            BLOCK_ENTITIES.register("canvas_cell", () -> BlockEntityType.Builder.of(CanvasCellBlockEntity::new, CANVAS_CELL.get()).build(null));

    public static final Supplier<RecipeSerializer<LeafCoverRecipe>> LEAF_COVER_RECIPE = RECIPES.register("leaf_cover",
            () -> new SimpleCraftingRecipeSerializer<>(LeafCoverRecipe::new));

    public static final Supplier<CreativeModeTab> TAB = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.tarp"))
            .icon(() -> new ItemStack(KIT_ITEM.get()))
            .displayItems((params, out) -> {
                out.accept(POLE.get());
                out.accept(ROPE.get());
                out.accept(STAKE_ITEM.get());
                out.accept(KIT_ITEM.get());
                out.accept(TarpItem.leaf("minecraft:oak_leaves"));
                for (int k = 0; k < 3; k++)
                    for (DyeColor c : DyeColor.values()) out.accept(TARPS[k][c.getId()].get());
            })
            .build());

    private TarpRegistry() {}

    /** 그 크기의 방수포 (넓이 상한을 볼 때. 색은 상관없어 하양), 3 이면 잎 덮개. */
    public static TarpItem tarpItem(int kind) {
        return kind >= 0 && kind < 3 ? tarp(kind, 0) : LEAF_COVER.get();
    }

    public static TarpItem tarp(int kind, int color) {
        return TARPS[Math.max(0, Math.min(2, kind))][Math.max(0, Math.min(15, color))].get();
    }

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        COMPONENTS.register(modBus);
        RECIPES.register(modBus);
        TABS.register(modBus);
    }
}
