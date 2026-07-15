package dev.leonetic.features.modules.combat;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;

public final class AntiPhaseModule extends Module {

    private final Setting<Double> range = num("Range", 4.0, 1.0, 6.0);
    private final Setting<Boolean> replaceScaffolding = bool("ReplaceScaffolding", true);
    private final Setting<Boolean> replaceLadders = bool("ReplaceLadders", true);
    private final Setting<Boolean> breakItemFrames = bool("BreakItemFrames", true);
    private final Setting<Boolean> onlyBreak = bool("OnlyBreak", false);
    private final Setting<Integer> delayMs = num("Delay", 50, 0, 1000);

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<Color> fillColor = color("FillColor", 255, 0, 0, 50).setPage("Render")
            .setVisibility(v -> render.getValue());
    private final Setting<Color> outlineColor = color("OutlineColor", 255, 0, 0, 255).setPage("Render")
            .setVisibility(v -> render.getValue());
    private final Setting<Float> fadeTime = num("FadeTime", 0.5f, 0.05f, 2.0f).setPage("Render")
            .setVisibility(v -> render.getValue());

    private long lastActionTime = 0L;
    private BlockPos renderPos = null;
    private long renderStart = 0L;

    public AntiPhaseModule() {
        super("AntiPhase", "Prevents players from phasing by replacing ladders, scaffolds, and breaking item frames.", Category.COMBAT);
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player == null || mc.level == null) return;

        if (System.currentTimeMillis() - lastActionTime < delayMs.getValue()) return;

        Vec3 eyePos = mc.player.getEyePosition();
        double rangeSq = range.getValue() * range.getValue();

        BlockPos closestBlockPos = null;
        Entity closestFrame = null;
        double closestDistSq = Double.MAX_VALUE;

        // 1. Search for Item Frames
        if (breakItemFrames.getValue()) {
            AABB searchArea = mc.player.getBoundingBox().inflate(range.getValue());
            for (Entity entity : mc.level.getEntities((Entity) null, searchArea)) {
                if (entity instanceof ItemFrame || entity instanceof GlowItemFrame) {
                    double dist = entity.position().distanceToSqr(eyePos);
                    if (dist <= rangeSq && dist < closestDistSq) {
                        closestDistSq = dist;
                        closestFrame = entity;
                        closestBlockPos = entity.blockPosition();
                    }
                }
            }
        }

        // 2. Search for Scaffolding and Ladders
        BlockPos playerPos = mc.player.blockPosition();
        int r = (int) Math.ceil(range.getValue());

        for (BlockPos pos : BlockPos.betweenClosed(playerPos.offset(-r, -r, -r), playerPos.offset(r, r, r))) {
            BlockState state = mc.level.getBlockState(pos);
            boolean isScaffold = state.is(Blocks.SCAFFOLDING);
            boolean isLadder = state.is(Blocks.LADDER);

            if ((isScaffold && replaceScaffolding.getValue()) || (isLadder && replaceLadders.getValue())) {
                double dist = eyePos.distanceToSqr(Vec3.atCenterOf(pos));
                if (dist <= rangeSq && dist < closestDistSq) {
                    closestDistSq = dist;
                    closestFrame = null; // Prioritize the block if it's closer
                    closestBlockPos = pos.immutable();
                }
            }
        }

        // 3. Execute Action on the closest target
        if (closestBlockPos != null) {
            if (closestFrame != null) {
                // Break item frame
                mc.gameMode.attack(mc.player, closestFrame);
                mc.player.swing(InteractionHand.MAIN_HAND);
            } else {
                BlockState state = mc.level.getBlockState(closestBlockPos);
                boolean isScaffold = state.is(Blocks.SCAFFOLDING);

                if (!onlyBreak.getValue()) {
                    Result obsidian = InventoryUtil.find(Items.OBSIDIAN, InventoryUtil.PLACE_SCOPE);
                    if (obsidian.found()) {
                        if (isScaffold && PlaceUtil.canPlace(closestBlockPos)) {
                            // Scaffolding is replaceable, place obsidian directly into it
                            if (Homovore.placementManager.enqueue(closestBlockPos, obsidian.slot())) {
                                Homovore.placementManager.flushQueue();
                            }
                        } else if (!isScaffold) {
                            // Ladders are not replaceable, we must break them first
                            breakBlock(closestBlockPos);
                        }
                    } else {
                        // No obsidian found, just break it
                        breakBlock(closestBlockPos);
                    }
                } else {
                    // Only break mode
                    breakBlock(closestBlockPos);
                }
            }

            mc.player.swing(InteractionHand.MAIN_HAND);
            lastActionTime = System.currentTimeMillis();
            
            // Update render state
            renderPos = closestBlockPos.immutable();
            renderStart = System.currentTimeMillis();
        }
    }

    /**
     * Sends instant break packets to the server for the given block.
     */
    private void breakBlock(BlockPos pos) {
        mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP
        ));
        mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP
        ));
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || !render.getValue() || renderPos == null) return;

        long age = System.currentTimeMillis() - renderStart;
        double fadeMs = fadeTime.getValue() * 1000.0;
        
        if (age > fadeMs) {
            renderPos = null;
            return;
        }

        double t = age / fadeMs;
        float alphaMult = (float) (1.0f - t);

        Color fc = fillColor.getValue();
        Color oc = outlineColor.getValue();
        
        RenderUtil.drawBoxFilled(event.getMatrix(), renderPos,
                new Color(fc.getRed(), fc.getGreen(), fc.getBlue(), (int) (fc.getAlpha() * alphaMult)));
        RenderUtil.drawBox(event.getMatrix(), renderPos,
                new Color(oc.getRed(), oc.getGreen(), oc.getBlue(), (int) (oc.getAlpha() * alphaMult)), 1.5f);
    }
}
