package dev.leonetic.features.modules.world;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.AttackBlockEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.impl.render.Render3DEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.modules.combat.AutoMineModule;
import dev.leonetic.features.modules.combat.OffhandModule;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.manager.RotationRequest;
import dev.leonetic.manager.SwapManager;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.mixin.entity.EntityRotationAccessor;
import dev.leonetic.util.EnchantmentUtil;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import dev.leonetic.util.render.RenderUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Conservative single-block packet mining.
 *
 * <p>The module intentionally does not implement the old dual-mine/rebreak
 * packet burst. A live action owns exactly one block, one fastest-hotbar-tool
 * lease and one START. Non-instant blocks receive one STOP only after vanilla
 * progress reaches 1.0 and one additional client tick has elapsed.</p>
 */
public final class SpeedMineModule extends Module {

    private static final double USER_PRIORITY = 100.0;
    private static final int MINE_SWAP_PRIORITY = 65;
    private static final int ROTATION_PRIORITY = 70;
    private static final String ROTATION_ID = "SpeedMine";
    private static final long START_COOLDOWN_NANOS = 325_000_000L;

    private MiningAction active;
    private PendingMine pending;
    private BlockPos lastActivePos;

    private BlockPos lastFinishedPos;
    private boolean sawFinishedPosAir = true;
    private long nextStartNanos;
    private long nextStartTick;

    private BlockPos heldFinishPos;
    private int heldFinishTicks;

    private BlockPos deferredFinishPos;
    private long deferredFinishTick = Long.MAX_VALUE;

    private double currentServerTick;
    private boolean usingMainhandThisTick;
    private boolean heldToolThisTick;

    private SwapManager.SwapHandle mineSwapHandle;
    private long releaseSwapAtTick = Long.MAX_VALUE;

    public interface MineFinishListener {
        void onMineFinish(BlockPos pos);
    }

    private final CopyOnWriteArrayList<MineFinishListener> finishListeners =
            new CopyOnWriteArrayList<>();

    private final Setting<Integer> mineTimeoutTicks = num("MineTimeoutTicks", 200, 40, 600);
    private final Setting<Boolean> debugLog = bool("DebugLog", false);

    private final Setting<Boolean> render = bool("Render", true).setPage("Render");
    private final Setting<Float> lineWidth = num("LineWidth", 2.0f, 0.5f, 5.0f).setPage("Render");
    private final Setting<Color> lineColor = color("LineColor", 255, 255, 255, 150).setPage("Render");
    private final Setting<Color> sideColor = color("SideColor", 255, 180, 255, 60).setPage("Render");

    private static final class PendingMine {
        private final BlockPos pos;
        private final Direction preferredFace;
        private final double priority;
        private Direction primedFace;
        private long actionAfterTick = Long.MAX_VALUE;

        private PendingMine(BlockPos pos, Direction preferredFace, double priority) {
            this.pos = pos;
            this.preferredFace = preferredFace;
            this.priority = priority;
        }
    }

    public SpeedMineModule() {
        super("SpeedMine", "Single-block, vanilla-timed mining for Grim v2", Category.WORLD);
    }

    public void addFinishListener(MineFinishListener listener) {
        finishListeners.addIfAbsent(listener);
    }

    public void removeFinishListener(MineFinishListener listener) {
        finishListeners.remove(listener);
    }

    private void fireFinish(BlockPos pos) {
        for (MineFinishListener listener : finishListeners) listener.onMineFinish(pos);
    }

    @Override
    public void onDisable() {
        if (active != null) sendCancel(active.pos);
        active = null;
        pending = null;
        deferredFinishPos = null;
        deferredFinishTick = Long.MAX_VALUE;
        Homovore.rotationManager.cancel(ROTATION_ID);

        if (mineSwapHandle != null) {
            Homovore.swapManager.release(mineSwapHandle);
            mineSwapHandle = null;
        }
        releaseSwapAtTick = Long.MAX_VALUE;
    }

    public boolean silentBreakBlock(BlockPos pos, double priority) {
        return silentBreakBlock(pos, null, priority);
    }

    public boolean silentBreakBlock(BlockPos pos, Direction preferredFace, double priority) {
        if (nullCheck() || pos == null) return false;
        if (mc.player.isCreative() || mc.player.isSpectator()) return false;
        if (alreadyBreaking(pos)) return true;
        if (!canBreak(pos) || !inBreakRange(pos)) return false;
        if (pos.equals(lastFinishedPos) && !sawFinishedPosAir) return false;

        PendingMine request = new PendingMine(pos.immutable(), preferredFace, priority);
        if (active == null) {
            if (pending == null || priority >= pending.priority) {
                if (pending != null && !pending.pos.equals(pos)) {
                    Homovore.rotationManager.cancel(ROTATION_ID);
                }
                pending = request;
            }
            return true;
        }

        // Keep one active source-to-sink path. A replacement waits until the
        // current action has finished or has been cancelled.
        if (pending == null || priority >= pending.priority) pending = request;
        return true;
    }

    public boolean alreadyBreaking(BlockPos pos) {
        return pos != null && ((active != null && pos.equals(active.pos))
                || (pending != null && pos.equals(pending.pos)));
    }

    @Subscribe
    private void onAttackBlock(AttackBlockEvent event) {
        if (nullCheck() || mc.player.isCreative() || mc.player.isSpectator()) return;
        event.cancel();
        silentBreakBlock(event.getPos(), event.getDirection(), USER_PRIORITY);
    }

    @Subscribe(priority = 10)
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || mc.player.isCreative() || mc.player.isSpectator()) return;

        currentServerTick = mc.level.getGameTime();
        diagTick = (long) currentServerTick;
        heldToolThisTick = false;
        lastActivePos = active != null ? active.pos : null;

        if (deferredFinishPos != null && (long) currentServerTick >= deferredFinishTick) {
            BlockPos finished = deferredFinishPos;
            deferredFinishPos = null;
            deferredFinishTick = Long.MAX_VALUE;
            fireFinish(finished);
        }

        if (heldFinishTicks > 0) heldFinishTicks--;
        if (lastFinishedPos != null && mc.level.getBlockState(lastFinishedPos).isAir()) {
            sawFinishedPosAir = true;
        }

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        usingMainhandThisTick = (offhand != null && offhand.shouldDeferForEat())
                || (mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == InteractionHand.MAIN_HAND);

        tickActive();
        if (active == null) tryStartPending();
        releaseVisibleToolWhenSafe();
    }

    private void tickActive() {
        if (active == null) return;

        BlockState state = mc.level.getBlockState(active.pos);
        if (!canBreak(active.pos) || !inBreakRange(active.pos)) {
            cancelActive("invalid-or-range");
            return;
        }
        if ((long) currentServerTick - active.startTick > mineTimeoutTicks.getValue()) {
            cancelActive("timeout");
            return;
        }
        if (!holdFastestTool(active.pos, state)) return;

        active.advance(state);
        if (!active.ready()) return;
        if (heldFinishTicks > 0 && active.pos.equals(heldFinishPos)) return;

        Direction face = visibleFace(active.pos, active.startFace);
        if (face == null) {
            cancelActive("face-not-visible");
            return;
        }

        if (active.finishFace != face) {
            active.finishFace = face;
            active.finishAfterTick = (long) currentServerTick + 1;
            primeRotation(active.pos, face);
            return;
        }
        if ((long) currentServerTick < active.finishAfterTick
                || !Homovore.rotationManager.isCompleted(ROTATION_ID)) return;

        MiningAction finished = active;
        swingAndSend(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                finished.pos, face);
        active = null;
        finishAction(finished.pos, "stop", finished.progress);
    }

    private void tryStartPending() {
        if (pending == null) return;
        if ((long) currentServerTick < nextStartTick || System.nanoTime() < nextStartNanos) return;

        PendingMine request = pending;
        if (request.pos.equals(lastFinishedPos) && !sawFinishedPosAir) {
            pending = null;
            Homovore.rotationManager.cancel(ROTATION_ID);
            return;
        }
        if (!canBreak(request.pos) || !inBreakRange(request.pos)) {
            pending = null;
            Homovore.rotationManager.cancel(ROTATION_ID);
            return;
        }

        Direction face = visibleFace(request.pos, request.preferredFace);
        if (face == null) return;

        if (request.primedFace != face) {
            request.primedFace = face;
            request.actionAfterTick = (long) currentServerTick + 1;
            primeRotation(request.pos, face);
            return;
        }
        if ((long) currentServerTick < request.actionAfterTick
                || !Homovore.rotationManager.isCompleted(ROTATION_ID)) return;

        BlockState state = mc.level.getBlockState(request.pos);
        if (!holdFastestTool(request.pos, state)) return;

        pending = null;
        swingAndSend(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                request.pos, face);

        float firstDelta = heldToolDelta(request.pos, state);
        if (firstDelta >= 1.0f) {
            // Vanilla instant mining completes on START. Sending STOP in the
            // same tick is an invalid packet burst on Grim.
            finishAction(request.pos, "instant-start", 1.0);
            return;
        }

        active = new MiningAction(request.pos, face, request.priority,
                (long) currentServerTick);
        if (debugLog.getValue()) {
            Homovore.LOGGER.info("[SpeedMine] start pos={} face={} delta={}",
                    request.pos, face, String.format("%.4f", firstDelta));
        }
    }

    private void finishAction(BlockPos pos, String path, double progress) {
        deferredFinishPos = pos.immutable();
        deferredFinishTick = (long) currentServerTick + 1;
        lastFinishedPos = pos.immutable();
        sawFinishedPosAir = false;
        nextStartNanos = System.nanoTime() + START_COOLDOWN_NANOS;
        nextStartTick = (long) currentServerTick + 1;
        releaseSwapAtTick = (long) currentServerTick + 1;
        Homovore.rotationManager.cancel(ROTATION_ID);
        if (debugLog.getValue()) {
            Homovore.LOGGER.info("[SpeedMine] finish {} pos={} progress={}",
                    path, pos, String.format("%.3f", progress));
        }
    }

    private void cancelActive(String reason) {
        if (active == null) return;
        BlockPos pos = active.pos;
        sendCancel(pos);
        active = null;
        Homovore.rotationManager.cancel(ROTATION_ID);
        nextStartTick = (long) currentServerTick + 1;
        releaseSwapAtTick = (long) currentServerTick + 1;
        if (debugLog.getValue()) Homovore.LOGGER.info("[SpeedMine] cancel {} pos={}", reason, pos);
    }

    private void primeRotation(BlockPos pos, Direction face) {
        Vec3 hit = Vec3.atCenterOf(pos).relative(face, 0.5);
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(), hit);
        Homovore.rotationManager.submit(new RotationRequest(
                ROTATION_ID, ROTATION_PRIORITY, angles[0], angles[1],
                RotationRequest.Mode.MOTION, true, true));
    }

    private void swingAndSend(ServerboundPlayerActionPacket.Action action,
                              BlockPos pos, Direction face) {
        mc.player.swing(InteractionHand.MAIN_HAND);
        sendPredictedAction(action, pos, face);
    }

    private void sendPredictedAction(ServerboundPlayerActionPacket.Action action,
                                     BlockPos pos, Direction face) {
        if (mc.level == null || mc.getConnection() == null) return;
        try (var prediction = ((ClientLevelAccessor) mc.level)
                .homovore$getBlockStatePredictionHandler().startPredicting()) {
            mc.getConnection().send(new ServerboundPlayerActionPacket(
                    action, pos, face, prediction.currentSequence()));
        }
    }

    private void sendCancel(BlockPos pos) {
        if (mc.getConnection() == null || pos == null) return;
        mc.getConnection().send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                pos, Direction.DOWN, 0));
    }

    private Direction visibleFace(BlockPos pos, Direction preferred) {
        Vec3 eye = mc.player.getEyePosition();
        AABB box = new AABB(pos);
        if (box.contains(eye)) return preferred != null ? preferred : Direction.DOWN;

        Direction direct = rayFace(pos, Vec3.atCenterOf(pos));
        if (direct != null && (preferred == null || direct == preferred)) return direct;

        if (preferred != null) {
            Direction preferredHit = rayFace(pos,
                    Vec3.atCenterOf(pos).relative(preferred, 0.499));
            if (preferredHit == preferred) return preferred;
        }

        for (Direction face : Direction.values()) {
            Direction hit = rayFace(pos, Vec3.atCenterOf(pos).relative(face, 0.499));
            if (hit == face) return face;
        }
        return direct;
    }

    private Direction rayFace(BlockPos pos, Vec3 target) {
        BlockHitResult hit = mc.level.clip(new ClipContext(
                mc.player.getEyePosition(), target,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                ? hit.getDirection() : null;
    }

    public boolean inBreakRange(BlockPos pos) {
        return mc.player != null && pos != null
                && mc.player.isWithinBlockInteractionRange(pos, 0.0);
    }

    private boolean canBreak(BlockPos pos) {
        if (mc.level == null || pos == null) return false;
        BlockState state = mc.level.getBlockState(pos);
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock)
                && state.getDestroySpeed(mc.level, pos) >= 0.0f;
    }

    private AutoMineModule silentAutoMine() {
        AutoMineModule autoMine = Homovore.moduleManager.getModuleByClass(AutoMineModule.class);
        return autoMine != null && autoMine.isEnabled()
                && (autoMine.shouldUseSilentTool() || autoMine.isSilentSwapping())
                ? autoMine : null;
    }

    private boolean holdFastestTool(BlockPos pos, BlockState state) {
        AutoMineModule autoMine = silentAutoMine();
        if (autoMine != null) {
            boolean held = autoMine.ensureSilentTool(state);
            heldToolThisTick |= held;
            return held;
        }

        Result tool = fastestTool(pos, state);
        if (tool.holding()) {
            heldToolThisTick = true;
            return true;
        }
        if (usingMainhandThisTick || !ensureMineSwap()) return false;
        if (InventoryUtil.selected() != tool.slot()) InventoryUtil.swap(tool.slot());
        heldToolThisTick = true;
        return true;
    }

    private boolean ensureMineSwap() {
        if (mineSwapHandle != null && !Homovore.swapManager.holds(mineSwapHandle)) {
            mineSwapHandle = null;
        }
        if (mineSwapHandle == null) {
            mineSwapHandle = Homovore.swapManager.acquireLease("SpeedMine", MINE_SWAP_PRIORITY);
        }
        return Homovore.swapManager.holdsActive(mineSwapHandle);
    }

    public void releaseVisibleSwapForSilentAutoMine() {
        if (mineSwapHandle == null) return;
        if (Homovore.swapManager.holdsActive(mineSwapHandle)
                && InventoryUtil.selected() != mineSwapHandle.originalSlot) {
            InventoryUtil.swap(mineSwapHandle.originalSlot);
        }
        Homovore.swapManager.release(mineSwapHandle);
        mineSwapHandle = null;
        heldToolThisTick = false;
    }

    private void releaseVisibleToolWhenSafe() {
        if (active != null || heldToolThisTick || mineSwapHandle == null) return;
        if ((long) currentServerTick < releaseSwapAtTick) return;

        if (Homovore.swapManager.holdsActive(mineSwapHandle)
                && InventoryUtil.selected() != mineSwapHandle.originalSlot) {
            InventoryUtil.swap(mineSwapHandle.originalSlot);
        }
        Homovore.swapManager.release(mineSwapHandle);
        mineSwapHandle = null;
        releaseSwapAtTick = Long.MAX_VALUE;
    }

    private Result fastestTool(BlockPos pos, BlockState state) {
        int selected = InventoryUtil.selected();
        int bestSlot = selected;
        float bestDelta = calcDelta(mc.player.getInventory().getItem(selected), pos, state,
                serverKnownOnGround());
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            float delta = calcDelta(stack, pos, state, serverKnownOnGround());
            if (delta > bestDelta) {
                bestDelta = delta;
                bestSlot = slot;
            }
        }
        return new Result(bestSlot, mc.player.getInventory().getItem(bestSlot), ResultType.HOTBAR);
    }

    private float heldToolDelta(BlockPos pos, BlockState state) {
        int slot = Homovore.swapManager.serverSlot();
        if (slot < 0 || slot > 8) slot = InventoryUtil.selected();
        ItemStack held = mc.player.getInventory().getItem(slot);
        return calcDelta(held, pos, state, serverKnownOnGround());
    }

    private float calcDelta(ItemStack item, BlockPos pos, BlockState state, boolean onGround) {
        float speed = item.getDestroySpeed(state);
        if (speed > 1.0f) {
            int efficiency = EnchantmentUtil.getLevel(Enchantments.EFFICIENCY, item);
            if (efficiency > 0) speed += efficiency * efficiency + 1;
        }
        if (MobEffectUtil.hasDigSpeed(mc.player)) {
            speed *= 1.0f + (MobEffectUtil.getDigSpeedAmplification(mc.player) + 1) * 0.2f;
        }
        if (mc.player.hasEffect(MobEffects.MINING_FATIGUE)) {
            int amplifier = mc.player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier();
            speed *= switch (amplifier) {
                case 0 -> 0.3f;
                case 1 -> 0.09f;
                case 2 -> 0.0027f;
                default -> 8.1e-4f;
            };
        }
        if (mc.player.isEyeInFluid(FluidTags.WATER)
                && !EnchantmentUtil.has(Enchantments.AQUA_AFFINITY, EquipmentSlot.HEAD)) {
            speed /= 5.0f;
        }
        if (!onGround) speed /= 5.0f;

        float hardness = state.getDestroySpeed(mc.level, pos);
        if (hardness <= 0.0f) return hardness == 0.0f ? 1.0f : 0.0f;
        boolean correct = !state.requiresCorrectToolForDrops() || item.isCorrectToolForDrops(state);
        return speed / hardness / (correct ? 30.0f : 100.0f);
    }

    private boolean serverKnownOnGround() {
        return ((EntityRotationAccessor) mc.player).homovore$getLastOnGround();
    }

    public boolean hasFailingBlock() {
        return active != null
                && (long) currentServerTick - active.startTick > mineTimeoutTicks.getValue();
    }

    public boolean hasDelayedDestroy() {
        return false;
    }

    public boolean hasRebreakBlock() {
        return active != null || pending != null;
    }

    public boolean canRebreakRebreakBlock() {
        return false;
    }

    public BlockPos getRebreakBlockPos() {
        if (active != null) return active.pos;
        return pending != null ? pending.pos : null;
    }

    public void holdRebreak(BlockPos pos, int ticks) {
        heldFinishPos = pos != null ? pos.immutable() : null;
        heldFinishTicks = pos != null ? Math.max(0, ticks) : 0;
    }

    public BlockPos getDelayedDestroyBlockPos() {
        return null;
    }

    public BlockPos getLastDelayedDestroyBlockPos() {
        return lastActivePos;
    }

    public void collectMiningPositions(Set<BlockPos> out, double minProgress) {
        if (active != null && active.progress() >= minProgress) out.add(active.pos);
    }

    @Subscribe
    public void onRender3D(Render3DEvent event) {
        if (nullCheck() || !render.getValue() || active == null) return;
        double progress = active.renderProgress(event.getDelta());
        double centerX = active.pos.getX() + 0.5;
        double centerY = active.pos.getY() + 0.5;
        double centerZ = active.pos.getZ() + 0.5;
        double half = 0.5 * Mth.clamp(progress, 0.0, 1.0);
        AABB box = new AABB(centerX - half, centerY - half, centerZ - half,
                centerX + half, centerY + half, centerZ + half);
        RenderUtil.drawBoxFilled(event.getMatrix(), box, sideColor.getValue());
        RenderUtil.drawBox(event.getMatrix(), box, lineColor.getValue(), lineWidth.getValue());
    }

    private final class MiningAction {
        private final BlockPos pos;
        private final Direction startFace;
        @SuppressWarnings("unused")
        private final double priority;
        private final long startTick;
        private long lastProgressTick;
        private long readySinceTick = -1L;
        private Direction finishFace;
        private long finishAfterTick = Long.MAX_VALUE;
        private double progress;

        private MiningAction(BlockPos pos, Direction startFace, double priority, long startTick) {
            this.pos = pos.immutable();
            this.startFace = startFace;
            this.priority = priority;
            this.startTick = startTick;
            this.lastProgressTick = startTick;
        }

        private void advance(BlockState state) {
            long tick = (long) currentServerTick;
            long elapsed = Math.max(0L, tick - lastProgressTick);
            if (elapsed > 0L) {
                progress = Math.min(1.0,
                        progress + heldToolDelta(pos, state) * elapsed);
                lastProgressTick = tick;
            }
            if (progress >= 1.0 && readySinceTick < 0L) readySinceTick = tick;
        }

        private boolean ready() {
            return readySinceTick >= 0L && (long) currentServerTick > readySinceTick;
        }

        private double progress() {
            return progress;
        }

        private double renderProgress(float partialTick) {
            BlockState state = mc.level.getBlockState(pos);
            return Math.min(1.0, progress + heldToolDelta(pos, state) * partialTick);
        }
    }

    private record Diag(long tick, long nanos, String description) {}
    private static final int DIAG_CAPACITY = 48;
    private final ArrayDeque<Diag> diagnostics = new ArrayDeque<>();
    private volatile long diagTick;

    @Subscribe
    private void onDiagSend(PacketEvent.Send event) {
        if (!debugLog.getValue()) return;
        String description;
        if (event.getPacket() instanceof ServerboundSetCarriedItemPacket packet) {
            description = "SLOT-> " + packet.getSlot();
        } else if (event.getPacket() instanceof ServerboundPlayerActionPacket packet) {
            description = packet.getAction() + " " + packet.getPos().toShortString()
                    + " face=" + packet.getDirection() + " seq=" + packet.getSequence();
        } else if (event.getPacket() instanceof ServerboundMovePlayerPacket packet) {
            description = "MOVE " + packet.getClass().getSimpleName()
                    + " onGround=" + packet.isOnGround();
        } else {
            return;
        }
        synchronized (diagnostics) {
            diagnostics.addLast(new Diag(diagTick, System.nanoTime(), description));
            while (diagnostics.size() > DIAG_CAPACITY) diagnostics.removeFirst();
        }
    }

    @Subscribe
    private void onDiagReceive(PacketEvent.Receive event) {
        if (!debugLog.getValue()
                || !(event.getPacket() instanceof ClientboundPlayerPositionPacket packet)) return;
        long now = System.nanoTime();
        StringBuilder log = new StringBuilder("[SpeedMine][SETBACK] server teleport id=")
                .append(packet.id()).append(" packets:");
        synchronized (diagnostics) {
            for (Diag entry : diagnostics) {
                log.append(String.format("%n  t%d -%.1fms %s", entry.tick(),
                        (now - entry.nanos()) / 1e6, entry.description()));
            }
        }
        Homovore.LOGGER.info(log.toString());
    }
}
