package dev.bekhilitt.client.render;

import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.monster.Vindicator;
import org.joml.Vector4f;
import org.slf4j.Logger;
import yesman.epicfight.api.client.event.EpicFightClientEventHooks;
import yesman.epicfight.api.client.event.types.render.PrepareModelEvent;
import yesman.epicfight.api.client.model.Meshes;
import yesman.epicfight.api.client.model.SkinnedMesh;
import yesman.epicfight.api.utils.math.Vec2i;

/**
 * Opt-in client presentation for the episode's commander. The real entity,
 * VindicatorPatch, armature, attacks, collision and native held-item layer stay
 * untouched. The mesh and texture are original Bekhilitt assets.
 *
 * Register once with the client's active-episode supplier. Disable with
 * -Dbekhilitt.disableGriffithBody=true to restore Epic Fight's ordinary body.
 * No registration or common/server class is changed by adding this file.
 */
public final class GriffithBodyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        "bekhilitt", "textures/entity/griffith.png");
    private static final Meshes.MeshAccessor<SkinnedMesh> BODY = Meshes.MeshAccessor.create(
        "bekhilitt", "entity/griffith", loader -> loader.loadSkinnedMesh(SkinnedMesh::new));
    private static BooleanSupplier activeEpisode = () -> false;
    private static boolean registered;
    private static boolean failed;
    private static boolean textureChecked;

    private GriffithBodyRenderer() {}

    public static void register(BooleanSupplier active) {
        Objects.requireNonNull(active, "activeEpisode");
        if (registered || Boolean.getBoolean("bekhilitt.disableGriffithBody")) return;
        registered = true;
        activeEpisode = active;
        EpicFightClientEventHooks.Render.PREPARE_MODEL_TO_RENDER.registerEvent(
            GriffithBodyRenderer::renderBody, "bekhilitt:griffith_body");
    }

    /** Name keys are network-synchronized; vanilla scoreboard tags are not. */
    public static boolean isEpisodeCommander(net.minecraft.world.entity.Entity entity) {
        return !failed && entity instanceof Vindicator
            && entity.getCustomName() != null
            && entity.getCustomName().getContents() instanceof TranslatableContents name
            && "character.bekhilitt.griffith".equals(name.getKey())
            && isActiveEpisode();
    }

    private static boolean isActiveEpisode() {
        try {
            return activeEpisode.getAsBoolean();
        } catch (RuntimeException error) {
            failed = true;
            LOGGER.error("Bekhilitt episode state unavailable; keeping native commander rendering for this session", error);
            return false;
        }
    }

    private static void renderBody(PrepareModelEvent event) {
        if (failed || event.isCanceled()) return;
        var patch = event.getEntityPatch();
        var actor = patch.getOriginal();
        if (!isEpisodeCommander(actor)) return;

        try {
            Minecraft client = Minecraft.getInstance();
            if (!textureChecked) {
                if (client.getResourceManager().getResource(TEXTURE).isEmpty()) {
                    throw new IllegalStateException("Missing original commander texture: " + TEXTURE);
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
            failed = true;
            LOGGER.error("Bekhilitt commander body unavailable; keeping native Epic Fight rendering for this session", error);
        }
    }
}
