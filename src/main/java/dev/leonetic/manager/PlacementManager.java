package dev.leonetic.manager;

import dev.leonetic.Homovore;
import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.impl.network.PacketEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.Feature;
import dev.leonetic.features.modules.combat.OffhandModule;
import dev.leonetic.mixin.client.ClientLevelAccessor;
import dev.leonetic.util.MathUtil;
import dev.leonetic.util.PlaceUtil;
import dev.leonetic.util.inventory.InventoryUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PlacementManager extends Feature {

    private static final String ROTATION_ID = "PlacementManager.place";
    private static final int ROTATION_PRIORITY = 90;

    private static final int  WINDOW_LIMIT = 9;
    private static final long WINDOW_MS    = 300;

    private static final long PER_BLOCK_COOLDOWN_MS = 30;

    private final ArrayDeque<Long> recentPlacements = new ArrayDeque<>();

    private final Map<BlockPos, Long> sentAt = new ConcurrentHashMap<>();

    private final Queue<PlacementTask> queue    = new ArrayDeque<>();
    private final Set<BlockPos>        queued   = new HashSet<>();

    @Nullable
    private PendingPlacement pendingPlacement;

    @Nullable
    private DeferredRestore deferredRestore;

    private final Map<BlockPos, Long> directCompletions = new ConcurrentHashMap<>();

    private boolean placing = false;

    private boolean wasUsingItem = false;

    private int lastSwapSequenceTick = -1;

    private int lastNormalPlacementTick = -1;

    private boolean swapSequenceAvailable() {
        return mc.player == null || mc.player.tickCount != lastSwapSequenceTick;
    }

    private static int containerSlotOf(int slot) {
        return slot < 9 ? slot + 36 : slot;
    }

    private static boolean isInventorySlot(int slot) {
        return slot > 8;
    }

    private boolean canAltSwap() {
        return mc.player != null && mc.player.containerMenu.containerId == 0;
    }

    private int altSwapIn(int slot) {
        if (!isInventorySlot(slot)) return slot;
        if (!canAltSwap()) return -1;
        int selected = InventoryUtil.selected();
        InventoryUtil.click(slot, selected, ClickType.SWAP);
        return selected;
    }

    private void altSwapOut(int slot, int hotbarSlot) {
        if (!isInventorySlot(slot)) return;
        InventoryUtil.click(slot, hotbarSlot, ClickType.SWAP);
    }

    private void markSwapSequenceUsed() {
        if (mc.player != null) lastSwapSequenceTick = mc.player.tickCount;
    }

    public interface PlacementListener {
        void onBlockUpdate(BlockPos pos, boolean nowAir);
    }

    private final CopyOnWriteArrayList<PlacementListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(PlacementListener listener)    { listeners.addIfAbsent(listener); }
    public void removeListener(PlacementListener listener) { listeners.remove(listener); }

    public PlacementManager() {
        EVENT_BUS.register(this);
    }

    public boolean enqueue(BlockPos pos, int hotbarSlot) {
        return enqueueTask(pos, null, hotbarSlot, false);
    }

    public boolean enqueue(BlockPos pos, Direction face, int hotbarSlot) {
        return enqueueTask(pos, face, hotbarSlot, false);
    }

    private boolean enqueueTask(BlockPos pos, @Nullable Direction face, int hotbarSlot, boolean direct) {
        if (pos == null || hotbarSlot < 0 || hotbarSlot > 35) return false;
        if (isOnCooldown(pos)) return false;
        BlockPos immutable = pos.immutable();
        if (!queued.add(immutable)) return false;
        boolean accepted = queue.offer(new PlacementTask(immutable, face, hotbarSlot, direct));
        if (!accepted) queued.remove(immutable);
        return accepted;
    }

    private boolean isOnCooldown(BlockPos pos) {
        Long last = sentAt.get(pos);
        if (last == null) return false;
        if (System.currentTimeMillis() - last >= PER_BLOCK_COOLDOWN_MS) {
            sentAt.remove(pos, last);
            return false;
        }
        return true;
    }

    public void clearQueue() {
        queue.clear();
        queued.clear();
        directCompletions.clear();
        cancelPendingPlacement();
    }

    public void removeQueuedFor(java.util.function.Predicate<BlockPos> filter) {
        queue.removeIf(task -> {
            if (filter.test(task.pos())) {
                queued.remove(task.pos());
                return true;
            }
            return false;
        });
        if (pendingPlacement != null && filter.test(pendingPlacement.task.pos())) {
            queued.remove(pendingPlacement.task.pos());
            cancelPendingPlacement();
        }
    }

    public void forceResetPlaceCooldown(BlockPos pos) {
        sentAt.remove(pos);
    }

    public boolean hasPending() {
        return !queue.isEmpty() || pendingPlacement != null || deferredRestore != null;
    }

    public boolean isPlacing() {
        return placing;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck()) {
            wasUsingItem = false;
            queue.clear();
            queued.clear();
            directCompletions.clear();
            if (Homovore.rotationManager != null) Homovore.rotationManager.cancel(ROTATION_ID);
            pendingPlacement = null;
            deferredRestore = null;
            lastNormalPlacementTick = -1;
            lastSwapSequenceTick = -1;
            return;
        }

        if (deferredRestore != null) {
            if (restoreDeferred(deferredRestore)) deferredRestore = null;
            wasUsingItem = mc.player.isUsingItem();
            return;
        }

        driveQueue();
        wasUsingItem = mc.player.isUsingItem();
    }

    private boolean usingItemAnyTick() {
        return mc.player.isUsingItem() || wasUsingItem;
    }

    public void flushQueue() {
        // Compatibility hook only. The queue is intentionally driven from
        // PreTickEvent so no ordinary placement can run after sendPosition.
    }

    public List<BlockPos> placeBatch(List<BlockPos> positions, int hotbarSlot) {
        if (nullCheck() || positions.isEmpty()) return List.of();
        java.util.ArrayList<BlockPos> accepted = new java.util.ArrayList<>();
        for (BlockPos pos : positions) {
            if (enqueue(pos, hotbarSlot)) accepted.add(pos.immutable());
        }
        return accepted;
    }

    private void driveQueue() {
        prunePlacementWindow();
        long now = System.currentTimeMillis();
        directCompletions.entrySet().removeIf(e -> now - e.getValue() > 1_000L);

        if (lastNormalPlacementTick == mc.player.tickCount) return;
        if (usingItemAnyTick()) return;
        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return;
        if (!swapSequenceAvailable() || Homovore.swapManager.isHotbarBusy()) return;
        if (recentPlacements.size() >= WINDOW_LIMIT) return;

        if (pendingPlacement == null) {
            while (!queue.isEmpty()) {
                PlacementTask task = queue.peek();
                if (isInventorySlot(task.hotbarSlot()) && inventorySwapUnsafe()) return;

                PreparedClick prepared = prepareClick(task);
                if (prepared == null) {
                    queue.poll();
                    queued.remove(task.pos());
                    continue;
                }

                queue.poll();
                pendingPlacement = new PendingPlacement(task, prepared);
                submitPlacementRotation(pendingPlacement);
                return;
            }
            return;
        }

        PendingPlacement pending = pendingPlacement;
        if (isInventorySlot(pending.task.hotbarSlot()) && inventorySwapUnsafe()) {
            Homovore.rotationManager.cancel(ROTATION_ID);
            pending.rotationTick = -1;
            return;
        }

        PreparedClick refreshed = prepareClick(pending.task);
        if (refreshed == null) {
            queued.remove(pending.task.pos());
            cancelPendingPlacement();
            return;
        }
        if (!sameClick(pending.click, refreshed)) {
            pending.click = refreshed;
            submitPlacementRotation(pending);
            return;
        }

        if (pending.rotationTick < 0) {
            submitPlacementRotation(pending);
            return;
        }
        if (mc.player.tickCount <= pending.rotationTick) return;

        if (!serverRayHits(pending.click)) {
            submitPlacementRotation(pending);
            return;
        }
        if (!sendNormalPlacement(pending)) return;

        long stamp = System.currentTimeMillis();
        sentAt.put(pending.click.pos(), stamp);
        recentPlacements.addLast(stamp);
        queued.remove(pending.task.pos());
        if (pending.task.direct()) directCompletions.put(pending.task.pos(), stamp);
        lastNormalPlacementTick = mc.player.tickCount;
        markSwapSequenceUsed();
        Homovore.rotationManager.cancel(ROTATION_ID);
        pendingPlacement = null;
    }

    private void submitPlacementRotation(PendingPlacement pending) {
        float[] angles = MathUtil.calcAngle(mc.player.getEyePosition(1.0f), pending.click.hitPos());
        Homovore.rotationManager.submit(new RotationRequest(
                ROTATION_ID, ROTATION_PRIORITY, angles[0], angles[1],
                RotationRequest.Mode.MOTION, true, true));
        pending.rotationTick = mc.player.tickCount;
    }

    private void prunePlacementWindow() {
        long now = System.currentTimeMillis();
        while (!recentPlacements.isEmpty() && now - recentPlacements.peekFirst() >= WINDOW_MS) {
            recentPlacements.pollFirst();
        }
    }

    @Nullable
    public BlockHitResult prepareAirPlaceHit(BlockPos pos) {
        if (isOnCooldown(pos)) return null;
        if (!PlaceUtil.canPlace(pos)) return null;
        PreparedClick click = findSupportClick(pos, null, -1);
        if (click == null) return null;
        return new BlockHitResult(click.hitPos(), click.hitSide(), click.neighbour(), false);
    }

    public void notePlacement(BlockPos pos) {
        long stamp = System.currentTimeMillis();
        sentAt.put(pos, stamp);
        recentPlacements.addLast(stamp);
    }

    @Nullable
    public Direction getPlaceSide(BlockPos pos) {
        PreparedClick click = findSupportClick(pos, null, -1);
        if (click == null) return null;
        return click.hitSide().getOpposite();
    }

    private boolean isInteractable(net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
        var block = state.getBlock();
        return block instanceof BaseEntityBlock
            || block instanceof DoorBlock
            || block instanceof TrapDoorBlock
            || block instanceof FenceGateBlock
            || block instanceof ButtonBlock
            || block instanceof LeverBlock
            || block instanceof BedBlock
            || block instanceof NoteBlock
            || block instanceof AnvilBlock
            || state.getMenuProvider(mc.level, pos) != null;
    }

    @Nullable
    private PreparedClick prepareClick(PlacementTask task) {
        BlockPos pos = task.pos();
        if (!PlaceUtil.canPlace(pos)) return null;
        if (task.hotbarSlot() < 0 || task.hotbarSlot() > 35) return null;
        ItemStack stack = mc.player.getInventory().getItem(task.hotbarSlot());
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) return null;
        return findSupportClick(pos, task.face(), task.hotbarSlot());
    }

    @Nullable
    private PreparedClick findSupportClick(BlockPos pos, @Nullable Direction requestedSide, int slot) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double range = mc.player.blockInteractionRange();
        double bestDistSq = Double.MAX_VALUE;
        PreparedClick best = null;

        for (Direction side : Direction.values()) {
            if (requestedSide != null && side != requestedSide) continue;
            BlockPos support = pos.relative(side);
            var state = mc.level.getBlockState(support);
            if (state.isAir() || state.canBeReplaced()) continue;
            if (!state.getFluidState().isEmpty()) continue;
            if (isInteractable(state, support)) continue;

            Direction hitSide = side.getOpposite();
            Vec3 hitPos = Vec3.atCenterOf(pos).relative(side, 0.5);
            hitPos = finiteLocalHit(support, hitPos);
            if (hitPos == null) continue;

            Vec3 normal = new Vec3(hitSide.getStepX(), hitSide.getStepY(), hitSide.getStepZ());
            if (eye.subtract(hitPos).dot(normal) <= 1.0e-4) continue;
            double distSq = eye.distanceToSqr(hitPos);
            if (distSq > range * range + 1.0e-6) continue;
            if (!directRaySeesSupport(eye, hitPos, hitSide, support)) continue;

            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = new PreparedClick(pos, support, hitPos, hitSide, slot);
            }
        }
        return best;
    }

    @Nullable
    private Vec3 finiteLocalHit(BlockPos support, Vec3 hit) {
        double x = hit.x - support.getX();
        double y = hit.y - support.getY();
        double z = hit.z - support.getZ();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        if (x < -1.0e-6 || x > 1.000001 || y < -1.0e-6 || y > 1.000001
                || z < -1.0e-6 || z > 1.000001) return null;
        x = Math.max(0.0, Math.min(1.0, x));
        y = Math.max(0.0, Math.min(1.0, y));
        z = Math.max(0.0, Math.min(1.0, z));
        return new Vec3(support.getX() + x, support.getY() + y, support.getZ() + z);
    }

    private boolean directRaySeesSupport(Vec3 eye, Vec3 hit, Direction face, BlockPos support) {
        Vec3 inward = hit.subtract(face.getStepX() * 1.0e-3,
                face.getStepY() * 1.0e-3, face.getStepZ() * 1.0e-3);
        BlockHitResult result = mc.level.clip(new ClipContext(
                eye, inward, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return result.getType() == HitResult.Type.BLOCK
                && result.getBlockPos().equals(support)
                && result.getDirection() == face;
    }

    private boolean serverRayHits(PreparedClick click) {
        Vec3 eye = mc.player.getEyePosition(1.0f);
        double range = mc.player.blockInteractionRange();
        if (eye.distanceToSqr(click.hitPos()) > range * range + 1.0e-6) return false;
        Vec3 look = getLookVector(Homovore.rotationManager.getServerYaw(),
                Homovore.rotationManager.getServerPitch());
        Vec3 end = eye.add(look.scale(range));
        BlockHitResult result = mc.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        return result.getType() == HitResult.Type.BLOCK
                && result.getBlockPos().equals(click.neighbour())
                && result.getDirection() == click.hitSide();
    }

    private static boolean sameClick(PreparedClick a, PreparedClick b) {
        return a.neighbour().equals(b.neighbour())
                && a.hitSide() == b.hitSide()
                && a.hitPos().distanceToSqr(b.hitPos()) < 1.0e-10;
    }

    private record PreparedClick(BlockPos pos, BlockPos neighbour, Vec3 hitPos,
                                 Direction hitSide, int hotbarSlot) {}

    private boolean sendNormalPlacement(PendingPlacement pending) {
        if (Homovore.swapManager.isHotbarBusy()) return false;
        PlacementTask task = pending.task;
        boolean inventory = isInventorySlot(task.hotbarSlot());
        if (inventory && inventorySwapUnsafe()) return false;
        if (inventory && (!canAltSwap() || !InventoryUtil.cursor().isEmpty())) return false;

        int useSlot = task.hotbarSlot();
        if (inventory) {
            useSlot = altSwapIn(task.hotbarSlot());
            if (useSlot < 0) return false;
            if (!(mc.player.getInventory().getItem(useSlot).getItem() instanceof BlockItem)) {
                altSwapOut(task.hotbarSlot(), useSlot);
                return false;
            }
        }

        int originalServerSlot = Homovore.swapManager.serverSlot();
        if (originalServerSlot < 0 || originalServerSlot > 8) {
            originalServerSlot = InventoryUtil.selected();
        }
        boolean changedServerSlot = useSlot != originalServerSlot;

        placing = true;
        try {
            var conn = mc.getConnection();
            if (conn == null) {
                if (inventory) altSwapOut(task.hotbarSlot(), useSlot);
                return false;
            }
            if (changedServerSlot) conn.send(new ServerboundSetCarriedItemPacket(useSlot));
            try (var handler = ((ClientLevelAccessor) mc.level)
                    .homovore$getBlockStatePredictionHandler().startPredicting()) {
                conn.send(new ServerboundUseItemOnPacket(
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(pending.click.hitPos(), pending.click.hitSide(),
                                pending.click.neighbour(), false),
                        handler.currentSequence()));
            }
        } finally {
            placing = false;
        }

        int restoreServerSlot = changedServerSlot ? originalServerSlot : -1;
        int inventorySlot = inventory ? task.hotbarSlot() : -1;
        if (restoreServerSlot >= 0 || inventorySlot >= 0) {
            deferredRestore = new DeferredRestore(restoreServerSlot, inventorySlot, useSlot);
        }
        return true;
    }

    private boolean restoreDeferred(DeferredRestore restore) {
        if (restore.inventorySlot() >= 0) {
            if (inventorySwapUnsafe() || mc.player.isUsingItem()) return false;
            if (!canAltSwap() || !InventoryUtil.cursor().isEmpty()) return false;
        }

        var conn = mc.getConnection();
        if (conn == null) return false;
        if (restore.serverSlot() >= 0
                && Homovore.swapManager.serverSlot() != restore.serverSlot()) {
            conn.send(new ServerboundSetCarriedItemPacket(restore.serverSlot()));
        }
        if (restore.inventorySlot() >= 0) {
            altSwapOut(restore.inventorySlot(), restore.hotbarSlot());
        }
        return true;
    }

    private boolean inventorySwapUnsafe() {
        if (mc.player.isSprinting()) return true;
        if (mc.player.getDeltaMovement().horizontalDistanceSqr() > 1.0e-4) return true;
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
                || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    private void cancelPendingPlacement() {
        if (pendingPlacement == null) return;
        pendingPlacement = null;
        if (Homovore.rotationManager != null) Homovore.rotationManager.cancel(ROTATION_ID);
    }

    private static final class PendingPlacement {
        private final PlacementTask task;
        private PreparedClick click;
        private int rotationTick = -1;

        private PendingPlacement(PlacementTask task, PreparedClick click) {
            this.task = task;
            this.click = click;
        }
    }

    private record DeferredRestore(int serverSlot, int inventorySlot, int hotbarSlot) {}

    @Subscribe
    private void onPacketReceive(PacketEvent.Receive event) {
        Packet<?> packet = event.getPacket();
        if (packet instanceof ClientboundBlockUpdatePacket bup) {
            handleBlockUpdate(bup);
        } else if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) {
                if (sub instanceof ClientboundBlockUpdatePacket bup) handleBlockUpdate(bup);
            }
        }
    }

    private void handleBlockUpdate(ClientboundBlockUpdatePacket packet) {

        BlockPos pos = packet.getPos().immutable();
        sentAt.remove(pos);

        if (listeners.isEmpty()) return;

        boolean nowAir = packet.getBlockState().isAir();
        mc.execute(() -> {
            for (PlacementListener listener : listeners) {
                listener.onBlockUpdate(pos, nowAir);
            }
            flushQueue();
        });
    }

    public boolean placeDirect(BlockPos pos, @Nullable Direction face, int hotbarSlot) {
        if (nullCheck()) return false;
        Long completion = directCompletions.remove(pos);
        if (completion != null && System.currentTimeMillis() - completion <= 1_000L) return true;
        enqueueTask(pos, face, hotbarSlot, true);
        return false;
    }

    public boolean placeCrystal(BlockPos base, int hotbarSlot) {
        return placeCrystal(base, hotbarSlot, false);
    }

    public boolean placeCrystal(BlockPos base, int hotbarSlot, boolean trustBase) {
        if (hasPending() || !swapSequenceAvailable()) return false;
        boolean altSwap = isInventorySlot(hotbarSlot);
        if (altSwap) {
            if (inventorySwapUnsafe()) return false;
            if (usingItemAnyTick()) return false;
            OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
            if (offhand != null && offhand.shouldDeferForEat()) return false;
        }

        BlockHitResult hit = computeCrystalHit(base, trustBase);
        if (hit == null) return false;

        int useSlot = altSwapIn(hotbarSlot);
        if (useSlot < 0) return false;

        try {
            var conn = mc.getConnection();
            int originalSlot = Homovore.swapManager.serverSlot();
            boolean needSlotSwap = useSlot != originalSlot;

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(useSlot));
            }

            try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
            }

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        } finally {
            altSwapOut(hotbarSlot, useSlot);
        }

        markSwapSequenceUsed();
        return true;
    }

    public boolean placeCrystalOffhand(BlockPos base, int hotbarSlot, boolean trustBase) {
        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return false;

        if (hasPending() || !swapSequenceAvailable()) return false;
        if (isInventorySlot(hotbarSlot) && inventorySwapUnsafe()) return false;

        BlockHitResult hit = computeCrystalHit(base, trustBase);
        if (hit == null) return false;

        int useSlot = altSwapIn(hotbarSlot);
        if (useSlot < 0) return false;

        try {
            var conn = mc.getConnection();
            int originalSlot = Homovore.swapManager.serverSlot();
            boolean needSlotSwap = useSlot != originalSlot;

            if (needSlotSwap) {
                conn.send(new ServerboundSetCarriedItemPacket(useSlot));
            }
            conn.send(new ServerboundPlayerActionPacket(
                    ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            try {
                try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                    conn.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, handler.currentSequence()));
                }
            } finally {
                conn.send(new ServerboundPlayerActionPacket(
                        ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
                if (needSlotSwap) {
                    conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
                }
            }
        } finally {
            altSwapOut(hotbarSlot, useSlot);
        }
        markSwapSequenceUsed();

        return true;
    }

    /**
     * One-tick silent break + crystal place. Holds the pickaxe in the mainhand
     * and the crystal in the offhand simultaneously, runs the block-break burst,
     * places a crystal from the offhand, then restores both hands. Unlike
     * {@link #placeCrystalOffhand}, the crystal reaches the offhand via a
     * container click (not SWAP_ITEM_WITH_OFFHAND), so the mainhand stays free to
     * hold the pickaxe — letting us break a block and place a crystal in the same
     * tick.
     *
     * @param pickaxeSlot hotbar slot (0-8) of the pickaxe to break with
     * @param breakBurst  emits the block-break packets; run while the pickaxe is held
     * @param crystalSlot hotbar slot (0-8) holding the end crystal
     * @param crystalBase obsidian/bedrock the crystal is placed on
     * @param trustBase   skip the obsidian/bedrock check on the base
     */
    public boolean breakThenPlaceCrystalOffhand(int pickaxeSlot, Runnable breakBurst,
                                                int crystalSlot, BlockPos crystalBase, boolean trustBase) {
        if (nullCheck()) return false;
        if (mc.player.containerMenu.containerId != 0) return false;
        if (!InventoryUtil.cursor().isEmpty()) return false;
        if (pickaxeSlot < 0 || pickaxeSlot > 8 || crystalSlot < 0 || crystalSlot > 8) return false;
        if (pickaxeSlot == crystalSlot) return false;
        if (!mc.player.getInventory().getItem(crystalSlot).is(Items.END_CRYSTAL)) return false;
        if (inventorySwapUnsafe()) return false;

        OffhandModule offhand = Homovore.moduleManager.getModuleByClass(OffhandModule.class);
        if (offhand != null && offhand.shouldDeferForEat()) return false;

        // This combo owns the hotbar for the tick; don't fight another active swap.
        if (Homovore.swapManager.isHotbarBusy()) return false;

        if (hasPending() || !swapSequenceAvailable()) return false;

        BlockHitResult hit = computeCrystalHit(crystalBase, trustBase);
        if (hit == null) return false;

        var conn = mc.getConnection();
        if (conn == null) return false;

        int originalSlot = Homovore.swapManager.serverSlot();
        boolean needSlotSwap = pickaxeSlot != originalSlot;

        // 1. Crystal -> offhand via container click (mainhand stays free).
        InventoryUtil.swapToOffhand(crystalSlot);
        // 2. Pickaxe -> mainhand.
        if (needSlotSwap) conn.send(new ServerboundSetCarriedItemPacket(pickaxeSlot));

        try {
            // 3. Break the block with the pickaxe now held in the mainhand.
            if (breakBurst != null) breakBurst.run();

            // 4. Place the crystal from the offhand.
            try (var handler = ((ClientLevelAccessor) mc.level)
                    .homovore$getBlockStatePredictionHandler().startPredicting()) {
                conn.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, hit, handler.currentSequence()));
            }
        } finally {
            // 5. Restore mainhand, then 6. restore offhand (swapToOffhand is its own inverse).
            if (needSlotSwap) conn.send(new ServerboundSetCarriedItemPacket(originalSlot));
            InventoryUtil.swapToOffhand(crystalSlot);
        }

        markSwapSequenceUsed();
        return true;
    }

    @Nullable
    private BlockHitResult computeCrystalHit(BlockPos base, boolean trustBase) {
        if (!trustBase) {
            var baseState = mc.level.getBlockState(base);
            if (!baseState.is(Blocks.OBSIDIAN) && !baseState.is(Blocks.BEDROCK)) return null;
        }

        BlockPos airPos = base.above();
        var airState = mc.level.getBlockState(airPos);
        if (!airState.isAir() && !(airState.is(Blocks.FIRE) && mc.level.dimension().equals(Level.END))) return null;

        Vec3 eye = mc.player.getEyePosition(1.0f);

        AABB checkBox = new AABB(airPos);
        for (Entity e : mc.level.getEntities(null, checkBox)) {
            if (e instanceof ItemEntity) continue;
            if (e instanceof EndCrystal crystal && crystal.tickCount < 5) continue;
            if (e instanceof EndCrystal crystal && crystal.blockPosition().equals(airPos)) continue;
            return null;
        }

        Vec3 baseCenter = Vec3.atCenterOf(base);
        double dx = eye.x - baseCenter.x;
        double dy = eye.y - baseCenter.y;
        double dz = eye.z - baseCenter.z;
        double absX = Math.abs(dx), absY = Math.abs(dy), absZ = Math.abs(dz);
        Direction clickFace;
        if (absY >= absX && absY >= absZ)      clickFace = dy > 0 ? Direction.UP    : Direction.DOWN;
        else if (absX >= absZ)                 clickFace = dx > 0 ? Direction.EAST  : Direction.WEST;
        else                                   clickFace = dz > 0 ? Direction.SOUTH : Direction.NORTH;

        Vec3 playerPos = mc.player.position();
        double offX = Math.max(0.2, Math.min(0.8, playerPos.x - Math.floor(playerPos.x)));
        double offY = Math.max(0.2, Math.min(0.8, playerPos.y - Math.floor(playerPos.y)));
        double offZ = Math.max(0.2, Math.min(0.8, playerPos.z - Math.floor(playerPos.z)));
        Vec3 hitVec = baseCenter;
        switch (clickFace) {
            case UP, DOWN -> hitVec = hitVec.add(
                    offX - 0.5,
                    clickFace == Direction.UP ? 0.5 : -0.5,
                    offZ - 0.5
            );
            case NORTH, SOUTH -> hitVec = hitVec.add(
                    offX - 0.5,
                    offY - 0.5,
                    clickFace == Direction.SOUTH ? 0.5 : -0.5
            );
            case EAST, WEST -> hitVec = hitVec.add(
                    clickFace == Direction.EAST ? 0.5 : -0.5,
                    offY - 0.5,
                    offZ - 0.5
            );
        }

        float[] angles = MathUtil.calcAngle(eye, hitVec);

        AABB baseBox = new AABB(base);
        boolean insideBox = baseBox.contains(eye);
        if (!insideBox) {
            Vec3 look = getLookVector(angles[0], angles[1]);
            Vec3 reachEnd = eye.add(look.scale(6.0));
            if (baseBox.clip(eye, reachEnd).isEmpty()) return null;
        }

        return new BlockHitResult(hitVec, clickFace, base, false);
    }

    public boolean placeFireworksAlt(List<BlockPos> poses, Direction face, int hotbarSlot) {
        if (nullCheck()) return false;
        if (poses.isEmpty()) return false;
        if (mc.player.containerMenu.containerId != 0) return false;
        if (hotbarSlot < 0 || hotbarSlot > 35) return false;
        if (hasPending() || !swapSequenceAvailable()) return false;

        ItemStack stack = mc.player.getInventory().getItem(hotbarSlot);
        if (stack.isEmpty()) return false;

        var conn = mc.getConnection();
        if (conn == null) return false;

        int containerSlot = containerSlotOf(hotbarSlot);
        boolean swapped = hotbarSlot != InventoryUtil.selected();
        if (swapped && inventorySwapUnsafe()) return false;

        if (swapped) InventoryUtil.click(containerSlot, InventoryUtil.selected(), ClickType.SWAP);
        try {
            for (BlockPos pos : poses) {
                BlockPos neighbour = pos.relative(face);
                Vec3 hitPos = getFireworkHitPos(pos, face);
                Direction hitSide = face.getOpposite();
                BlockHitResult hit = new BlockHitResult(hitPos, hitSide, neighbour, false);

                try (var handler = ((ClientLevelAccessor) mc.level).homovore$getBlockStatePredictionHandler().startPredicting()) {
                    conn.send(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, hit, handler.currentSequence()));
                }
            }
        } finally {
            if (swapped) InventoryUtil.click(containerSlot, InventoryUtil.selected(), ClickType.SWAP);
        }
        return true;
    }

    private Vec3 getFireworkHitPos(BlockPos pos, Direction face) {
        if (face != Direction.DOWN || mc.player == null) {
            return Vec3.atCenterOf(pos).relative(face, 0.5);
        }

        // Lean toward the corner facing away from the player, but don't jam it fully
        // into the corner: sitting ~0.15 from the edge can end up placing crystals there.
        // 0.7 of the way through keeps the firework in that corner-ish region while
        // staying clear enough of the edge.
        final double lean = 0.7;
        Vec3 player = mc.player.position();
        double x = pos.getX() + (player.x >= pos.getX() + 0.5 ? 1.0 - lean : lean);
        double z = pos.getZ() + (player.z >= pos.getZ() + 0.5 ? 1.0 - lean : lean);
        return new Vec3(x, pos.getY(), z);
    }

    private Vec3 getLookVector(float yaw, float pitch) {
        float f = (float) Math.cos(-yaw * 0.017453292F - Math.PI);
        float g = (float) Math.sin(-yaw * 0.017453292F - Math.PI);
        float h = -(float) Math.cos(-pitch * 0.017453292F);
        float i = (float) Math.sin(-pitch * 0.017453292F);
        return new Vec3(g * h, i, f * h);
    }

    private record PlacementTask(BlockPos pos, @Nullable Direction face, int hotbarSlot, boolean direct) {}
}
