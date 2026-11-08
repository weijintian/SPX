package com.spx.chat;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Shared 24dp stroke system for chat controls, independent of device fonts. */
final class ChatControlIcon extends Drawable {
    private final String kind;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tint;
    ChatControlIcon(String kind,int tint){this.kind=kind;this.tint=tint;}
    @Override public void draw(Canvas c){
        Rect b=getBounds();c.save();c.translate(b.left,b.top);c.scale(b.width()/24f,b.height()/24f);
        paint.setColor(tint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.65f);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);
        switch(kind){
            case "file":{Path p=new Path();p.moveTo(6,2);p.lineTo(14,2);p.lineTo(20,8);p.lineTo(20,22);p.lineTo(4,22);p.lineTo(4,2);p.close();c.drawPath(p,paint);c.drawLine(14,2,14,8,paint);c.drawLine(14,8,20,8,paint);c.drawLine(8,13,16,13,paint);c.drawLine(8,17,14,17,paint);break;}
            case "minimize":c.drawLine(4,4,11,11,paint);c.drawLine(5,11,11,11,paint);c.drawLine(11,5,11,11,paint);c.drawRoundRect(13,13,22,21,1,1,paint);break;
            case "phone":case "phoneoff":{Path p=new Path();p.moveTo(5,3);p.lineTo(9,3);p.lineTo(10,8);p.lineTo(7.5f,10);p.cubicTo(9,13,11,15,14,16.5f);p.lineTo(16,14);p.lineTo(21,15);p.lineTo(21,19);p.cubicTo(21,23,14,21,9,16);p.cubicTo(4,11,1,3,5,3);c.drawPath(p,paint);if(kind.equals("phoneoff"))c.drawLine(3,21,21,3,paint);break;}
            case "hangup":{Path p=new Path();p.moveTo(3,15);p.lineTo(3,11);p.cubicTo(8,6,16,6,21,11);p.lineTo(21,15);p.lineTo(16,15);p.lineTo(16,12);p.cubicTo(14,11,10,11,8,12);p.lineTo(8,15);p.close();c.drawPath(p,paint);break;}
            case "video":case "cameraoff":c.drawRoundRect(2,5,16,19,2,2,paint);{Path p=new Path();p.moveTo(16,10);p.lineTo(22,6);p.lineTo(22,18);p.lineTo(16,14);c.drawPath(p,paint);}if(kind.equals("cameraoff"))c.drawLine(3,2,21,22,paint);break;
            case "mute":c.drawRoundRect(9,3,15,14,3,3,paint);c.drawArc(new RectF(6,6,18,18),0,180,false,paint);c.drawLine(12,18,12,21,paint);c.drawLine(3,3,21,21,paint);break;
            case "speaker":{Path p=new Path();p.moveTo(3,9);p.lineTo(7,9);p.lineTo(12,5);p.lineTo(12,19);p.lineTo(7,15);p.lineTo(3,15);p.close();c.drawPath(p,paint);c.drawArc(new RectF(10,7,20,17),-60,120,false,paint);c.drawArc(new RectF(8,3,24,21),-55,110,false,paint);break;}
            case "switchcamera":c.drawRoundRect(3,5,21,20,2,2,paint);c.drawArc(new RectF(8,8,16,17),-140,280,false,paint);c.drawLine(7,9,10,9,paint);c.drawLine(7,9,7,12,paint);break;
            case "person":c.drawCircle(12,7.5f,4,paint);c.drawArc(new RectF(4,13,20,25),180,180,false,paint);break;
            case "redpacket":c.drawRoundRect(5,2,19,22,2,2,paint);c.drawArc(new RectF(5,2,19,15),0,180,false,paint);c.drawCircle(12,13,2.5f,paint);break;
            case "back":{Path p=new Path();p.moveTo(14.5f,5);p.lineTo(7.5f,12);p.lineTo(14.5f,19);c.drawPath(p,paint);break;}
            case "chevronright":{Path p=new Path();p.moveTo(9,5);p.lineTo(16,12);p.lineTo(9,19);c.drawPath(p,paint);break;}
            case "emoji":c.drawCircle(12,12,9,paint);c.drawPoint(8.5f,9,paint);c.drawPoint(15.5f,9,paint);c.drawArc(new RectF(7,8,17,17),25,130,false,paint);break;
            case "keyboard":c.drawRoundRect(2.5f,5,21.5f,19,2,2,paint);for(int y=9;y<=12;y+=3)for(int x=6;x<=18;x+=4)c.drawPoint(x,y,paint);c.drawLine(7,16,17,16,paint);break;
            case "voice":c.drawRoundRect(9,3,15,14,3,3,paint);c.drawArc(new RectF(6,6,18,18),0,180,false,paint);c.drawLine(12,18,12,21,paint);c.drawLine(9,21,15,21,paint);break;
            case "detail":paint.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)c.drawCircle(x,12,1.6f,paint);break;
            default:c.drawCircle(12,12,9,paint);c.drawLine(7.5f,12,16.5f,12,paint);c.drawLine(12,7.5f,12,16.5f,paint);
        }c.restore();
    }
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
