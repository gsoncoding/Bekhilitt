package dev.bekhilitt.client.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Vector4f;
import org.slf4j.Logger;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.render.PrepareModelEvent;
import yesman.epicfight.api.client.event.types.render.ValidatePlayerModelEvent;
import yesman.epicfight.client.renderer.patched.entity.PPlayerRenderer;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.utils.math.Vec2i;

/**
 * Original early-episode avatar for the local player in third person only.
 * Never changes the account skin, client options, items, player mode or server
 * state. The native Epic Fight pose and held-item/armor layers remain intact.
 * Call register(activeEpisode) once from client initialization. Returning false
 * from the supplier immediately leaves all ordinary player rendering untouched.
 */
public final class GutsBodyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        "bekhilitt", "textures/entity/guts.png");
    private static final Meshes.MeshAccessor<SkinnedMesh> BODY = Meshes.MeshAccessor.create(
        "bekhilitt", "entity/guts", loader -> loader.loadSkinnedMesh(SkinnedMesh::new));
    private static BooleanSupplier activeEpisode = () -> false;
    private static boolean registered;
    private static boolean failed;
    private static boolean textureChecked;

    private GutsBodyRenderer() {}

    public static void register(BooleanSupplier active) {
        Objects.requireNonNull(active, "activeEpisode");
        if (registered || Boolean.getBoolean("bekhilitt.disableGutsBody")) return;
        registered = true;
        activeEpisode = active;
        EpicFightClientEventHooks.Render.VALIDATE_PLAYER_MODEL_TO_RENDER.registerEvent(
            GutsBodyRenderer::validateModel, "bekhilitt:guts_third_person");
        EpicFightClientEventHooks.Render.PREPARE_MODEL_TO_RENDER.registerEvent(
            GutsBodyRenderer::renderBody, "bekhilitt:guts_body");
    }

    private static boolean shouldReplace(Minecraft client, Entity actor) {
        return !failed && client.player != null && actor == client.player
            && !actor.isSpectator() && !client.options.getCameraType().isFirstPerson()
            && activeEpisode.getAsBoolean();
    }

    private static void validateModel(ValidatePlayerModelEvent event) {
        try {
            if (shouldReplace(Minecraft.getInstance(), event.getPlayerPatch().getOriginal())) {
                // Rendering choice for this event, not an options/mode change.
                event.setShouldRender(true);
            }
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private static void fail(RuntimeException error) {
        failed = true;
        LOGGER.error("Bekhilitt episode avatar unavailable; keeping native player rendering for this session", error);
    }

    private static void renderBody(PrepareModelEvent event) {
        if (failed || event.isCanceled() || !(event.getRenderer() instanceof PPlayerRenderer)) return;
        var patch = event.getEntityPatch();
        var actor = patch.getOriginal();

        try {
            Minecraft client = Minecraft.getInstance();
            if (!shouldReplace(client, actor)) return;
            if (!textureChecked) {
                if (client.getResourceManager().getResource(TEXTURE).isEmpty()) {
                    throw new IllegalStateException("Missing original episode avatar texture: " + TEXTURE);
                }
                textureChecked = true;
            }
            float alpha = 1.0F;
            RenderType renderType;
            if (actor.isInvisible()) {
                if (client.player != null && !actor.isInvisibleTo(client.player)) {
                    renderType = RenderType.itemEntityTranslucentCull(TEXTURE);
                    alpha = 0.15F;
                } else if (client.shouldEntityAppearGlowing(actor)) {
                    renderType = RenderType.outline(TEXTURE);
                } else {
                    return;
                }
            } else {
                renderType = RenderType.entityCutoutNoCull(TEXTURE);
            }

            // Fail before cancellation if resources are unavailable. Reloads
            // resolve through the accessor rather than retaining a stale mesh.
            SkinnedMesh mesh = BODY.get();
            mesh.initialize();
            var armature = patch.getArmature();
            var matrices = armature.getPoseMatrices();
            var decorations = patch.getEntityDecorations();
            Vector4f tint = new Vector4f(1.0F, 1.0F, 1.0F, alpha);
            decorations.modifyColor(tint, event.getPartialTicks());
            Vec2i light = new Vec2i(LightTexture.block(event.getPackedLight()),
                LightTexture.sky(event.getPackedLight()));
            decorations.modifyLight(light, event.getPartialTicks());
            Vec2i overlay = new Vec2i(0, OverlayTexture.v(actor.hurtTime > 0 || actor.deathTime > 0));
            decorations.modifyOverlay(overlay, event.getPartialTicks());
            int packedLight = LightTexture.pack(light.x, light.y);

            // Epic Fight has already transformed the stack and posed the
            // armature. Applying either again would misalign the weapon.
            mesh.draw(event.getPoseStack(), event.getBuffer(), renderType, packedLight,
                tint.x(), tint.y(), tint.z(), tint.w(), OverlayTexture.pack(overlay.x, overlay.y),
                armature, matrices);
            decorations.listDecorationOverlays().forEach(decoration -> {
                if (!decoration.shouldRender()) return;
                Vector4f color = decoration.color(event.getPartialTicks());
                mesh.draw(event.getPoseStack(), event.getBuffer(), decoration.getRenderType(), packedLight,
                    color.x(), color.y(), color.z(), color.w(), OverlayTexture.NO_OVERLAY, armature, matrices);
            });
            // Only replaces the body; Epic Fight continues its native layers.
            event.cancel();
        } catch (RuntimeException error) {
            fail(error);
        }
    }
}
