package dev.bekhilitt.protocol;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-owned presentation snapshot. Text fields accept translation keys or literal fallbacks. */
public record EpisodeStatePayload(String phase, String checkpoint, long revision, int page,
                                  String speaker, String body, String objective, boolean locked)
        implements CustomPacketPayload {
    public static final String INACTIVE = "inactive";
    public static final Type<EpisodeStatePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("bekhilitt", "episode_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EpisodeStatePayload> CODEC = StreamCodec.of(
        (buf, value) -> {
            buf.writeUtf(value.phase(), 128);
            buf.writeUtf(value.checkpoint(), 128);
            buf.writeVarLong(value.revision());
            buf.writeVarInt(value.page());
            buf.writeUtf(value.speaker(), 256);
            buf.writeUtf(value.body(), 4096);
            buf.writeUtf(value.objective(), 1024);
            buf.writeBoolean(value.locked());
        },
        buf -> new EpisodeStatePayload(buf.readUtf(128), buf.readUtf(128), buf.readVarLong(),
            buf.readVarInt(), buf.readUtf(256), buf.readUtf(4096), buf.readUtf(1024), buf.readBoolean()));

    public EpisodeStatePayload {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(speaker);
        Objects.requireNonNull(body);
        Objects.requireNonNull(objective);
        if (revision < 0 || page < -1) throw new IllegalArgumentException("Invalid episode snapshot");
    }

    public boolean active() { return !INACTIVE.equals(phase); }
    /** page=-1 is an automatic scene or playable objective, never a client-authorized advance. */
    public boolean canAdvance() { return active() && page >= 0; }
    public boolean showsScreen() { return active() && (locked || canAdvance()); }
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
