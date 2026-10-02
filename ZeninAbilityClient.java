package com.adam.zeninability;

import io.netty.buffer.Unpooled;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.EntityHitResult;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public class ZeninAbilityClient implements ClientModInitializer {
    private static KeyBinding key;
    private static boolean active;
    private static final Map<Integer,Integer> visuals = new HashMap<>();

    @Override public void onInitializeClient() {
        key = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.zeninability.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "category.zeninability"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (key.wasPressed()) {
                active = !active;
                ClientPlayNetworking.send(ZeninAbility.TOGGLE, new PacketByteBuf(Unpooled.buffer()));
            }
            visuals.replaceAll((id,t) -> t - 1);
            visuals.values().removeIf(t -> t <= 0);
        });

        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            if (!active || client.world == null || !(client.crosshairTarget instanceof EntityHitResult hit)) return false;
            if (hit.getEntity() instanceof MobEntity mob && player.squaredDistanceTo(mob) <= 36.0) {
                PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
                buf.writeVarInt(mob.getId());
                ClientPlayNetworking.send(ZeninAbility.TRAP, buf);
                return true;
            }
            return false;
        });

        ClientPlayNetworking.registerGlobalReceiver(ZeninAbility.VISUAL, (client, handler, buf, responseSender) -> {
            int id = buf.readVarInt(); boolean start = buf.readBoolean(); int ticks = buf.readVarInt();
            client.execute(() -> { if (start) visuals.put(id, ticks); else visuals.remove(id); });
        });

        HudRenderCallback.EVENT.register((ctx, tickDelta) -> renderHud(ctx));
        WorldRenderEvents.AFTER_ENTITIES.register(context -> renderTraps(context));
    }

    private static void renderHud(DrawContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        int x=10, y=mc.getWindow().getScaledHeight()-52;
        ctx.fill(x,y,x+128,y+42,0xAA111118); ctx.drawBorder(x,y,x+128,y+42,0xFFBBD9FF);
        ctx.drawText(mc.textRenderer,"ZENIN FRAME",x+8,y+7,0xFFFFFFFF,false);
        ctx.drawText(mc.textRenderer,active ? "ACTIVE  [Z]" : "READY   [Z]",x+8,y+23,active?0xFFFF7777:0xFFB0B0B0,false);
    }

    private static void renderTraps(WorldRenderEvents.AfterEntities context) {
        MinecraftClient mc = MinecraftClient.getInstance(); if (mc.world == null || visuals.isEmpty()) return;
        MatrixStack matrices = context.matrixStack(); Camera camera = context.camera(); Vec3d cam = camera.getPos();
        VertexConsumerProvider.Immediate providers = mc.getBufferBuilders().getEntityVertexConsumers();
        VertexConsumer v = providers.getBuffer(RenderLayer.getLines()); Matrix4f matrix = matrices.peek().getPositionMatrix();
        for (Integer id : visuals.keySet()) {
            Entity entity = mc.world.getEntityById(id); if (!(entity instanceof MobEntity mob)) continue;
            Box b = mob.getBoundingBox().expand(.16).offset(-cam.x,-cam.y,-cam.z);
            edge(v,matrix,b.minX,b.minY,b.minZ,b.maxX,b.minY,b.minZ); edge(v,matrix,b.maxX,b.minY,b.minZ,b.maxX,b.minY,b.maxZ);
            edge(v,matrix,b.maxX,b.minY,b.maxZ,b.minX,b.minY,b.maxZ); edge(v,matrix,b.minX,b.minY,b.maxZ,b.minX,b.minY,b.minZ);
            edge(v,matrix,b.minX,b.maxY,b.minZ,b.maxX,b.maxY,b.minZ); edge(v,matrix,b.maxX,b.maxY,b.minZ,b.maxX,b.maxY,b.maxZ);
            edge(v,matrix,b.maxX,b.maxY,b.maxZ,b.minX,b.maxY,b.maxZ); edge(v,matrix,b.minX,b.maxY,b.maxZ,b.minX,b.maxY,b.minZ);
            edge(v,matrix,b.minX,b.minY,b.minZ,b.minX,b.maxY,b.minZ); edge(v,matrix,b.maxX,b.minY,b.minZ,b.maxX,b.maxY,b.minZ);
            edge(v,matrix,b.maxX,b.minY,b.maxZ,b.maxX,b.maxY,b.maxZ); edge(v,matrix,b.minX,b.minY,b.maxZ,b.minX,b.maxY,b.maxZ);
        }
        providers.draw();
    }
    private static void edge(VertexConsumer v, Matrix4f m,double x1,double y1,double z1,double x2,double y2,double z2) {
        v.vertex(m,(float)x1,(float)y1,(float)z1).color(190,230,255,220).normal(0,1,0).next();
        v.vertex(m,(float)x2,(float)y2,(float)z2).color(190,230,255,220).normal(0,1,0).next();
    }
}
