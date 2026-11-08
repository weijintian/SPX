package com.spx.chat;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Visible-only media with bounded transfers and decoding work. */
final class ChatMediaWork {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2);
    private static final ExecutorService AVATARS = Executors.newFixedThreadPool(2);
    private static final ExecutorService CACHE = Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.locks.ReentrantReadWriteLock FILES = new java.util.concurrent.locks.ReentrantReadWriteLock(true);
    private static final Object[] LOCKS = new Object[32];
    static { for (int i = 0; i < LOCKS.length; i++) LOCKS[i] = new Object(); }
    private ChatMediaWork() {}
    private static Runnable reading(Runnable work) { return () -> { FILES.readLock().lock();try{work.run();}finally{FILES.readLock().unlock();} }; }
    static void execute(Runnable work) { WORKERS.execute(reading(work)); }
    static void executeCache(Runnable work) { CACHE.execute(reading(work)); }
    static void clearCaches(Runnable work) { CACHE.execute(() -> { FILES.writeLock().lock();try{work.run();}finally{FILES.writeLock().unlock();} }); }
    static void executeAvatar(String key, Runnable work) {
        AVATARS.execute(reading(() -> { synchronized (LOCKS[(key.hashCode() & 0x7fffffff) % LOCKS.length]) { work.run(); } }));
    }
    static void execute(String key, Runnable work) {
        execute(() -> { synchronized (LOCKS[(key.hashCode() & 0x7fffffff) % LOCKS.length]) { work.run(); } });
    }
    static void whenVisible(View view, Runnable work) { new VisibleTask(view, work).install(); }

    private static final class VisibleTask implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnPreDrawListener {
        final View view; final Runnable work; final Rect rect = new Rect();
        ViewTreeObserver observer; boolean started;
        VisibleTask(View view, Runnable work) { this.view = view; this.work = work; }
        void install() {
            view.addOnAttachStateChangeListener(this);
            if (view.isAttachedToWindow()) observe();
        }
        void observe() {
            removeObservers(); observer = view.getViewTreeObserver();
            observer.addOnScrollChangedListener(this); observer.addOnPreDrawListener(this);
            view.post(this::check);
        }
        void removeObservers() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnScrollChangedListener(this); observer.removeOnPreDrawListener(this);
            }
            observer = null;
        }
        void check() {
            if (started || !view.isAttachedToWindow() || !view.isShown() || !view.getGlobalVisibleRect(rect)) return;
            started = true; removeObservers(); view.removeOnAttachStateChangeListener(this); work.run();
        }
        public void onViewAttachedToWindow(View v) { if (!started) observe(); }
        public void onViewDetachedFromWindow(View v) { removeObservers(); }
        public void onScrollChanged() { check(); }
        public boolean onPreDraw() { check(); return true; }
    }
}
