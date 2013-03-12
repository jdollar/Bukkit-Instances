/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.cyberiantiger.minecraft.instances.unsafe;

import com.google.common.io.Files;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.logging.Level;
import net.minecraft.server.v1_4_R1.Chunk;
import net.minecraft.server.v1_4_R1.ChunkRegionLoader;
import net.minecraft.server.v1_4_R1.EntityTracker;
import net.minecraft.server.v1_4_R1.IAsyncChunkSaver;
import net.minecraft.server.v1_4_R1.IChunkLoader;
import net.minecraft.server.v1_4_R1.IDataManager;
import net.minecraft.server.v1_4_R1.IWorldAccess;
import net.minecraft.server.v1_4_R1.MethodProfiler;
import net.minecraft.server.v1_4_R1.MinecraftServer;
import net.minecraft.server.v1_4_R1.NBTTagCompound;
import net.minecraft.server.v1_4_R1.WorldData;
import net.minecraft.server.v1_4_R1.WorldManager;
import net.minecraft.server.v1_4_R1.WorldProvider;
import net.minecraft.server.v1_4_R1.WorldServer;
import net.minecraft.server.v1_4_R1.NBTCompressedStreamTools;
import net.minecraft.server.v1_4_R1.ServerNBTManager;
import net.minecraft.server.v1_4_R1.WorldProviderHell;
import net.minecraft.server.v1_4_R1.WorldProviderTheEnd;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.craftbukkit.v1_4_R1.CraftServer;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.cyberiantiger.minecraft.instances.Coord;
import org.cyberiantiger.minecraft.instances.FileUtils;
import org.cyberiantiger.minecraft.instances.Instances;
import org.cyberiantiger.minecraft.instances.PortalPair;
import org.cyberiantiger.minecraft.instances.generator.VoidGenerator;

/**
 *
 * @author antony
 */
public final class InstanceTools {
    public static final String FOLDER_NAME = "worlds";

    public static org.bukkit.World createInstance(final Instances instances, PortalPair portal, String sourceWorld) {
        File dataFolder = new File(instances.getServer().getWorldContainer(), sourceWorld);
        if (!dataFolder.isDirectory()) {
            instances.getLogger().info("Failed to create instance, could not find data folder " + dataFolder.getAbsolutePath() + " for world " + sourceWorld);
            return null;
        }

        MinecraftServer console = ((CraftServer) instances.getServer()).getServer();
        if (console == null) {
            instances.getLogger().info("Failed to create instance, could not locate console object.");
            return null;
        }

        int i = 0;
        while (instances.getServer().getWorld(sourceWorld + '-' + i) != null) {
            i++;
        }

        String instanceName = sourceWorld + '-' + i;

        File worldFolder = new File(instances.getDataFolder(), FOLDER_NAME);
        File saveDataFolder = new File(worldFolder, instanceName);

        if (saveDataFolder.isDirectory()) {
            try {
                final File tempFile = File.createTempFile("deleted_",".world", worldFolder);
                tempFile.delete();
                if(saveDataFolder.renameTo(tempFile)) {
                    instances.getLogger().info("Renamed old world data");
                    instances.getServer().getScheduler().runTaskAsynchronously(instances, new Runnable() {

                        public void run() {
                            try {
                                if (!FileUtils.deleteRecursively(tempFile)) {
                                    instances.getLogger().warning("Failed to delete archived instances world: " + tempFile);
                                }
                            } catch (IOException e) {
                                instances.getLogger().warning("Failed to delete archived instances world: " + tempFile);
                            }
                        }
                        
                    });
                } else {
                    if (!FileUtils.deleteRecursively(saveDataFolder)) {
                        instances.getLogger().log(Level.SEVERE, "Failed to delete world folder: " + saveDataFolder);
                        return null;
                    }
                }
            } catch (IOException ex) {
                instances.getLogger().log(Level.SEVERE, null, ex);
                return null;
            }
        }

        saveDataFolder.mkdirs();

        IDataManager dataManager =
                new InstanceDataManager(instances, dataFolder, saveDataFolder);

        // XXX: Copy paste from craftbukkit.
        int dimension = 10 + console.worlds.size();

        boolean used = false;
        do {
            for (WorldServer server : console.worlds) {
                used = server.dimension == dimension;
                if (used) {
                    dimension++;
                    break;
                }
            }
        } while (used);

        MethodProfiler profiler = console.methodProfiler;

        WorldData wd = dataManager.getWorldData();

        World.Environment env;

        switch (wd.j()) {
            case 0:
                env = World.Environment.NORMAL;
            case -1:
                env = World.Environment.NETHER;
            case 1:
                env = World.Environment.THE_END;
            default:
                env = World.Environment.NORMAL;
        }

        ChunkGenerator generator = new VoidGenerator(Biome.PLAINS, new Coord(wd.c(),wd.d(),wd.e()));

        WorldServer instanceWorld = new WorldServer(console, dataManager, instanceName, dimension, null, profiler, env, generator);

        instanceWorld.worldMaps = console.worlds.get(0).worldMaps;
        instanceWorld.tracker = new EntityTracker(instanceWorld);
        instanceWorld.addIWorldAccess((IWorldAccess) new WorldManager(console, instanceWorld));
        instanceWorld.difficulty = portal.getDifficulty().getValue();
        instanceWorld.keepSpawnInMemory = false;
        console.worlds.add(instanceWorld);

        instanceWorld.getWorld().getPopulators().addAll(generator.getDefaultPopulators(instanceWorld.getWorld()));

        instances.getServer().getPluginManager().callEvent(new WorldInitEvent(instanceWorld.getWorld()));
        instances.getServer().getPluginManager().callEvent(new WorldLoadEvent(instanceWorld.getWorld()));

        return instanceWorld.getWorld();
    }

    // Have to extend WorldNBTStorage - CB casts IDataManager to it in World.getWorldFolder()
    // cannot just implement IDataManager, PlayerFileData
    private static class InstanceDataManager extends ServerNBTManager {
        private static final String WORLD_DATA = "level.dat";
        private static final String WORLD_DATA_OLD = "level.dat_old";

        private final Instances instances;
        private final File loadDataFolder;
        private final String world;
        private final UUID uid;
        private WorldData worldData;

        public InstanceDataManager(Instances instances, File loadDataFolder, File saveDataFolder) {
            // false flag - do not create players directory.
            super(saveDataFolder.getParentFile(), saveDataFolder.getName(), false);
            this.instances = instances;
            this.loadDataFolder = loadDataFolder;
            this.world = saveDataFolder.getName();
            this.uid = UUID.randomUUID();
        }

        @Override
        public WorldData getWorldData() {
            File levelData = new File(getDirectory(), WORLD_DATA);
            if (levelData.isFile()) {
                return super.getWorldData();
            }
            levelData = new File(getDirectory(), WORLD_DATA_OLD);
            if (levelData.isFile()) {
                return super.getWorldData();
            }
            
            File file1 = new File(loadDataFolder, WORLD_DATA);
            NBTTagCompound nbttagcompound;
            NBTTagCompound nbttagcompound1;
            
            if (file1.exists()) {
                try {
                    nbttagcompound = NBTCompressedStreamTools.a((InputStream) (new FileInputStream(file1)));
                    nbttagcompound1 = nbttagcompound.getCompound("Data");
                    return new WorldData(nbttagcompound1);
                } catch (Exception exception) {
                    exception.printStackTrace();
                }
            }
            
            file1 = new File(loadDataFolder, WORLD_DATA_OLD);
            if (file1.exists()) {
                try {
                    nbttagcompound = NBTCompressedStreamTools.a((InputStream) (new FileInputStream(file1)));
                    nbttagcompound1 = nbttagcompound.getCompound("Data");
                    return new WorldData(nbttagcompound1);
                } catch (Exception exception1) {
                    exception1.printStackTrace();
                }
            }
            
            return null;
        }

        @Override
        public IChunkLoader createChunkLoader(WorldProvider wp) {
            File loadChunkDir;
            File saveChunkDir;
            if (wp instanceof WorldProviderHell) {
                loadChunkDir = new File(loadDataFolder, "DIM-1");
                saveChunkDir = new File(getDirectory(), "DIM-1");
            } else if (wp instanceof WorldProviderTheEnd) {
                loadChunkDir = new File(loadDataFolder, "DIM1");
                saveChunkDir = new File(getDirectory(), "DIM1");
            } else {
                loadChunkDir = loadDataFolder;
                saveChunkDir = getDirectory();
            }
            ChunkRegionLoader loadLoader = new ChunkRegionLoader(loadChunkDir);
            ChunkRegionLoader saveLoader = new ChunkRegionLoader(saveChunkDir);
            return new InstanceChunkLoader(loadLoader, saveLoader);
        }

        @Override
        public File getDataFile(String string) {
            File result = new File(this.loadDataFolder, string + ".dat");
            if (result.isFile()) {
                return result;
            }
            File source = new File(getDirectory(), string + ".dat");
            if (!source.isFile()) {
                return result;
            }
            try {
                Files.copy(source, result);
            } catch (IOException ex) {
                instances.getLogger().log(Level.SEVERE, "Error copying " + source.getPath() + " to " + result.getPath() + " for Instance world: " + world, ex);
            }
            return result;
        }
    }

    // Safe not to extend ChunkRegionLoader - CB does not cast to ChunkRegionLoader anywhere.
    public static final class InstanceChunkLoader implements IChunkLoader, IAsyncChunkSaver {

        private final ChunkRegionLoader loadLoader;
        private final ChunkRegionLoader saveLoader;

        public InstanceChunkLoader(ChunkRegionLoader loadLoader, ChunkRegionLoader saveLoader) {
            this.loadLoader = loadLoader;
            this.saveLoader = saveLoader;
        }

        public Chunk a(net.minecraft.server.v1_4_R1.World world, int i, int j) {
            if (saveLoader.chunkExists(world, i, j)) {
                return saveLoader.a(world, i, j);
            }
            return loadLoader.a(world, i, j);
        }

        public void a(net.minecraft.server.v1_4_R1.World world, Chunk chunk) {
            saveLoader.a(world, chunk);
        }

        public void b(net.minecraft.server.v1_4_R1.World world, Chunk chunk) {
            // Assume this is supposed to be some sort of save operation.
            // Can't tell from NMS - empty method.
            saveLoader.b(world, chunk);
        }

        public void a() {
            // XXX: Can't guess if this is a save or load operation.
        }

        public void b() {
            // XXX: Can't guess if this is a save or load operation.
        }

        public boolean c() {
            // Looks like a flush() / sync() method.
            return saveLoader.c();
        }
    }
}