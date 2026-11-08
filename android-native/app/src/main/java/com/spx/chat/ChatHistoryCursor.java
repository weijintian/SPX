package com.spx.chat;

/** REST history advances independently from realtime messages and send acknowledgements. */
final class ChatHistoryCursor {
    private long sequence;
    private long active;
    private int afterId;

    void reset() { sequence++; active = 0; afterId = 0; }
    int afterId() { return afterId; }
    void seed(int cachedThrough) { if (active == 0) afterId = Math.max(afterId, cachedThrough); }
    long begin() {
        if (active != 0) return 0;
        active = ++sequence;
        return active;
    }
    boolean finish(long request, int fetchedThrough) {
        if (request == 0 || active != request) return false;
        afterId = Math.max(afterId, fetchedThrough);
        active = 0;
        return true;
    }
    void fail(long request) { if (request != 0 && active == request) active = 0; }
}
