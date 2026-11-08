package com.spx.chat;

import android.app.Activity;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.provider.MediaStore;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Photo selection stays in the photo picker or gallery. Documents have their own entry. */
final class ChatGallery {
    /** Vendor galleries may return ClipData without setting Intent.data. */
    static android.net.Uri selectedUri(Intent result) {
        if (result == null) return null;
        android.net.Uri uri = readableUri(result.getData());
        if (uri != null) return uri;
        android.content.ClipData clips = result.getClipData();
        if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) {
            uri = readableUri(clips.getItemAt(i).getUri());
            if (uri != null) return uri;
        }
        // Some gallery/share providers return their selected item as EXTRA_STREAM.
        Object stream = result.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream instanceof android.net.Uri) return readableUri((android.net.Uri) stream);
        return null;
    }

    /** Returns every image selected by the system picker, preserving user order. */
    static List<android.net.Uri> selectedUris(Intent result) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        ArrayList<android.net.Uri> values = new ArrayList<>();
        if (result == null) return values;
        android.net.Uri uri = readableUri(result.getData());
        if (uri != null && seen.add(uri.toString())) values.add(uri);
        android.content.ClipData clips = result.getClipData();
        if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) {
            uri = readableUri(clips.getItemAt(i).getUri());
            if (uri != null && seen.add(uri.toString())) values.add(uri);
        }
        Object stream = result.getParcelableExtra(Intent.EXTRA_STREAM);
        if (stream instanceof android.net.Uri) {
            uri = readableUri((android.net.Uri) stream);
            if (uri != null && seen.add(uri.toString())) values.add(uri);
        }
        return values;
    }
    private static android.net.Uri readableUri(android.net.Uri uri) {
        if (uri == null) return null;
        return "content".equals(uri.getScheme()) || "file".equals(uri.getScheme()) ? uri : null;
    }
    static boolean open(Activity a, String mime, int request) {
        // Older OEM galleries resolve PICK_IMAGES without implementing its result contract.
        String maker = android.os.Build.MANUFACTURER;
        boolean nativeGallery = android.os.Build.VERSION.SDK_INT < 33
                || "HONOR".equalsIgnoreCase(maker) || "HUAWEI".equalsIgnoreCase(maker);
        Intent gallery = new Intent(Intent.ACTION_PICK, mime.startsWith("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType(mime);
        gallery.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (nativeGallery) try { a.startActivityForResult(gallery, request); return true; } catch (ActivityNotFoundException ignored) {}
        Intent picker = new Intent("android.provider.action.PICK_IMAGES").setType(mime);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { a.startActivityForResult(picker, request); return true; } catch (ActivityNotFoundException ignored) {}
        try { a.startActivityForResult(gallery, request); return true; } catch (ActivityNotFoundException e) { AppNotice.show(a, "未找到相册，请安装或启用系统相册"); return false; }
    }

    static boolean openMultiple(Activity a, String mime, int request) {
        Intent picker = new Intent("android.provider.action.PICK_IMAGES").setType(mime);
        picker.putExtra("android.provider.extra.PICK_IMAGES_MAX", 9);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= 33) try { a.startActivityForResult(picker, request); return true; } catch (ActivityNotFoundException | IllegalArgumentException ignored) {}
        Intent gallery = new Intent(Intent.ACTION_PICK, mime.startsWith("video") ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI).setType(mime);
        gallery.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); gallery.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { a.startActivityForResult(gallery, request); return true; } catch (ActivityNotFoundException ignored) {}
        AppNotice.show(a, "未找到相册，请安装或启用系统相册"); return false;
    }
    static boolean openFiles(Activity a, int request) {
        Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        files.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); files.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { a.startActivityForResult(files, request); return true; } catch (ActivityNotFoundException ignored) { AppNotice.show(a, "未找到文件选择器"); return false; }
    }
    private ChatGallery() {}
}
