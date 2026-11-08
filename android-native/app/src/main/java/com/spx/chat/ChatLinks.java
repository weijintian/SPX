package com.spx.chat;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.text.*;
import android.text.style.*;
import android.view.View;
import android.widget.*;
import java.util.*;

final class ChatLinks {
    static void bind(Activity a, TextView view) {
        SpannableString text=new SpannableString(view.getText());
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?i)(?:https?://|www[.])[^\\s<>\"，。！？；：、（）【】]+",java.util.regex.Pattern.UNICODE_CASE).matcher(text);
        boolean found=false;
        for(URLSpan old:text.getSpans(0,text.length(),URLSpan.class))text.removeSpan(old);
        while(matcher.find()){
            int start=matcher.start(),end=matcher.end();
            while(end>start&&".,;!?)】}".indexOf(text.charAt(end-1))>=0)end--;
            String url=text.subSequence(start,end).toString();if(url.toLowerCase(Locale.ROOT).startsWith("www."))url="https://"+url;
            Uri uri=Uri.parse(url);if(uri.getHost()==null||uri.getHost().isEmpty())continue;found=true;
            text.setSpan(new ClickableSpan(){public void onClick(View v){open(a,uri);}public void updateDrawState(TextPaint paint){paint.setColor(view.getCurrentTextColor());paint.setUnderlineText(true);}},start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if(found){view.setText(text);view.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());}
    }
    private static void open(Activity a,Uri uri){
        // Find browsers using a neutral URL, preventing verified App Links from swallowing the click.
        Intent probe=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> all=a.getPackageManager().queryIntentActivities(probe,0);
        ArrayList<Intent> browsers=new ArrayList<>();
        for(ResolveInfo r:all){if(r.activityInfo==null||a.getPackageName().equals(r.activityInfo.packageName))continue;browsers.add(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(r.activityInfo.packageName));}
        try{
            ResolveInfo def=a.getPackageManager().resolveActivity(probe,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            if(def!=null&&def.activityInfo!=null)for(Intent browser:browsers)if(def.activityInfo.packageName.equals(browser.getPackage())){a.startActivity(browser);return;}
            if(!browsers.isEmpty()){Intent chooser=Intent.createChooser(browsers.remove(0),"选择浏览器");chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,browsers.toArray(new Intent[0]));a.startActivity(chooser);return;}
        }catch(Exception ignored){}
        AppNotice.show(a, "未找到可用浏览器，请安装或启用浏览器");
    }
    private ChatLinks(){}
}
