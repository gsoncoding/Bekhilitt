package dev.bekhilitt.client.mixin;

import dev.bekhilitt.client.CombatContext;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cancel vanilla effects, not mouse events: camera and UI keep their normal input. */
@Mixin(Minecraft.class)
public abstract class MinecraftInputMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void bekhilitt$attack(CallbackInfoReturnable<Boolean> ci) {
        if (CombatContext.INPUT.blockVanillaAttack()) ci.setReturnValue(false);
    }
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void bekhilitt$mine(boolean held, CallbackInfo ci) {
        if (CombatContext.INPUT.blockVanillaAttack()) ci.cancel();
    }
    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void bekhilitt$use(CallbackInfo ci) {
        if (CombatContext.INPUT.blockVanillaUse()) ci.cancel();
    }
}
