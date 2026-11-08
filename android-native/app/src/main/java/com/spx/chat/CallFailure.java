package com.spx.chat;

import java.util.Locale;

/** Preserve failure meaning without leaking SDK parameters, IDs or credentials into logs/UI. */
final class CallFailure {
    static boolean expired(int code,String message){
        String s=message==null?"":message.toLowerCase(Locale.ROOT);
        return code==6206||code==70001||code==-10001||s.contains("key is expired")||(s.contains("usersig")&&s.contains("expir"))||(s.contains("signature")&&s.contains("expir"));
    }
    static boolean uninitialized(int code,String message){
        String s=message==null?"":message.trim().toLowerCase(Locale.ROOT);
        return code==-1002||(code==-1&&(s.equals("user not logged in")||s.equals("user not logged in.")));
    }
    static String category(int code,String message){
        if(expired(code,message))return "expired_credentials";
        if(uninitialized(code,message))return "sdk_not_initialized";
        String s=message==null?"":message.toLowerCase(Locale.ROOT);
        if(code==101011||code==-1004||code==-1005||s.contains("package")&&(s.contains("expir")||s.contains("purchas")||s.contains("activat")||s.contains("upgrad"))||s.contains("require payment"))return "service_package";
        if(s.contains("network")||s.contains("timeout"))return "network";
        if(s.contains("permission"))return "permission";
        return "sdk_failure";
    }
    static String label(int code,String message){
        switch(category(code,message)){
            case "service_package":return "音视频服务未开通或已到期，请联系管理员";
            case "network":return "通话网络连接失败，请检查网络后重试";
            case "permission":return "通话权限不足，请检查麦克风和相机权限";
            default:return "通话连接失败，请稍后重试（"+code+"）";
        }
    }
}
