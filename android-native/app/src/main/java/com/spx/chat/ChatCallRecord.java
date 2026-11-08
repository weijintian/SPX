package com.spx.chat;

/** Only server-owned private call records can launch a redial action. */
final class ChatCallRecord {
    private static final java.util.regex.Pattern DURATION = java.util.regex.Pattern.compile("通话时长 ([0-9]{2,8}:[0-5][0-9])");
    static int media(String origin,String type,String content,int groupId){
        if(groupId!=0||!"call".equals(origin)||!"text".equals(type)||content==null)return 0;
        if(content.startsWith("[语音通话] "))return 1;
        if(content.startsWith("[视频通话] "))return 2;
        return 0;
    }
    static String result(String content, int media) {
        String prefix = media == 2 ? "[视频通话] " : "[语音通话] ";
        return content != null && content.startsWith(prefix) ? content.substring(prefix.length()).trim() : "";
    }
    static String duration(String content, int media) {
        java.util.regex.Matcher match = DURATION.matcher(result(content, media));
        return match.matches() ? match.group(1) : "";
    }
    static String statusKey(String content, int media) {
        switch (result(content, media)) {
            case "已取消": return "call_record_canceled";
            case "已拒绝": return "call_record_rejected";
            case "对方忙线中": return "call_record_busy";
            case "无人接听": return "call_record_unanswered";
            case "对方暂时不在线": return "call_record_offline";
            case "连接中断": return "call_record_interrupted";
            default: return "";
        }
    }
}
