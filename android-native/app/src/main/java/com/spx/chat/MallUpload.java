package com.spx.chat;
import android.app.Activity;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
final class MallUpload {
    static void upload(Activity a,String endpoint,Uri uri,ApiClient.Callback cb){
        final String requestEndpoint=ApiClient.endpoint(a),requestToken=ApiClient.token(a),requestServer=ApiClient.baseUrl(a);
        new Thread(()->{File file=null;try{
        BitmapFactory.Options o=new BitmapFactory.Options();o.inJustDecodeBounds=true;try(InputStream in=a.getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,o);}
        if(o.outWidth<1||o.outHeight<1)throw new IOException("无法读取图片");
        o.inSampleSize=1;while(o.outWidth/o.inSampleSize>2048||o.outHeight/o.inSampleSize>12000||((long)o.outWidth*o.outHeight)/(o.inSampleSize*o.inSampleSize)>12000000)o.inSampleSize*=2;
        o.inJustDecodeBounds=false;Bitmap b;try(InputStream in=a.getContentResolver().openInputStream(uri)){b=BitmapFactory.decodeStream(in,null,o);}if(b==null)throw new IOException("无法解码图片");
        if(android.os.Build.VERSION.SDK_INT>=24){
            int orientation=1;try(InputStream in=a.getContentResolver().openInputStream(uri)){orientation=new android.media.ExifInterface(in).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);}catch(Exception ignored){}
            Matrix m=new Matrix();switch(orientation){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setRotate(180);m.postScale(-1,1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(-90);m.postScale(-1,1);break;case 8:m.setRotate(-90);break;}
            if(!m.isIdentity()){Bitmap rotated=Bitmap.createBitmap(b,0,0,b.getWidth(),b.getHeight(),m,true);if(rotated!=b)b.recycle();b=rotated;}
        }
        file=File.createTempFile("mall-upload-",".jpg",a.getCacheDir());
        try{for(int quality:new int[]{90,78,64,50}){try(FileOutputStream out=new FileOutputStream(file)){if(!b.compress(Bitmap.CompressFormat.JPEG,quality,out))throw new IOException("无法压缩图片");}if(file.length()<5*1024*1024)break;}}finally{b.recycle();}
        if(file.length()>5*1024*1024)throw new IOException("图片过大，请裁剪后重试");File upload=file;
        if(a.isFinishing()||a.isDestroyed()||!requestServer.equals(ApiClient.baseUrl(a))||!requestToken.equals(ApiClient.token(a)))throw new IOException("账号或节点已切换，请重新选择图片");
        ApiClient.uploadCaptured(a,endpoint,Uri.fromFile(file),requestEndpoint,requestToken,new ApiClient.Callback(){public void onSuccess(Object d){upload.delete();cb.onSuccess(d);}public void onError(String s){upload.delete();cb.onError(s);}});
    }catch(Exception|OutOfMemoryError e){if(file!=null)file.delete();
        String message=e instanceof SecurityException?"相册未授予图片读取权限，请重新从相册选择"
            :e instanceof FileNotFoundException?"图片无法读取，请先将云端照片下载到手机再选择"
            :e instanceof OutOfMemoryError?"图片尺寸过大，请裁剪或缩小后重试"
            :e instanceof IOException?e.getMessage():"图片处理失败，请重新选择图片";
        a.runOnUiThread(()->cb.onError(message==null?"图片处理失败，请重新选择图片":message));}},"mall-upload").start();}
}
