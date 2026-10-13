package dev.bekhilitt.presentation;

/** Local reading only. One physical press may take one action, including across a server update. */
public final class DialoguePager {
    private int page, lineCount, capacity = 1;
    private boolean released;
    public void layout(int lines, int rows) {
        lineCount = Math.max(0, lines); capacity = Math.max(1, rows);
        page = Math.min(page, pages() - 1);
    }
    public void reset() { page = 0; released = false; }
    public void release() { released = true; }
    public boolean take(boolean pending) {
        if (!released || pending) return false;
        released = false;
        return true;
    }
    public boolean ready() { return released; }
    public int page() { return page; }
    public int pages() { return Math.max(1, (lineCount + capacity - 1) / capacity); }
    public int from() { return page * capacity; }
    public int to() { return Math.min(lineCount, from() + capacity); }
    public boolean more() { return page + 1 < pages(); }
    public void next() { if (more()) page++; }
    public void previous() { if (page > 0) page--; }
}
