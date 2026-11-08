package com.spx.chat;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Display snapshots only. Transaction forms and submission validation always use live API requests. */
final class AccountPageCache {
    interface Notice { void state(boolean cached, String error); }
    private static final String PREFS = "spx_account_pages_v1";
    private static long revision;
    private static final long MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    static void get(Context context, int userId, String action, boolean force, ApiClient.Callback callback, Notice notice) {
        if(!java.util.Arrays.asList("wallet", "wallet.binding", "staking", "staking.plans").contains(action))throw new IllegalArgumentException("Not a display snapshot");
        final ApiClient.SessionStamp session = new ApiClient.SessionStamp(context);
        final SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String key = AccountScope.id(session.server,userId)+"|"+ApiClient.language(context)+"|"+action;
        final long capturedRevision;
        synchronized(AccountPageCache.class){capturedRevision=revision;}
        String previous = null;
        long age = System.currentTimeMillis()-prefs.getLong(key+".time",0);
        if(age>=0 && age<MAX_AGE_MS) {
            String saved=prefs.getString(key,"");
            if(!saved.isEmpty())try {
                Object value=saved.startsWith("[")?new JSONArray(saved):new JSONObject(saved);
                previous=saved;callback.onSuccess(value);
            } catch(org.json.JSONException invalid) { prefs.edit().remove(key).remove(key+".time").apply(); }
        }
        final String shown=previous;
        long freshFor="staking.plans".equals(action)?60000:15000;
        boolean fresh=shown!=null && age<freshFor && !force;
        notice.state(shown!=null && !fresh, "");
        if(fresh)return;
        ApiClient.get(context,action,null,new ApiClient.Callback(){
            public void onSuccess(Object data){
                if(!session.valid(context))return;
                synchronized(AccountPageCache.class){
                    if(capturedRevision!=revision){notice.state(shown!=null,I18n.t(context,ApiClient.language(context),"cache_changed_retry"));return;}
                    if(!(data instanceof JSONObject)&&!(data instanceof JSONArray)){onError(I18n.t(context,ApiClient.language(context),"network_response_invalid"));return;}
                    prefs.edit().putString(key,data.toString()).putLong(key+".time",System.currentTimeMillis()).apply();
                }
                notice.state(false,"");
                if(shown==null || !shown.equals(data.toString()))callback.onSuccess(data);
            }
            public void onError(String message){notice.state(shown!=null,message);if(shown==null)callback.onError(message);}
        });
    }
    static synchronized long revision(){return revision;}
    static synchronized void invalidate(Context context) {
        revision++;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().clear().apply();
    }
    static void invalidateForMutation(Context context,String action) {
        if(action.startsWith("wallet.") || action.startsWith("staking.") || action.startsWith("orders.") || action.startsWith("red_packets.") || action.startsWith("redpackets.") || action.equals("financial.resolve"))invalidate(context);
    }
    private AccountPageCache() { }
}
