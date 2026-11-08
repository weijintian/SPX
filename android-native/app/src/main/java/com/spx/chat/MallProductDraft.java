package com.spx.chat;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Local recovery is separate from the manually synchronized server draft. */
final class MallProductDraft {
    private final SharedPreferences store;
    private final String key;
    MallProductDraft(Context context,String server,int user,JSONObject product){
        store=context.getSharedPreferences("mall-product-local-v1",0);
        String target=product.optInt("id")>0?"product:"+product.optInt("id"):
                product.optInt("draft_id")>0?"draft:"+product.optInt("draft_id"):"new";
        key=server+":"+user+":"+target;
    }
    JSONObject load(){try{String raw=store.getString(key,"");return raw.isEmpty()?null:new JSONObject(raw);}catch(Exception e){return null;}}
    void save(JSONObject payload){store.edit().putString(key,payload.toString()).apply();}
    void clear(){store.edit().remove(key).commit();}
}
