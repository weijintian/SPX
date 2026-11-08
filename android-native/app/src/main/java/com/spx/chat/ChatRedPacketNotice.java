package com.spx.chat;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.TextView;
import org.json.JSONObject;

/** Only server-owned system records are actionable; ordinary messages remain plain text. */
final class ChatRedPacketNotice {
    private static final String PREFIX="spxredpacket:claim:";
    final String packetId,receiverName,senderName;
    final int receiverId,senderId;
    private ChatRedPacketNotice(JSONObject data){
        packetId=data.optString("packet_id");receiverId=data.optInt("receiver_id");senderId=data.optInt("sender_id");
        receiverName=name(data.optString("receiver_name"));senderName=name(data.optString("sender_name"));
    }
    static ChatRedPacketNotice parse(JSONObject message){
        if(message==null||!"system".equals(message.optString("message_origin"))||!"text".equals(message.optString("message_type")))return null;
        String raw=message.optString("media_url");if(!raw.startsWith(PREFIX)||raw.length()>2000)return null;
        try{
            ChatRedPacketNotice value=new ChatRedPacketNotice(new JSONObject(raw.substring(PREFIX.length())));
            if(!value.packetId.matches("[a-f0-9]{32}")||value.receiverId<=0||value.senderId<=0||message.optInt("sender_id")!=value.receiverId)return null;
            return value;
        }catch(Exception invalid){return null;}
    }
    private static String name(String value){
        String clean=value.replaceAll("[\\p{Cc}\\p{Cf}]","").trim();
        return clean.codePointCount(0,clean.length())>64?clean.substring(0,clean.offsetByCodePoints(0,64)):clean;
    }
    String text(int viewer,boolean english){
        String who=viewer==receiverId?(english?"You":"你"):receiverName.isEmpty()?(english?"A friend":"好友"):receiverName;
        if(english){
            String owner=senderId==receiverId?(viewer==senderId?"your own":"their own"):viewer==senderId?"your":(senderName.isEmpty()?"a friend's":senderName+"'s");
            return who+" claimed "+owner+" red packet";
        }
        String owner=senderId==receiverId?"自己":viewer==senderId?"你":senderName.isEmpty()?"好友":senderName;
        return who+"领取了"+owner+"的红包";
    }
    void bind(TextView view,int viewer,boolean english,Runnable open){
        String label=text(viewer,english),link=english?"red packet":"红包";
        SpannableString styled=new SpannableString(label);int start=label.lastIndexOf(link);
        styled.setSpan(new ClickableSpan(){
            @Override public void onClick(View widget){open.run();}
            @Override public void updateDrawState(TextPaint paint){paint.setColor(0xffad803f);paint.setUnderlineText(false);}
        },start,start+link.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled);view.setMovementMethod(LinkMovementMethod.getInstance());view.setHighlightColor(Color.TRANSPARENT);
    }
}
