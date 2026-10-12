package dev.bekhilitt.protocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
/** 0=attack, 1=guard lease, 2=dodge; invalid values are rejected by server. */
public record ControlPayload(int action) implements CustomPacketPayload {
    public static final Type<ControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("bekhilitt", "control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlPayload> CODEC = StreamCodec.of(
        (buf, value) -> buf.writeVarInt(value.action()), buf -> new ControlPayload(buf.readVarInt()));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
