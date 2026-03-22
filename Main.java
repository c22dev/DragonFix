package ch.cclerc.dragonfix;

import net.minecraft.server.v1_12_R1.EntityAreaEffectCloud;
import net.minecraft.server.v1_12_R1.EntityTracker;
import net.minecraft.server.v1_12_R1.WorldServer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.craftbukkit.v1_12_R1.CraftWorld;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.UUID;
import java.util.logging.Logger;

public class Main extends JavaPlugin implements Listener {

    private static Unsafe UNSAFE;


    private static Field RADIUS_FIELD;
    private static Field TRACKER_FIELD;

    private static final int    CLOUD_DURATION_TICKS  = 200;
    private static final int    DAMAGE_INTERVAL_TICKS = 25;
    private static final double DAMAGE_AMOUNT         = 6.0;
    private static final double DEFAULT_RADIUS        = 3.0;
    private static final int    PARTICLE_POINTS       = 12;
    private static final int    PARTICLE_INTERVAL     = 3;

    private static final double[] RING_COS = new double[PARTICLE_POINTS];
    private static final double[] RING_SIN = new double[PARTICLE_POINTS];

    static {
        double step = 2.0 * Math.PI / PARTICLE_POINTS;
        for (int i = 0; i < PARTICLE_POINTS; i++) {
            RING_COS[i] = Math.cos(i * step);
            RING_SIN[i] = Math.sin(i * step);
        }
    }

    @Override
    public void onEnable() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            UNSAFE = (Unsafe) f.get(null);
        } catch (Exception e) {
            getLogger().severe("cannot obtain unsafe" + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            RADIUS_FIELD = EntityAreaEffectCloud.class.getDeclaredField("radius");
            RADIUS_FIELD.setAccessible(true);
        } catch (Exception e) {
            getLogger().warning("using defualt cache" + e.getMessage());
        }

        TRACKER_FIELD = findField(WorldServer.class, "tracker");
        if (TRACKER_FIELD != null) TRACKER_FIELD.setAccessible(true);

        getServer().getPluginManager().registerEvents(this, this);
        for (World world : getServer().getWorlds()) hookTracker(world);

        getLogger().info("DragonFix enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("DragonFix disabled goodbye!!");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof DragonFireball || entity instanceof AreaEffectCloud)
            event.setCancelled(true);
    }


    void startBreathDamage(final net.minecraft.server.v1_12_R1.Entity nmsEntity,
                           final String worldName) {
        final double x = nmsEntity.locX;
        final double y = nmsEntity.locY;
        final double z = nmsEntity.locZ;

        double r = DEFAULT_RADIUS;
        if (RADIUS_FIELD != null) {
            try { r = RADIUS_FIELD.getFloat(nmsEntity); } catch (Exception ignored) {}
        }
        final double radius   = (r > 0 && r < 16) ? r : DEFAULT_RADIUS;
        final double radiusSq = radius * radius;

        getLogger().info(String.format("[Breath] Cloud at %.1f,%.1f,%.1f world=%s",
                x, y, z, worldName));

        final double[] ringX = new double[PARTICLE_POINTS];
        final double[] ringZ = new double[PARTICLE_POINTS];
        for (int i = 0; i < PARTICLE_POINTS; i++) {
            ringX[i] = x + radius * RING_COS[i];
            ringZ[i] = z + radius * RING_SIN[i];
        }

        final int[]        ticksElapsed = {0};
        final BukkitTask[] taskRef      = {null};
        final HashMap<UUID, Integer> lastHit = new HashMap<UUID, Integer>(4);
        final Location reusableLoc = new Location(null, 0, 0, 0);

        taskRef[0] = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                final int tick = ++ticksElapsed[0];
                if (tick >= CLOUD_DURATION_TICKS) {
                    taskRef[0].cancel();
                    return;
                }

                final World world = getServer().getWorld(worldName);
                if (world == null) { taskRef[0].cancel(); return; }

                if (tick % PARTICLE_INTERVAL == 0) {
                    for (int i = 0; i < PARTICLE_POINTS; i++) {
                        reusableLoc.setWorld(world);
                        reusableLoc.setX(ringX[i]);
                        reusableLoc.setY(y + 0.05);
                        reusableLoc.setZ(ringZ[i]);
                        try {
                            world.spawnParticle(org.bukkit.Particle.DRAGON_BREATH,
                                    reusableLoc, 3, 0.1, 0.3, 0.1, 0.0);
                        } catch (Exception ignored) {}
                    }

                    final double base = tick * 0.25;
                    for (int i = 0; i < 6; i++) {
                        double angle = base + i * (Math.PI / 3);
                        double dist  = radius * (i % 2 == 0 ? 0.7 : 0.5);
                        reusableLoc.setWorld(world);
                        reusableLoc.setX(x + dist * Math.cos(angle));
                        reusableLoc.setY(y + 0.1);
                        reusableLoc.setZ(z + dist * Math.sin(angle));
                        try {
                            world.spawnParticle(org.bukkit.Particle.DRAGON_BREATH,
                                    reusableLoc, 4, 0.2, 0.4, 0.2, 0.0);
                        } catch (Exception ignored) {}
                    }
                }

                final Collection<? extends Player> online = getServer().getOnlinePlayers();
                for (Player player : online) {
                    final Location loc = player.getLocation();
                    if (!loc.getWorld().getName().equals(worldName)) continue;

                    final double dx = loc.getX() - x;
                    final double dy = loc.getY() - y;
                    final double dz = loc.getZ() - z;
                    if (dx*dx + dy*dy + dz*dz > radiusSq) continue;

                    final UUID uid  = player.getUniqueId();
                    final int  last = lastHit.getOrDefault(uid, tick - DAMAGE_INTERVAL_TICKS);
                    if (tick - last >= DAMAGE_INTERVAL_TICKS) {
                        player.damage(DAMAGE_AMOUNT);
                        lastHit.put(uid, tick);
                        getLogger().info("[Breath] Damaged " + player.getName());
                    }
                }
            }
        }, 0L, 1L);
    }


    private void hookTracker(World world) {
        if (TRACKER_FIELD == null) {
            getLogger().severe("[Tracker] 'tracker' ?????? hook skipped for " + world.getName());
            return;
        }
        try {
            WorldServer nmsWorld = ((CraftWorld) world).getHandle();
            EntityTracker original = (EntityTracker) TRACKER_FIELD.get(nmsWorld);

            FilteringTracker filtering =
                    (FilteringTracker) UNSAFE.allocateInstance(FilteringTracker.class);
            copyFields(EntityTracker.class, original, filtering);
            filtering.log       = getLogger();
            filtering.plugin    = this;
            filtering.worldName = world.getName();

            TRACKER_FIELD.set(nmsWorld, filtering);
            getLogger().info("[Tracker] replaced EntityTracker for world: " + world.getName());
        } catch (Exception e) {
            getLogger().severe("[Tracker] hook failed for " + world.getName() + ": " + e);
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static void copyFields(Class<?> clazz, Object src, Object dst) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                try { f.set(dst, f.get(src)); } catch (Exception ignored) {}
            }
        }
    }

    static final class FilteringTracker extends EntityTracker {

        Logger log;
        Main   plugin;
        String worldName;

        FilteringTracker() { super(null); }

        @Override
        public void track(net.minecraft.server.v1_12_R1.Entity entity) {
            if (entity instanceof EntityAreaEffectCloud) {
                entity.dead = true;
                if (log != null)
                    log.warning("[Tracker] supressed AEC id=" + entity.getId());
                if (plugin != null && worldName != null)
                    plugin.startBreathDamage(entity, worldName);
                return;
            }
            super.track(entity);
        }
    }
}
