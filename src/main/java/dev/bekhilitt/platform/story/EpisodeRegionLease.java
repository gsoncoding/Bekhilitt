package dev.bekhilitt.platform.story;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Runtime-only region ticket. Never writes the world's persistent forced-chunk list. */
public final class EpisodeRegionLease implements AutoCloseable {
    private static final ChunkPos CENTER = new ChunkPos(0, 0);
    private static final int RADIUS = 4;
    private static final TicketType<ChunkPos> TYPE = TicketType.create("bekhilitt_episode", java.util.Comparator.comparingLong(ChunkPos::toLong));
    private final ServerLevel level;
    private boolean acquired;
    public static void initialize() { }
    public EpisodeRegionLease(ServerLevel level) { this.level = level; }
    public void acquire() {
        if (acquired) throw new IllegalStateException("Episode region lease already acquired");
        acquired = true;
        level.getChunkSource().addRegionTicket(TYPE, CENTER, RADIUS, CENTER);
    }
    public boolean ready() {
        if (!acquired) return false;
        for (int x = -2; x <= 1; x++) for (int z = -2; z <= 1; z++) {
            ChunkPos pos = new ChunkPos(x, z);
            if (!level.isPositionEntityTicking(pos.getMiddleBlockPosition(0))) return false;
        }
        return true;
    }
    @Override public void close() {
        if (!acquired) return;
        acquired = false;
        level.getChunkSource().removeRegionTicket(TYPE, CENTER, RADIUS, CENTER);
    }
}
