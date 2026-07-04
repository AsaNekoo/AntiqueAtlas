package hunternif.mc.impl.atlas.core.scaning;

import hunternif.mc.api.AtlasAPI;
import hunternif.mc.impl.atlas.AntiqueAtlasMod;
import hunternif.mc.impl.atlas.core.AtlasData;
import hunternif.mc.impl.atlas.core.ITileStorage;
import hunternif.mc.impl.atlas.core.TileInfo;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;

public class WorldScanner {

    /**
     * Maps dimension ID to biomeAnalyzer.
     */
    private final Map<RegistryKey<World>, ITileDetector> biomeAnalyzers = new HashMap<>();
    private final TileDetectorBase tileDetectorOverworld = new TileDetectorBase();

    public WorldScanner() {
        setBiomeDetectorForWorld(World.OVERWORLD, tileDetectorOverworld);
        setBiomeDetectorForWorld(World.NETHER, new TileDetectorNether());
        setBiomeDetectorForWorld(World.END, new TileDetectorEnd());
    }


    /**
     * If not found, returns the analyzer for overworld.
     */
    private ITileDetector getBiomeDetectorForWorld(RegistryKey<World> world) {
        ITileDetector biomeAnalyzer = biomeAnalyzers.get(world);

        return biomeAnalyzer == null ? tileDetectorOverworld : biomeAnalyzer;
    }

    private void setBiomeDetectorForWorld(RegistryKey<World> world, ITileDetector biomeAnalyzer) {
        biomeAnalyzers.put(world, biomeAnalyzer);
    }

    /**
     * Updates map data around player
     *
     * @return A set of the new tiles, mostly so the server can sync those with relevant clients.
     */
    public Collection<TileInfo> updateAtlasAroundPlayer(AtlasData data, PlayerEntity player) {
        World world = player.getEntityWorld();

        // Update the actual map only so often:
        int newScanInterval = Math.round(AntiqueAtlasMod.CONFIG.newScanInterval * 20);

        long worldTime = world.getTime();
        if (worldTime % newScanInterval != 0) {
            return Collections.emptyList(); //no new tiles
        }

        ArrayList<TileInfo> updatedTiles = new ArrayList<>();

        int rescanInterval = newScanInterval * AntiqueAtlasMod.CONFIG.rescanRate;
        boolean rescanRequired = AntiqueAtlasMod.CONFIG.doRescan && worldTime % rescanInterval == 0;

        RegistryKey<World> dimension = world.getRegistryKey();
        ITileDetector biomeDetector = getBiomeDetectorForWorld(dimension);
        ITileStorage storedData = data.getWorldData(dimension);

        int scanRadius = biomeDetector.getScanRadius();
        int scanRadiusSq = scanRadius * scanRadius;

        ChunkPos playerChunkPos = player.getChunkPos();
        int playerChunkX = playerChunkPos.x;
        int playerChunkZ = playerChunkPos.z;

        // Look at chunks around in a circular area:
        for (int dx = -scanRadius; dx <= scanRadius; dx++) {
            int dzMax = (int) Math.sqrt(scanRadiusSq - dx * dx);
            int chunkX = playerChunkX + dx;

            for (int dz = -dzMax; dz <= dzMax; dz++) {
                int chunkZ = playerChunkZ + dz;

                TileInfo update = updateAtlasForChunk(data, world, dimension, storedData, biomeDetector,
                        chunkX, chunkZ, rescanRequired);
                if (update != null) {
                    updatedTiles.add(update);
                }
            }
        }
        return updatedTiles;
    }

    private TileInfo updateAtlasForChunk(AtlasData data, World world, RegistryKey<World> dimension,
                                          ITileStorage storedData, ITileDetector biomeDetector,
                                          int x, int z, boolean rescanRequired) {
        Identifier oldTile = storedData.getTile(x, z);

        // Check if there's a custom tile at the location:
        // Custom tiles overwrite even the chunks already seen.
        Identifier tile = AtlasAPI.getTileAPI().getGlobalTile(world, x, z);

        // If there's no custom tile, check the actual chunk:
        if (tile == null) {
            // If the chunk has been scanned previously, only re-scan it so often:
            if (oldTile != null && !rescanRequired) {
                return null;
            }

            if (!world.getChunkManager().isChunkLoaded(x, z)) {
                return null;
            }

            // TODO FABRIC: forceChunkLoading crashes here
            Chunk chunk = world.getChunk(x, z, ChunkStatus.FULL, AntiqueAtlasMod.CONFIG.forceChunkLoading);

            // Skip chunk if it hasn't loaded yet:
            if (chunk == null) {
                return null;
            }

            tile = biomeDetector.getBiomeID(world, chunk);

            if (oldTile != null) {
                if (tile == null) {
                    // If the new tile is empty, remove the old one:
                    data.removeTile(dimension, x, z);
                } else if (!oldTile.equals(tile)) {
                    // Only update if the old tile's biome ID doesn't match the new one:
                    data.setTile(dimension, x, z, tile);
                    return new TileInfo(x, z, tile);
                }
            } else {
                // Scanning new chunk:
                if (tile != null) {
                    data.setTile(dimension, x, z, tile);
                    return new TileInfo(x, z, tile);
                }
            }
        } else {
            // Only update the custom tile if it doesn't rewrite itself:
            if (oldTile == null || !oldTile.equals(tile)) {
                data.setTile(dimension, x, z, tile);
                data.markDirty();
                return new TileInfo(x, z, tile);
            }
        }

        return null;
    }
}