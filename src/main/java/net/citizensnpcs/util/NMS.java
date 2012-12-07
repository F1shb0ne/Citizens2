package net.citizensnpcs.util;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.CitizensNPC;
import net.minecraft.server.v1_4_5.ControllerLook;
import net.minecraft.server.v1_4_5.DamageSource;
import net.minecraft.server.v1_4_5.EnchantmentManager;
import net.minecraft.server.v1_4_5.Entity;
import net.minecraft.server.v1_4_5.EntityLiving;
import net.minecraft.server.v1_4_5.EntityMonster;
import net.minecraft.server.v1_4_5.EntityTypes;
import net.minecraft.server.v1_4_5.MathHelper;
import net.minecraft.server.v1_4_5.MobEffectList;
import net.minecraft.server.v1_4_5.Navigation;
import net.minecraft.server.v1_4_5.NetworkManager;
import net.minecraft.server.v1_4_5.Packet;
import net.minecraft.server.v1_4_5.PathfinderGoalSelector;
import net.minecraft.server.v1_4_5.World;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.craftbukkit.v1_4_5.CraftWorld;
import org.bukkit.craftbukkit.v1_4_5.entity.CraftLivingEntity;
import org.bukkit.craftbukkit.v1_4_5.entity.CraftPlayer;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.material.Stairs;
import org.bukkit.material.Step;
import org.bukkit.util.Vector;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

@SuppressWarnings("unchecked")
public class NMS {
    private NMS() {
        // util class
    }

    private static final float DEFAULT_SPEED = 0.4F;
    private static Map<Class<? extends Entity>, Integer> ENTITY_CLASS_TO_INT;
    private static final Map<Class<? extends Entity>, Constructor<? extends Entity>> ENTITY_CONSTRUCTOR_CACHE = new WeakHashMap<Class<? extends Entity>, Constructor<? extends Entity>>();
    private static Map<Integer, Class<? extends Entity>> ENTITY_INT_TO_CLASS;
    private static Field GOAL_FIELD;
    private static Field LAND_SPEED_MODIFIER_FIELD;
    private static final Map<EntityType, Float> MOVEMENT_SPEEDS = Maps.newEnumMap(EntityType.class);
    private static Field NAVIGATION_WORLD_FIELD;
    private static Field PATHFINDING_RANGE;
    private static final Random RANDOM = Util.getFastRandom();
    private static Set<Integer> SLAB_MATERIALS = Sets.newHashSet();
    private static Field SPEED_FIELD;
    private static Set<Integer> STAIR_MATERIALS = Sets.newHashSet();

    private static Field THREAD_STOPPER;

    public static void addOrRemoveFromPlayerList(LivingEntity bukkitEntity, boolean remove) {
        if (bukkitEntity == null)
            return;
        EntityLiving handle = ((CraftLivingEntity) bukkitEntity).getHandle();
        if (handle.world == null)
            return;
        if (remove) {
            handle.world.players.remove(handle);
        } else if (!handle.world.players.contains(handle)) {
            handle.world.players.add(handle);
        }
    }

    public static void attack(EntityLiving handle, EntityLiving target) {
        int damage = handle instanceof EntityMonster ? ((EntityMonster) handle).c((Entity) target) : 2;

        if (handle.hasEffect(MobEffectList.INCREASE_DAMAGE)) {
            damage += 3 << handle.getEffect(MobEffectList.INCREASE_DAMAGE).getAmplifier();
        }

        if (handle.hasEffect(MobEffectList.WEAKNESS)) {
            damage -= 2 << handle.getEffect(MobEffectList.WEAKNESS).getAmplifier();
        }

        int knockbackLevel = 0;

        if (target instanceof EntityLiving) {
            damage += EnchantmentManager.a(handle, target);
            knockbackLevel += EnchantmentManager.getKnockbackEnchantmentLevel(handle, target);
        }

        boolean success = target.damageEntity(DamageSource.mobAttack(handle), damage);

        if (!success)
            return;
        if (knockbackLevel > 0) {
            target.g(-MathHelper.sin((float) (handle.yaw * Math.PI / 180.0F)) * knockbackLevel * 0.5F, 0.1D,

            MathHelper.cos((float) (handle.yaw * Math.PI / 180.0F)) * knockbackLevel * 0.5F);
            handle.motX *= 0.6D;
            handle.motZ *= 0.6D;
        }

        int fireAspectLevel = EnchantmentManager.getFireAspectEnchantmentLevel(handle);

        if (fireAspectLevel > 0)
            target.setOnFire(fireAspectLevel * 4);
    }

    public static void clearGoals(PathfinderGoalSelector... goalSelectors) {
        if (GOAL_FIELD == null || goalSelectors == null)
            return;
        for (PathfinderGoalSelector selector : goalSelectors) {
            try {
                List<?> list = (List<?>) NMS.GOAL_FIELD.get(selector);
                list.clear();
            } catch (Exception e) {
                Messaging.logTr(Messages.ERROR_CLEARING_GOALS, e.getMessage());
            }
        }
    }

    public static double distance(EntityLiving handle, Vector vector) {
        return Math.sqrt(distanceSquared(handle, vector));
    }

    public static double distanceSquared(EntityLiving handle, Vector vector) {
        return Math.pow(handle.locX - vector.getX(), 2) + Math.pow(handle.locY - vector.getY(), 2)
                + Math.pow(handle.locZ - vector.getZ(), 2);
    }

    private static Constructor<? extends Entity> getCustomEntityConstructor(Class<? extends Entity> clazz,
            EntityType type) throws SecurityException, NoSuchMethodException {
        Constructor<? extends Entity> constructor = ENTITY_CONSTRUCTOR_CACHE.get(clazz);
        if (constructor == null) {
            constructor = clazz.getConstructor(World.class);
            constructor.setAccessible(true);
            ENTITY_CLASS_TO_INT.put(clazz, (int) type.getTypeId());
            ENTITY_CONSTRUCTOR_CACHE.put(clazz, constructor);
        }
        return constructor;
    }

    private static Field getField(Class<?> clazz, String field) {
        Field f = null;
        try {
            f = clazz.getDeclaredField(field);
            f.setAccessible(true);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_GETTING_FIELD, field, e.getMessage());
        }
        return f;
    }

    public static float getSpeedFor(NPC npc) {
        EntityType entityType = npc.getBukkitEntity().getType();
        Float cached = MOVEMENT_SPEEDS.get(entityType);
        if (cached != null)
            return cached;
        if (SPEED_FIELD == null) {
            MOVEMENT_SPEEDS.put(entityType, DEFAULT_SPEED);
            return DEFAULT_SPEED;
        }
        try {
            float speed = SPEED_FIELD.getFloat(((CraftEntity)npc.getBukkitEntity()).getHandle());
            MOVEMENT_SPEEDS.put(entityType, speed);
            return speed;
        } catch (IllegalAccessException ex) {
            ex.printStackTrace();
            return DEFAULT_SPEED;
        }
    }

    public static boolean inWater(EntityLiving mcEntity) {
        return mcEntity.I() || mcEntity.J();
    }

    public static void look(ControllerLook controllerLook, EntityLiving handle, EntityLiving target) {
        controllerLook.a(target, 10.0F, handle.bp());
    }

    public static void look(EntityLiving handle, float yaw, float pitch) {
        handle.yaw = handle.ay = yaw;
        handle.pitch = pitch;
    }

    public static void registerEntityClass(Class<? extends Entity> clazz) {
        if (ENTITY_CLASS_TO_INT.containsKey(clazz))
            return;
        Class<?> search = clazz;
        while ((search = search.getSuperclass()) != null && Entity.class.isAssignableFrom(search)) {
            if (!ENTITY_CLASS_TO_INT.containsKey(search))
                continue;
            int code = ENTITY_CLASS_TO_INT.get(search);
            ENTITY_INT_TO_CLASS.put(code, clazz);
            ENTITY_CLASS_TO_INT.put(clazz, code);
            return;
        }
        throw new IllegalArgumentException("unable to find valid entity superclass");
    }

    public static void sendPacket(Player player, Packet packet) {
        ((CraftPlayer) player).getHandle().netServerHandler.sendPacket(packet);
    }

    public static void setHeadYaw(EntityLiving handle, float yaw) {
        handle.ay = yaw;
    }

    public static void setLandSpeedModifier(EntityLiving handle, float speed) {
        if (LAND_SPEED_MODIFIER_FIELD == null)
            return;
        try {
            LAND_SPEED_MODIFIER_FIELD.setFloat(handle, speed);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_UPDATING_SPEED, e.getMessage());
        }
    }

    public static org.bukkit.entity.Entity spawnCustomEntity(org.bukkit.World world, Location at,
            Class<? extends Entity> clazz, EntityType type) {
        World handle = ((CraftWorld) world).getHandle();
        Entity entity = null;
        try {
            Constructor<? extends Entity> constructor = getCustomEntityConstructor(clazz, type);
            entity = constructor.newInstance(handle);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_SPAWNING_CUSTOM_ENTITY, e.getMessage());
            return null;
        }
        handle.addEntity(entity);
        entity.setLocation(at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        return entity.getBukkitEntity();
    }

    public static void stopNetworkThreads(NetworkManager manager) {
        if (THREAD_STOPPER == null)
            return;
        try {
            THREAD_STOPPER.set(manager, false);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_STOPPING_NETWORK_THREADS, e.getMessage());
        }
    }

    public static void trySwim(EntityLiving handle) {
        trySwim(handle, 0.04F);
    }

    public static void trySwim(EntityLiving handle, float power) {
        if (RANDOM.nextFloat() < 0.8F && inWater(handle)) {
            handle.motY += power;
        }
    }

    public static void updateAI(EntityLiving entity) {
        updateSenses(entity);
        entity.getNavigation().e();
        entity.getControllerMove().c();
        entity.getControllerLook().a();
        entity.getControllerJump().b();
    }

    public static void updateNavigationWorld(org.bukkit.entity.Entity entity, org.bukkit.World world) {
        if (NAVIGATION_WORLD_FIELD == null || !(entity instanceof LivingEntity))
            return;
        EntityLiving handle = ((CraftLivingEntity) entity).getHandle();
        World worldHandle = ((CraftWorld) world).getHandle();
        try {
            NAVIGATION_WORLD_FIELD.set(handle.getNavigation(), worldHandle);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_UPDATING_NAVIGATION_WORLD, e.getMessage());
        }
    }

    public static void updatePathfindingRange(CitizensNPC npc, float pathfindingRange) {
        if (PATHFINDING_RANGE == null)
            return;
        Navigation navigation = npc.getHandle().getNavigation();
        try {
            PATHFINDING_RANGE.set(navigation, pathfindingRange);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_UPDATING_PATHFINDING_RANGE, e.getMessage());
        }
    }

    public static void updateSenses(EntityLiving entity) {
        entity.aA().a();
    }

    static {
        // true field above false and three synchronised lists
        THREAD_STOPPER = getField(NetworkManager.class, "m");

        // constants taken from source code
        MOVEMENT_SPEEDS.put(EntityType.CHICKEN, 0.25F);
        MOVEMENT_SPEEDS.put(EntityType.COW, 0.2F);
        MOVEMENT_SPEEDS.put(EntityType.CREEPER, 0.3F);
        MOVEMENT_SPEEDS.put(EntityType.IRON_GOLEM, 0.15F);
        MOVEMENT_SPEEDS.put(EntityType.MUSHROOM_COW, 0.2F);
        MOVEMENT_SPEEDS.put(EntityType.OCELOT, 0.23F);
        MOVEMENT_SPEEDS.put(EntityType.SHEEP, 0.25F);
        MOVEMENT_SPEEDS.put(EntityType.SNOWMAN, 0.25F);
        MOVEMENT_SPEEDS.put(EntityType.PIG, 0.27F);
        MOVEMENT_SPEEDS.put(EntityType.PLAYER, 1F);
        MOVEMENT_SPEEDS.put(EntityType.VILLAGER, 0.3F);

        LAND_SPEED_MODIFIER_FIELD = getField(EntityLiving.class, "bO");
        SPEED_FIELD = getField(EntityLiving.class, "bG");
        NAVIGATION_WORLD_FIELD = getField(Navigation.class, "b");
        PATHFINDING_RANGE = getField(Navigation.class, "e");
        GOAL_FIELD = getField(PathfinderGoalSelector.class, "a");

        try {
            Field field = getField(EntityTypes.class, "d");
            ENTITY_INT_TO_CLASS = (Map<Integer, Class<? extends Entity>>) field.get(null);
            field = getField(EntityTypes.class, "e");
            ENTITY_CLASS_TO_INT = (Map<Class<? extends Entity>, Integer>) field.get(null);
        } catch (Exception e) {
            Messaging.logTr(Messages.ERROR_GETTING_ID_MAPPING, e.getMessage());
        }
    }

    static {
        for (Material material : Material.values()) {
            if (Step.class.isAssignableFrom(material.getData()))
                SLAB_MATERIALS.add(material.getId());
            else if (Stairs.class.isAssignableFrom(material.getData()))
                STAIR_MATERIALS.add(material.getId());
        }
    }
}
