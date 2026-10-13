package dev.bekhilitt.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Client-only, opt-in original human presentation for the episode's named
 * ArmorStand. No entity registration, AI, world state or server data changes.
 * Call register(modEventBus, activeEpisode) once from the client entry point.
 */
public final class CascaBodyRenderer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
        "bekhilitt", "textures/entity/casca.png");
    private static Renderer renderer;
    private static BooleanSupplier activeEpisode = () -> false;
    private static boolean registered;
    private static boolean failed;
    private static boolean textureChecked;

    private CascaBodyRenderer() {}

    public static void register(IEventBus modEventBus, BooleanSupplier active) {
        Objects.requireNonNull(modEventBus, "modEventBus");
        Objects.requireNonNull(active, "activeEpisode");
        if (registered || Boolean.getBoolean("bekhilitt.disableCascaBody")) return;
        registered = true;
        activeEpisode = active;
        modEventBus.addListener(CascaBodyRenderer::rebuild);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, CascaBodyRenderer::beforeRender);
    }

    /** Called again when entity renderers are rebuilt during resource reload. */
    private static void rebuild(EntityRenderersEvent.AddLayers event) {
        failed = false;
        textureChecked = false;
        renderer = null;
        try {
            renderer = new Renderer(event.getContext());
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    public static boolean isEpisodeCasca(net.minecraft.world.entity.Entity entity) {
        return !failed && entity instanceof ArmorStand
            && entity.getCustomName() != null
            && entity.getCustomName().getContents() instanceof TranslatableContents name
            && "character.bekhilitt.casca".equals(name.getKey())
            && isActiveEpisode();
    }

    private static boolean isActiveEpisode() {
        try {
            return activeEpisode.getAsBoolean();
        } catch (RuntimeException error) {
            fail(error);
            return false;
        }
    }

    private static void beforeRender(RenderLivingEvent.Pre<?, ?> event) {
        Renderer current = renderer;
        if (current == null || failed || event.isCanceled()
            || event.getRenderer() instanceof Renderer || !isEpisodeCasca(event.getEntity())) return;

        ArmorStand actor = (ArmorStand) event.getEntity();
        try {
            if (!textureChecked) {
                if (Minecraft.getInstance().getResourceManager().getResource(TEXTURE).isEmpty()) {
                    throw new IllegalStateException("Missing original Casca texture: " + TEXTURE);
                }
                textureChecked = true;
            }
            // Isolate the stack: even a failed nested vanilla render cannot
            // leave the real world-render stack with an unbalanced push/pop.
            PoseStack localStack = new PoseStack();
            localStack.mulPose(event.getPoseStack().last().pose());
            float partial = event.getPartialTick();
            current.render(actor, Mth.rotLerp(partial, actor.yRotO, actor.getYRot()), partial,
                localStack, event.getMultiBufferSource(), event.getPackedLight());
            // The nested call posts Pre again; the Renderer guard above keeps
            // it non-recursive. Only suppress the ArmorStand after success.
            event.setCanceled(true);
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private static void fail(RuntimeException error) {
        failed = true;
        LOGGER.error("Bekhilitt Casca body unavailable; keeping native ArmorStand until resource reload", error);
    }

    private static final class Renderer extends LivingEntityRenderer<ArmorStand, HumanoidModel<ArmorStand>> {
        private Renderer(EntityRendererProvider.Context context) {
            super(context, new CascaModel(createBodyLayer().bakeRoot()), 0.0F);
            // Clothing is original model geometry. Do not add the stand's
            // armor layers again, or wooden/duplicate armor would reappear.
        }

        @Override
        public ResourceLocation getTextureLocation(ArmorStand actor) { return TEXTURE; }

        @Override
        protected boolean shouldShowName(ArmorStand actor) {
            return actor.isCustomNameVisible() && super.shouldShowName(actor);
        }

        @Override
        protected void setupRotations(ArmorStand actor, PoseStack stack, float age,
                                      float unusedBodyYaw, float partial, float scale) {
            // ArmorStand entity yaw is synchronized. Its living-body/head yaw
            // fields need not follow a scripted setYRot; never rewrite them.
            float yaw = Mth.rotLerp(partial, actor.yRotO, actor.getYRot());
            stack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        }
    }

    private static final class CascaModel extends HumanoidModel<ArmorStand> {
        private CascaModel(ModelPart root) { super(root); }

        @Override
        public void setupAnim(ArmorStand actor, float limbPosition, float limbSpeed,
                              float age, float unusedHeadYaw, float unusedPitch) {
            // A neutral head follows the already-oriented body. This is a
            // presentation choice, not a mutation of the stand's pose or data.
            super.setupAnim(actor, limbPosition, limbSpeed, age, 0.0F, 0.0F);
        }
    }

    /** Original modest travelling outfit, short dark hair, human proportions. */
    private static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition head = root.addOrReplaceChild("head", CubeListBuilder.create()
            .texOffs(0, 0).addBox(-3.5F, -8, -3.5F, 7, 8, 7), PartPose.ZERO);
        // Short layered hair follows the head. No borrowed model or texture.
        head.addOrReplaceChild("hair", CubeListBuilder.create()
            .texOffs(0, 24).addBox(-4, -8.75F, -4, 8, 2, 8)
            .texOffs(34, 24).addBox(-4, -7.25F, 2.3F, 8, 6, 2)
            .texOffs(58, 24).addBox(-4.05F, -7.1F, -2.5F, 1, 5, 6)
            .texOffs(58, 24).addBox(3.05F, -7.1F, -2.5F, 1, 4, 6), PartPose.ZERO);
        head.addOrReplaceChild("fringe", CubeListBuilder.create()
            .texOffs(76, 24).addBox(-3.9F, -7.2F, -4.05F, 3, 2, 1)
            .texOffs(86, 24).addBox(-1.3F, -7.2F, -4.08F, 3, 1, 1)
            .texOffs(76, 24).addBox(1.45F, -7.1F, -4.01F, 2, 2, 1), PartPose.ZERO);
        root.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
        PartDefinition body = root.addOrReplaceChild("body", CubeListBuilder.create()
            .texOffs(32, 0).addBox(-3.5F, 0, -2, 7, 12, 4), PartPose.ZERO);
        body.addOrReplaceChild("collar", CubeListBuilder.create()
            .texOffs(0, 42).addBox(-2.5F, -.25F, -2.2F, 5, 2, 5), PartPose.ZERO);
        body.addOrReplaceChild("belt", CubeListBuilder.create()
            .texOffs(24, 42).addBox(-3.7F, 9, -2.3F, 8, 1, 5), PartPose.ZERO);
        body.addOrReplaceChild("buckle", CubeListBuilder.create()
            .texOffs(52, 42).addBox(-.65F, 8.9F, -2.6F, 1.3F, 1.2F, .4F), PartPose.ZERO);
        body.addOrReplaceChild("pouch", CubeListBuilder.create()
            .texOffs(60, 42).addBox(1.55F, 9.3F, -2.7F, 2, 3, 1), PartPose.ZERO);
        root.addOrReplaceChild("right_arm", CubeListBuilder.create()
            .texOffs(64, 0).addBox(-2, -2, -2, 3, 12, 4), PartPose.offset(-4.5F, 2, 0));
        root.addOrReplaceChild("left_arm", CubeListBuilder.create()
            .texOffs(64, 0).addBox(-1, -2, -2, 3, 12, 4), PartPose.offset(4.5F, 2, 0));
        PartDefinition rightLeg = root.addOrReplaceChild("right_leg", CubeListBuilder.create()
            .texOffs(80, 0).addBox(-1.5F, 0, -2, 3, 12, 4), PartPose.offset(-1.75F, 12, 0));
        PartDefinition leftLeg = root.addOrReplaceChild("left_leg", CubeListBuilder.create()
            .texOffs(80, 0).addBox(-1.5F, 0, -2, 3, 12, 4), PartPose.offset(1.75F, 12, 0));
        for (PartDefinition leg : new PartDefinition[]{rightLeg, leftLeg}) {
            leg.addOrReplaceChild("boot", CubeListBuilder.create()
                .texOffs(0, 58).addBox(-1.65F, 7, -2.5F, 3.3F, 5, 5), PartPose.ZERO);
        }
        return LayerDefinition.create(mesh, 128, 128);
    }
}
