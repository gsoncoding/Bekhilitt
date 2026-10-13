package dev.bekhilitt.protocol;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Presentation only; 0..3 stance=ready/attack/guard/dodge, 0..4 feedback=none/hit/hurt/block/evade. */
public record CombatStatusPayload(int landed, int blocks, int dodges, int stance, boolean windup, int feedback)
        implements CustomPacketPayload {
    public static final Type<CombatStatusPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("bekhilitt", "combat_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CombatStatusPayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            buf.writeVarInt(value.landed());
            buf.writeVarInt(value.blocks());
            buf.writeVarInt(value.dodges());
            buf.writeVarInt(value.stance());
            buf.writeBoolean(value.windup());
            buf.writeVarInt(value.feedback());
        }, buf -> new CombatStatusPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
            buf.readVarInt(), buf.readBoolean(), buf.readVarInt()));

    public CombatStatusPayload {
        if (landed < 0 || blocks < 0 || dodges < 0 || stance < 0 || stance > 3 || feedback < 0 || feedback > 4)
            throw new IllegalArgumentException("Invalid combat presentation");
    }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
