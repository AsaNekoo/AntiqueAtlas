package hunternif.mc.impl.atlas.core.scaning;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import hunternif.mc.impl.atlas.AntiqueAtlas;
import hunternif.mc.impl.atlas.core.TileIdMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Detects the 256 vanilla biomes, water pools and lava pools.
 * Water and beach biomes are given priority because shore line is the defining
 * feature of the map, and so that rivers are more connected.
 *
 * @author Hunternif
 */
public class TileDetectorBase implements ITileDetector {
    /**
     * Biome used for occasional pools of water.
     * This used our own representation of biomes, but this was switched to Minecraft biomes.
     * So in absence of a better idea, this will just count as River from now on.
     */
    private static final ResourceLocation waterPoolBiome = Biomes.RIVER.location();
    /**
     * Increment the counter for water biomes by this much during iteration.
     * This is done so that water pools are more visible.
     */
    private static final int priorityRavine = 12, priorityWaterPool = 4, priorityLavaPool = 6;

    /**
     * Minimum depth in the ground to be considered a ravine
     */
    private static final int ravineMinDepth = 7;

    /**
     * Set to true for biome IDs that return true for BiomeDictionary.isBiomeOfType(WATER)
     */
    private static final Set<ResourceLocation> waterBiomes = new HashSet<>();
    /**
     * Set to true for biome IDs that return true for BiomeDictionary.isBiomeOfType(BEACH)
     */
    private static final Set<ResourceLocation> beachBiomes = new HashSet<>();

    private static final Set<ResourceLocation> swampBiomes = new HashSet<>();

    /**
     * Number of distinct TileHeightType values. Cached once to avoid repeated
     * values() array allocation (values() allocates a new array every call).
     */
    private static final int HEIGHT_TYPE_COUNT = TileHeightType.values().length;

    private static final Map<ResourceLocation, ResourceLocation[]> combinedIdCache = new HashMap<>();

    /**
     * Scan all registered biomes to mark biomes of certain types that will be
     * given higher priority when identifying mean biome ID for a chunk.
     * (Currently WATER, BEACH and SWAMP)
     */
    public static void scanBiomeTypes(Level level) {
        beachBiomes.clear();
        waterBiomes.clear();
        swampBiomes.clear();
        level.registryAccess().registryOrThrow(Registries.BIOME).holders().forEach(biome -> {
            if (biome.is(BiomeTags.IS_BEACH)) beachBiomes.add(biome.key().location());
            if (biome.is(BiomeTags.IS_RIVER)) waterBiomes.add(biome.key().location());
            if (biome.is(BiomeTags.IS_OCEAN)) waterBiomes.add(biome.key().location());
            if (biome.is(BiomeTags.HAS_RUINED_PORTAL_SWAMP)) swampBiomes.add(biome.key().location());
        });
        combinedIdCache.clear();
    }

    int priorityForBiome(ResourceLocation biome) {
        if (waterBiomes.contains(biome)) {
            return 4;
        } else if (beachBiomes.contains(biome)) {
            return 3;
        } else {
            return 1;
        }
    }

    /* these are the values used by vanilla, but it just doesn't work for me.
    protected static TileHeightType getHeightType(double weirdness) {
        if (weirdness < (double) VanillaTerrainParameters.getNormalizedWeirdness(0.05f)) {
            return TileHeightType.VALLEY;
        }
        if (weirdness < (double) VanillaTerrainParameters.getNormalizedWeirdness(0.26666668f)) {
            return TileHeightType.LOW;
        }
        if (weirdness < (double) VanillaTerrainParameters.getNormalizedWeirdness(0.4f)) {
            return TileHeightType.MID;
        }
        if (weirdness < (double) VanillaTerrainParameters.getNormalizedWeirdness(0.56666666f)) {
            return TileHeightType.HIGH;
        }
        return TileHeightType.PEAK;
    } */

    protected static TileHeightType getHeightTypeFromY(int y, int sealevel) {
        if (y < sealevel + 10) {
            return TileHeightType.VALLEY;
        }
        if (y < sealevel + 20) {
            return TileHeightType.LOW;
        }
        if (y < sealevel + 35) {
            return TileHeightType.MID;
        }
        if (y < sealevel + 50) {
            return TileHeightType.HIGH;
        }
        return TileHeightType.PEAK;
    }

    protected static ResourceLocation getBiomeIdentifier(Level world, Biome biome) {
        return world.registryAccess().registryOrThrow(Registries.BIOME).getKey(biome);
    }

    protected static ResourceLocation getCombinedBiomeIdentifier(ResourceLocation baseBiome, TileHeightType type) {
        ResourceLocation[] slots = combinedIdCache.get(baseBiome);
        if (slots == null) {
            slots = new ResourceLocation[HEIGHT_TYPE_COUNT];
            combinedIdCache.put(baseBiome, slots);
        }

        int idx = type.ordinal();
        ResourceLocation id = slots[idx];
        if (id == null) {
            id = new ResourceLocation(baseBiome.getNamespace(), baseBiome.getPath() + "_" + type.getName());
            slots[idx] = id;
        }
        return id;
    }

    protected static void updateOccurrencesMap(Map<ResourceLocation, Integer> map, ResourceLocation biome, int weight) {
        int occurrence = map.getOrDefault(biome, 0) + weight;
        map.put(biome, occurrence);
    }

    protected static void updateOccurrencesMap(Map<ResourceLocation, Integer> map, Level world, Biome biome, TileHeightType type, int weight) {
        ResourceLocation id = getBiomeIdentifier(world, biome);
        id = getCombinedBiomeIdentifier(id, type);

        int occurrence = map.getOrDefault(id, 0) + weight;
        map.put(id, occurrence);
    }

    @Override
    public int getScanRadius() {
        return AntiqueAtlas.CONFIG.scanRadius;
    }

    /**
     * If no valid biome ID is found, returns null.
     *
     * @return the detected biome ID for the given chunk
     */
    @Override
    public ResourceLocation getBiomeID(Level world, ChunkAccess chunk) {
        Map<ResourceLocation, Integer> biomeOccurrences = new HashMap<>(16);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int seaLevel = world.getSeaLevel();

        var heightmap = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.MOTION_BLOCKING);

        boolean scanPonds = AntiqueAtlas.CONFIG.doScanPonds;
        boolean scanRavines = AntiqueAtlas.CONFIG.doScanRavines;
        int ravineCutoff = seaLevel - ravineMinDepth;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Biome biome = chunk.getNoiseBiome(x, seaLevel, z).value();
                int y = heightmap.getFirstAvailable(x, z);

                // Compute once per column, reuse for swamp check, priority, and occurrence key.
                ResourceLocation biomeId = getBiomeIdentifier(world, biome);

                if (scanPonds && y > 0) {
                    Block topBlock = chunk.getBlockState(pos.set(x, y - 1, z)).getBlock();
                    if (topBlock == Blocks.WATER) {
                        if (swampBiomes.contains(biomeId)) {
                            updateOccurrencesMap(biomeOccurrences, TileIdMap.SWAMP_WATER, priorityWaterPool);
                        } else {
                            updateOccurrencesMap(biomeOccurrences, waterPoolBiome, priorityWaterPool);
                        }
                    } else if (topBlock == Blocks.LAVA) {
                        updateOccurrencesMap(biomeOccurrences, TileIdMap.TILE_LAVA, priorityLavaPool);
                    }
                }

                if (scanRavines && y > 0 && y < ravineCutoff) {
                    updateOccurrencesMap(biomeOccurrences, TileIdMap.TILE_RAVINE, priorityRavine);
                }

                ResourceLocation combinedId = getCombinedBiomeIdentifier(biomeId, getHeightTypeFromY(y, seaLevel));
                int occurrence = biomeOccurrences.getOrDefault(combinedId, 0) + priorityForBiome(biomeId);
                biomeOccurrences.put(combinedId, occurrence);
            }
        }

        if (biomeOccurrences.isEmpty()) return null;

        Map.Entry<ResourceLocation, Integer> meanBiome =
            Collections.max(biomeOccurrences.entrySet(), Comparator.comparingInt(Map.Entry::getValue));
        return meanBiome.getKey();
    }
}