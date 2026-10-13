package dev.bekhilitt.platform.story;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Converts a pure scenery plan. Never writes world blocks or bypasses EpisodeCamp's preflight. */
public final class WarCampEnvironment {
    public static final int MIN_X = WarCampPlan.MIN_X, MAX_X = WarCampPlan.MAX_X;
    public static final int MIN_Z = WarCampPlan.MIN_Z, MAX_Z = WarCampPlan.MAX_Z;
    public static final int MIN_Y_OFFSET = WarCampPlan.MIN_Y_OFFSET, MAX_Y_OFFSET = WarCampPlan.MAX_Y_OFFSET;
    private WarCampEnvironment() { }

    public static Map<BlockPos, BlockState> layout(long seed, int floor) {
        var result = new LinkedHashMap<BlockPos, BlockState>();
        WarCampPlan.layout(seed).forEach((cell, material) -> result.put(
            new BlockPos(cell.x(), Math.addExact(floor, cell.y()), cell.z()), state(material)));
        return Collections.unmodifiableMap(result);
    }
    private static BlockState state(WarCampPlan.Material material) {
        return switch (material) {
            case STONE -> Blocks.STONE.defaultBlockState();
            case ANDESITE -> Blocks.ANDESITE.defaultBlockState();
            case COBBLESTONE -> Blocks.COBBLESTONE.defaultBlockState();
            case MOSSY_COBBLESTONE -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case CRACKED_STONE_BRICKS -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            case COARSE_DIRT -> Blocks.COARSE_DIRT.defaultBlockState();
            case PODZOL -> Blocks.PODZOL.defaultBlockState();
            case GRAVEL -> Blocks.GRAVEL.defaultBlockState();
            case MOSS_BLOCK -> Blocks.MOSS_BLOCK.defaultBlockState();
            case SPRUCE_LOG -> Blocks.SPRUCE_LOG.defaultBlockState();
            case SPRUCE_PLANKS -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case SPRUCE_FENCE -> Blocks.SPRUCE_FENCE.defaultBlockState();
            case SPRUCE_LEAVES -> Blocks.SPRUCE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            case WHITE_WOOL -> Blocks.WHITE_WOOL.defaultBlockState();
            case LIGHT_GRAY_WOOL -> Blocks.LIGHT_GRAY_WOOL.defaultBlockState();
            case GRAY_WOOL -> Blocks.GRAY_WOOL.defaultBlockState();
            case BROWN_CARPET -> Blocks.BROWN_CARPET.defaultBlockState();
            case LANTERN -> Blocks.LANTERN.defaultBlockState();
            case CAMPFIRE -> Blocks.CAMPFIRE.defaultBlockState();
            case HAY_BLOCK -> Blocks.HAY_BLOCK.defaultBlockState();
        };
    }
}
