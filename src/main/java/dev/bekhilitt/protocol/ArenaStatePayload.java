package dev.bekhilitt.protocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** Presentation only: 0=inactive, 1=fight, 2=staging. */
public record ArenaStatePayload(int state) implements CustomPacketPayload {
    public static final Type<ArenaStatePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("bekhilitt", "state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ArenaStatePayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeVarInt(value.state()), buf -> new ArenaStatePayload(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
