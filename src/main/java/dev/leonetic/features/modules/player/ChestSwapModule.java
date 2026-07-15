package dev.leonetic.features.modules.player;

import dev.leonetic.event.impl.entity.player.PreTickEvent;
import dev.leonetic.event.system.Subscribe;
import dev.leonetic.features.modules.Module;
import dev.leonetic.features.settings.Setting;
import dev.leonetic.util.inventory.InventoryUtil;
import dev.leonetic.util.inventory.Result;
import dev.leonetic.util.inventory.ResultType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;

import static dev.leonetic.util.inventory.InventoryUtil.FULL_SCOPE;

public final class ChestSwapModule extends Module {
    private static final int CHEST_MENU_SLOT = 6;
    private static final int OFFHAND_MENU_SLOT = 45;

    private final Setting<Integer> delay = num("Delay", 2, 1, 20);

    private int timer;

    public ChestSwapModule() {
        super("ChestSwap", "Swaps between an elytra in flight and a chestplate on the ground.", Category.PLAYER);
    }

    @Override
    public void onEnable() {
        timer = 0;
    }

    @Subscribe
    private void onPreTick(PreTickEvent event) {
        if (nullCheck() || !canClickPlayerInventory()) return;

        if (++timer < delay.getValue()) return;
        timer = 0;

        ItemStack equipped = mc.player.getItemBySlot(EquipmentSlot.CHEST);
        if (mc.player.isFallFlying() && !equipped.is(Items.ELYTRA)) {
            Result elytra = InventoryUtil.find(Items.ELYTRA, FULL_SCOPE);
            if (elytra.found()) swapChestArmorWith(containerSlotOf(elytra));
            return;
        }

        if (mc.player.onGround() && equipped.is(Items.ELYTRA)) {
            Result chestplate = InventoryUtil.find(ChestSwapModule::isChestplate, FULL_SCOPE);
            if (chestplate.found()) swapChestArmorWith(containerSlotOf(chestplate));
        }
    }

    private boolean canClickPlayerInventory() {
        return mc.gameMode != null && mc.player.containerMenu == mc.player.inventoryMenu;
    }

    private void swapChestArmorWith(int itemContainerSlot) {
        InventoryUtil.click(itemContainerSlot, 0, ClickType.PICKUP);
        InventoryUtil.click(CHEST_MENU_SLOT, 0, ClickType.PICKUP);
        InventoryUtil.click(itemContainerSlot, 0, ClickType.PICKUP);
    }

    private static int containerSlotOf(Result result) {
        if (result.type() == ResultType.OFFHAND) return OFFHAND_MENU_SLOT;
        return result.slot() < 9 ? result.slot() + 36 : result.slot();
    }

    private static boolean isChestplate(ItemStack stack) {
        if (stack.is(Items.ELYTRA)) return false;
        Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot() == EquipmentSlot.CHEST;
    }
}
