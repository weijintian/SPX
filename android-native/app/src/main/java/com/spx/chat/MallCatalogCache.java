package com.spx.chat;
import android.content.Context;
import org.json.*;
import java.util.*;

/** Short-lived public catalog snapshots. Orders, wallet and checkout always use live requests. */
final class MallCatalogCache {
    private static final long MAX_AGE=120000;
    private static final long REFRESH_AFTER=15000;
    private static long generation;
    private static final LinkedHashMap<String,Entry> CACHE=new LinkedHashMap<>();
    private static final class Entry{final String json;final long time;Entry(Object d){json=d.toString();time=android.os.SystemClock.elapsedRealtime();}}
    static synchronized void clear(){CACHE.clear();generation++;}
    static void get(Context c,String action,Map<String,String> params,ApiClient.Callback cb){
        String key=ApiClient.baseUrl(c)+"|"+ApiClient.language(c)+"|"+ApiClient.token(c)+"|"+action+"|"+new TreeMap<>(params==null?Collections.<String,String>emptyMap():params);
        Entry e;long current; synchronized(MallCatalogCache.class){e=CACHE.get(key);current=generation;}
        boolean shown=false;
        if(e!=null&&android.os.SystemClock.elapsedRealtime()-e.time<MAX_AGE){try{cb.onSuccess(e.json.startsWith("[")?new JSONArray(e.json):new JSONObject(e.json));shown=true;}catch(JSONException ignored){}}
        if(shown&&android.os.SystemClock.elapsedRealtime()-e.time<REFRESH_AFTER)return;
        final boolean displayed=shown;
        final String previous=shown?e.json:null;
        ApiClient.get(c,action,params,new ApiClient.Callback(){public void onSuccess(Object d){if(d instanceof JSONArray||d instanceof JSONObject)synchronized(MallCatalogCache.class){if(current==generation){CACHE.put(key,new Entry(d));while(CACHE.size()>20)CACHE.remove(CACHE.keySet().iterator().next());}}if(previous==null||!previous.equals(String.valueOf(d)))cb.onSuccess(d);}public void onError(String s){if(!displayed)cb.onError(s);}});
    }
}
