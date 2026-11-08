package com.spx.chat;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.util.UUID;

/** A durable pending record is reserved before any debit. An unknown result never creates a new ID. */
final class FinancialRequests {
    interface Callback {
        void confirmed(JSONObject result);
        void notSubmitted();
        void rejected(String message);
        void uncertain(String message);
    }
    interface Transport { void post(String action,JSONObject body,ApiClient.Callback callback); }
    private static final Object LOCK=new Object();
    private final Context context;
    private final SharedPreferences prefs;
    private final String scope;
    private final Transport transport;
    FinancialRequests(Context context,int userId) {
        this(context,userId,(action,body,callback)->ApiClient.post(context,action,body,callback));
    }
    FinancialRequests(Context context,int userId,Transport transport) {
        this.context=context;this.transport=transport;
        prefs=context.getSharedPreferences("spx_financial_pending_v1",Context.MODE_PRIVATE);
        scope=AccountScope.id(ApiClient.baseUrl(context),userId)+"|";
    }
    private String key(String action) {
        if(!"wallet.withdraw".equals(action)&&!"staking.create".equals(action))throw new IllegalArgumentException("Unsupported financial action");
        return scope+action;
    }
    boolean pending(String action) { synchronized(LOCK){return !prefs.getString(key(action),"").isEmpty();} }
    private String message(String key){return I18n.t(context,ApiClient.language(context),key);}
    void submit(String action,JSONObject payload,Callback callback) {
        final String stored;
        synchronized(LOCK) {
            if(pending(action)){callback.uncertain(message("financial_pending"));return;}
            try {
                JSONObject request=new JSONObject(payload.toString());
                request.put("client_request_id",UUID.randomUUID().toString());
                stored=request.toString();
                if(!prefs.edit().putString(key(action),stored).commit()){callback.rejected(message("financial_save_failed"));return;}
            } catch(org.json.JSONException invalid){callback.rejected(message("financial_invalid"));return;}
        }
        send(action,stored,false,callback);
    }
    /** Resolve uses the server's transaction lock: either return the committed result or tombstone the old ID. */
    void resolve(String action,Callback callback) {
        final String stored;
        synchronized(LOCK){stored=prefs.getString(key(action),"");}
        if(stored.isEmpty()){callback.notSubmitted();return;}
        send(action,stored,true,callback);
    }
    private boolean clear(String action,String stored) {
        synchronized(LOCK){String current=prefs.getString(key(action),"");if(!stored.equals(current))return current.isEmpty();return prefs.edit().remove(key(action)).commit();}
    }
    private void send(String action,String stored,boolean resolving,Callback callback) {
        final JSONObject body;
        try {body=new JSONObject(stored);if(resolving)body.put("operation",action);}
        catch(org.json.JSONException invalid){callback.uncertain(message("financial_record_invalid"));return;}
        transport.post(resolving?"financial.resolve":action,body,new ApiClient.Callback(){
            public void onSuccess(Object data){
                if(!(data instanceof JSONObject)){callback.uncertain(message("financial_pending"));return;}
                JSONObject result=(JSONObject)data;
                boolean sameId=body.optString("client_request_id").equals(result.optString("client_request_id"));
                boolean cancelled=result.optBoolean("cancelled");
                boolean committed="wallet.withdraw".equals(action)?result.optInt("transaction_id")>0:result.optInt("id")>0;
                if(!sameId || (!cancelled&&!committed)){callback.uncertain(message("financial_pending"));return;}
                if(!clear(action,stored)){callback.uncertain(message("financial_save_failed"));return;}
                AccountPageCache.invalidate(context);
                if(cancelled)callback.notSubmitted();else callback.confirmed(result);
            }
            public void onError(String message){callback.uncertain(message);}
            public void onHttpError(int status,String message){
                // A 409 may be a payload conflict with an already committed request. Keep it for resolution.
                if(!resolving && (status==400 || status==403 || status==404 || status==422)) {
                    if(clear(action,stored))callback.rejected(message);else callback.uncertain(message("financial_save_failed"));
                } else callback.uncertain(message);
            }
        });
    }
}
