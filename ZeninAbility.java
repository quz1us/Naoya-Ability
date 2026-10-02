package com.adam.zeninability;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import io.netty.buffer.Unpooled;
import java.util.*;

public class ZeninAbility implements ModInitializer {
    public static final String MOD_ID = "zeninability";
    public static final Identifier TOGGLE = new Identifier(MOD_ID, "toggle");
    public static final Identifier TRAP = new Identifier(MOD_ID, "trap");
    public static final Identifier VISUAL = new Identifier(MOD_ID, "visual");
    private static final Map<UUID, Trap> TRAPS = new HashMap<>();
    private static final Set<UUID> ACTIVE = new HashSet<>();

    private record Trap(UUID owner, long expires) {}

    @Override public void onInitialize() {
        ServerPlayNetworking.registerGlobalReceiver(TOGGLE, (server, player, handler, buf, responseSender) ->
            server.execute(() -> { if (!ACTIVE.remove(player.getUuid())) ACTIVE.add(player.getUuid()); }));

        ServerPlayNetworking.registerGlobalReceiver(TRAP, (server, player, handler, buf, responseSender) -> {
            int id = buf.readVarInt();
            server.execute(() -> {
                if (!ACTIVE.contains(player.getUuid())) return;
                Entity entity = player.getWorld().getEntityById(id);
                if (!(entity instanceof MobEntity mob) || mob.isRemoved() || mob.isDead()) return;
                if (player.distanceTo(mob) > 6.0f || TRAPS.containsKey(mob.getUuid())) return;
                TRAPS.put(mob.getUuid(), new Trap(player.getUuid(), player.getWorld().getTime() + 40));
                mob.setVelocity(Vec3d.ZERO);
                mob.velocityModified = true;
                sendVisual(player, mob, true, 40);
                player.getWorld().playSound(null, mob.getBlockPos(), SoundEvents.BLOCK_GLASS_PLACE, SoundCategory.BLOCKS, .7f, 1.2f);
            });
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClient || !(entity instanceof MobEntity mob)) return ActionResult.PASS;
            Trap trap = TRAPS.get(mob.getUuid());
            if (trap == null || !trap.owner().equals(player.getUuid())) return ActionResult.PASS;
            double baseAttack = player.getAttributeValue(EntityAttributes.GENERIC_ATTACK_DAMAGE);
            mob.damage(player.getDamageSources().playerAttack(player), (float)(baseAttack * 3.0));
            shatter(world, mob, true);
            TRAPS.remove(mob.getUuid());
            sendVisual(player, mob, false, 0);
            return ActionResult.FAIL;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, Trap>> iterator = TRAPS.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Trap> entry = iterator.next();
                UUID targetId = entry.getKey(); Trap trap = entry.getValue();
                PlayerEntity owner = server.getPlayerManager().getPlayer(trap.owner());
                if (owner == null) { iterator.remove(); continue; }
                Entity entity = owner.getWorld().getEntity(targetId);
                if (!(entity instanceof MobEntity mob) || mob.isRemoved() || mob.isDead()) { iterator.remove(); continue; }
                mob.setVelocity(Vec3d.ZERO); mob.velocityModified = true;
                if (owner.getWorld().getTime() >= trap.expires()) {
                    mob.damage(owner.getDamageSources().generic(), 3.0f);
                    shatter(owner.getWorld(), mob, false);
                    sendVisual(owner, mob, false, 0);
                    iterator.remove();
                }
            }
        });
    }

    private static void shatter(World world, MobEntity mob, boolean hit) {
        Vec3d p = mob.getPos().add(0, mob.getHeight() * .55, 0);
        world.playSound(null, mob.getBlockPos(), SoundEvents.BLOCK_GLASS_BREAK, SoundCategory.BLOCKS, 1f, hit ? 1.15f : 1f);
        if (world instanceof net.minecraft.server.world.ServerWorld sw) {
            sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.GLASS.getDefaultState()), p.x, p.y, p.z, 45, .55, .8, .55, .18);
            sw.spawnParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 10, .3, .4, .3, .03);
        }
    }

    private static void sendVisual(PlayerEntity player, Entity target, boolean start, int ticks) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        buf.writeVarInt(target.getId()); buf.writeBoolean(start); buf.writeVarInt(ticks);
        ServerPlayNetworking.send(player, VISUAL, buf);
    }
}
