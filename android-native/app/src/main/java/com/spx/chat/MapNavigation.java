package com.spx.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONObject;
import java.util.ArrayList;

/** Shop coordinates retain their source system; each map receives its own format. */
public final class MapNavigation {
    private MapNavigation() {}

    public static void open(Activity activity, JSONObject shop) {
        String address = shop.optString("address").trim();
        boolean hasCoordinates = !shop.isNull("latitude") && !shop.isNull("longitude")
                && shop.optString("latitude").trim().length() > 0 && shop.optString("longitude").trim().length() > 0;
        String title = shop.optString("name", "门店");
        String name = Uri.encode(title);
        double[] gcj = null, wgs = null, bd = null;
        if (hasCoordinates) {
            double lat = shop.optDouble("latitude"), lon = shop.optDouble("longitude");
            String system = shop.optString("coordinate_system", "gcj02");
            gcj = toGcj(lat, lon, system);
            wgs = gcjToWgs(gcj[0], gcj[1]);
            bd = gcjToBd(gcj[0], gcj[1]);
        }
        String[] packages = {"com.autonavi.minimap", "com.baidu.BaiduMap", "com.tencent.map"};
        String[] names = {"高德地图", "百度地图", "腾讯地图"};
        String[] urls = hasCoordinates ? new String[] {
            "amapuri://route/plan/?sourceApplication=SPX&dlat=" + gcj[0] + "&dlon=" + gcj[1] + "&dname=" + name + "&dev=0&t=0",
            "baidumap://map/direction?destination=latlng:" + bd[0] + "," + bd[1] + "|name:" + name + "&coord_type=bd09ll&mode=driving&src=andr.spx.chat",
            "qqmap://map/routeplan?type=drive&to=" + name + "&tocoord=" + gcj[0] + "," + gcj[1] + "&referer=SPX"
        } : new String[] {
            "amapuri://search?keyword=" + Uri.encode(address) + "&sourceApplication=SPX",
            "baidumap://map/place/search?query=" + Uri.encode(address) + "&src=andr.spx.chat",
            "qqmap://map/search?keyword=" + Uri.encode(address) + "&referer=SPX"
        };
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Intent> intents = new ArrayList<>();
        for (int i = 0; i < packages.length; i++) {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urls[i])).setPackage(packages[i]);
            if (intent.resolveActivity(activity.getPackageManager()) != null) { labels.add(names[i]); intents.add(intent); }
        }
        if (hasCoordinates) {
            Intent other = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + wgs[0] + "," + wgs[1] + "?q=" + wgs[0] + "," + wgs[1] + "(" + name + ")"));
            if (other.resolveActivity(activity.getPackageManager()) != null) { labels.add("其他地图"); intents.add(other); }
        }
        if (hasCoordinates || !address.isEmpty()) {
            labels.add("网页地图");
            String web = hasCoordinates
                    ? "https://uri.amap.com/marker?position=" + gcj[1] + "," + gcj[0] + "&name=" + name + "&coordinate=gaode&callnative=0"
                    : "https://uri.amap.com/search?keyword=" + Uri.encode(address);
            intents.add(new Intent(Intent.ACTION_VIEW, Uri.parse(web)));
        }
        if (labels.isEmpty()) {
            AppNotice.show(activity, "暂无可用地图，请先完善门店地址");
            return;
        }
        new AlertDialog.Builder(activity).setTitle("选择导航应用 · " + title)
            .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                try { activity.startActivity(intents.get(which)); }
                catch (Exception e) { AppNotice.show(activity, "地图暂不可用，请复制门店地址"); }
            }).setNegativeButton("取消", null).show();
    }

    public static double[] toGcj(double lat, double lon, String system) {
        if ("bd09".equals(system)) {
            double x = lon - 0.0065, y = lat - 0.006;
            double z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * Math.PI * 3000 / 180);
            double theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * Math.PI * 3000 / 180);
            return new double[]{z * Math.sin(theta), z * Math.cos(theta)};
        }
        if (!"wgs84".equals(system) || outsideChina(lat, lon)) return new double[]{lat, lon};
        double x = lon - 105, y = lat - 35;
        double dlat = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        double dlon = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        dlat += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        dlat += (20 * Math.sin(y * Math.PI) + 40 * Math.sin(y / 3 * Math.PI)) * 2 / 3;
        dlat += (160 * Math.sin(y / 12 * Math.PI) + 320 * Math.sin(y * Math.PI / 30)) * 2 / 3;
        dlon += (20 * Math.sin(6 * x * Math.PI) + 20 * Math.sin(2 * x * Math.PI)) * 2 / 3;
        dlon += (20 * Math.sin(x * Math.PI) + 40 * Math.sin(x / 3 * Math.PI)) * 2 / 3;
        dlon += (150 * Math.sin(x / 12 * Math.PI) + 300 * Math.sin(x / 30 * Math.PI)) * 2 / 3;
        double rad = lat / 180 * Math.PI;
        double magic = 1 - 0.00669342162296594323 * Math.sin(rad) * Math.sin(rad);
        dlat = dlat * 180 / ((6378245 * (1 - 0.00669342162296594323)) / (magic * Math.sqrt(magic)) * Math.PI);
        dlon = dlon * 180 / (6378245 / Math.sqrt(magic) * Math.cos(rad) * Math.PI);
        return new double[]{lat + dlat, lon + dlon};
    }

    public static double[] gcjToWgs(double lat, double lon) {
        if (outsideChina(lat, lon)) return new double[]{lat, lon};
        double a = lat, b = lon;
        for (int i = 0; i < 5; i++) {
            double[] gcj = toGcj(a, b, "wgs84"); a -= gcj[0] - lat; b -= gcj[1] - lon;
        }
        return new double[]{a, b};
    }

    public static double[] gcjToBd(double lat, double lon) {
        double z = Math.sqrt(lon * lon + lat * lat) + 0.00002 * Math.sin(lat * Math.PI * 3000 / 180);
        double theta = Math.atan2(lat, lon) + 0.000003 * Math.cos(lon * Math.PI * 3000 / 180);
        return new double[]{z * Math.sin(theta) + 0.006, z * Math.cos(theta) + 0.0065};
    }

    private static boolean outsideChina(double lat, double lon) {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271;
    }
}
