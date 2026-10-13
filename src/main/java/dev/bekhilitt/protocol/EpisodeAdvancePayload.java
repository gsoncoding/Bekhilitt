package dev.bekhilitt.protocol;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Intent only. The server checks the revision and owns every transition/checkpoint. */
public record EpisodeAdvancePayload(long revision, Action action) implements CustomPacketPayload {
    public enum Action { NEXT, CANCEL }
    public static final Type<EpisodeAdvancePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("bekhilitt", "episode_advance"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EpisodeAdvancePayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            buf.writeVarLong(value.revision());
            buf.writeEnum(value.action());
        }, buf -> new EpisodeAdvancePayload(buf.readVarLong(), buf.readEnum(Action.class)));

    public EpisodeAdvancePayload {
        Objects.requireNonNull(action);
        if (revision < 0) throw new IllegalArgumentException("Invalid episode revision");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
