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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

public class Main extends JavaPlugin implements Listener {

    private static Unsafe UNSAFE;
    private static Field  RADIUS_FIELD;
    private static Field  TRACKER_FIELD;

    private static final int CLOUD_DURATION_TICKS = 200;
    private static final double RADIUS_SHRINK_PER_APP = 0.5;
    private static final int BASE_REAPPLY_TICKS = 25;
    private static final int REAPPLY_JITTER_TICKS = 10;
    private static final double CYLINDER_HALF_HEIGHT = 1.0;
    private static final int INSTANT_DAMAGE_ID = 7;
    private static final int PARTICLE_INTERVAL = 2;
    private static final int PARTICLES_PER_TICK = 8;
    private static final double DEFAULT_RADIUS = 3.0;

    private static final Random RNG = new Random();

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
        final double initialRadius = (r > 0 && r < 16) ? r : DEFAULT_RADIUS;

        getLogger().info(String.format("[Breath] Cloud at %.1f,%.1f,%.1f r=%.1f world=%s",
                x, y, z, initialRadius, worldName));

        final int[]        ticksElapsed = {0};
        final BukkitTask[] taskRef      = {null};
        final HashMap<UUID, Integer> nextHitTick = new HashMap<UUID, Integer>(4);
        final Location reusableLoc = new Location(null, 0, 0, 0);

        taskRef[0] = getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                final int tick = ++ticksElapsed[0];

                final double currentRadius = initialRadius
                        - RADIUS_SHRINK_PER_APP * (tick / (double) BASE_REAPPLY_TICKS);

                if (currentRadius < 0.5 || tick >= CLOUD_DURATION_TICKS) {
                    taskRef[0].cancel();
                    return;
                }

                final double radiusSq          = currentRadius * currentRadius;
                final double cylinderHalfH     = CYLINDER_HALF_HEIGHT + 1.8;

                final World world = getServer().getWorld(worldName);
                if (world == null) { taskRef[0].cancel(); return; }

                if (tick % PARTICLE_INTERVAL == 0) {
                    for (int i = 0; i < PARTICLES_PER_TICK; i++) {
                        double angle = RNG.nextDouble() * 2.0 * Math.PI;
                        double dist  = currentRadius * Math.sqrt(RNG.nextDouble());
                        reusableLoc.setWorld(world);
                        reusableLoc.setX(x + dist * Math.cos(angle));
                        reusableLoc.setY(y + RNG.nextDouble() * 1.5);
                        reusableLoc.setZ(z + dist * Math.sin(angle));
                        try {
                            world.spawnParticle(org.bukkit.Particle.DRAGON_BREATH,
                                    reusableLoc, 1, 0.0, 0.0, 0.0, 0.0);
                        } catch (Exception ignored) {}
                    }
                }

                reusableLoc.setWorld(world);
                reusableLoc.setX(x);
                reusableLoc.setY(y + CYLINDER_HALF_HEIGHT);
                reusableLoc.setZ(z);

                for (Entity e : getServer().getOnlinePlayers()) {
                    if (!(e instanceof Player)) continue;
                    final Player player = (Player) e;
                    final Location loc = player.getLocation();
                    if (!loc.getWorld().getName().equals(worldName)) continue;

                    final double dx = loc.getX() - x;
                    final double dz = loc.getZ() - z;
                    final double dy = loc.getY() - y;
                    if (dx*dx + dz*dz > radiusSq) continue;
                    if (Math.abs(dy) > cylinderHalfH) continue;

                    final UUID uid = player.getUniqueId();
                    if (!nextHitTick.containsKey(uid)) {
                        nextHitTick.put(uid, tick);
                    }

                    if (tick >= nextHitTick.get(uid)) {
                        try {
                            player.addPotionEffect(
                                    new PotionEffect(PotionEffectType.getById(INSTANT_DAMAGE_ID),
                                            1, 1, false, false),
                                    true);
                        } catch (Exception ex) {
                            player.damage(6.0);
                        }
                        nextHitTick.put(uid, tick + BASE_REAPPLY_TICKS
                                + RNG.nextInt(REAPPLY_JITTER_TICKS));
                        getLogger().info("[Breath] Damage to"
                                + player.getName()
                                + " (r=" + String.format("%.2f", currentRadius) + ")");
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
