package com.spx.chat;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** Uses isolated preferences and a loopback HTTP server. Never submits real payments, messages or calls. */
public final class AccountReliabilityInstrumentation extends Instrumentation {
    private int checks;
    private final String prefix="account-review-"+UUID.randomUUID()+"-";
    private final Set<String> stores=new HashSet<>();
    private Context isolated;
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static final class Fake implements FinancialRequests.Transport {
        String action;JSONObject body;ApiClient.Callback callback;int calls;
        public void post(String action,JSONObject body,ApiClient.Callback callback){this.action=action;this.body=body;this.callback=callback;calls++;}
    }
    private static final class Result implements FinancialRequests.Callback {
        int confirmed,cancelled,rejected,uncertain;
        public void confirmed(JSONObject value){confirmed++;}
        public void notSubmitted(){cancelled++;}
        public void rejected(String value){rejected++;}
        public void uncertain(String value){uncertain++;}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        Context target=getTargetContext();
        isolated=new ContextWrapper(target){@Override public SharedPreferences getSharedPreferences(String name,int mode){stores.add(name);return super.getSharedPreferences(prefix+name,mode);}};
        financial();sessions();networkAndCache();
        result.putString("stream","SUCCESS "+checks+" account/financial/network/cache checks; loopback only; no business writes\n");finish(Activity.RESULT_OK,result);
    }catch(Throwable error){result.putString("stream","FAIL "+android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result);}
    finally{if(isolated!=null)for(String name:new ArrayList<>(stores))isolated.getSharedPreferences(name,0).edit().clear().commit();}}
    private void financial()throws Exception {
        Fake transport=new Fake();Result result=new Result();
        FinancialRequests requests=new FinancialRequests(isolated,101,transport);
        JSONObject payload=new JSONObject().put("symbol","USDT").put("chain","BSC").put("amount","10").put("address","0x0000000000000000000000000000000000000001");
        requests.submit("wallet.withdraw",payload,result);
        String id=transport.body.getString("client_request_id");
        check(id.matches("[a-zA-Z0-9_-]{8,64}"),"valid request ID");
        check(requests.pending("wallet.withdraw"),"persisted before transport");
        payload.put("amount","999");check(transport.body.optString("amount").equals("10"),"immutable submitted payload");
        requests.submit("wallet.withdraw",payload,result);check(transport.calls==1&&result.uncertain==1,"double click cannot submit another request");
        FinancialRequests restarted=new FinancialRequests(isolated,101,transport);
        check(restarted.pending("wallet.withdraw"),"pending survives controller recreation");
        check(!new FinancialRequests(isolated,102,transport).pending("wallet.withdraw"),"account isolation");
        String originalBase=ApiClient.baseUrl(isolated);ApiClient.saveBaseUrl(isolated,"https://other.invalid");
        check(!new FinancialRequests(isolated,101,transport).pending("wallet.withdraw"),"server isolation");ApiClient.saveBaseUrl(isolated,originalBase);
        transport.callback.onError("timeout");check(requests.pending("wallet.withdraw"),"timeout keeps pending ID");
        ApiClient.saveToken(isolated,"relogin-token");check(new FinancialRequests(isolated,101,transport).pending("wallet.withdraw"),"login token rotation retains pending ID");
        restarted.resolve("wallet.withdraw",result);
        check(transport.action.equals("financial.resolve")&&transport.body.optString("operation").equals("wallet.withdraw"),"resolve uses safe transaction endpoint");
        check(transport.body.optString("client_request_id").equals(id)&&transport.body.optString("amount").equals("10"),"resolve reuses original ID and payload");
        transport.callback.onSuccess(new JSONObject().put("transaction_id",7).put("client_request_id","wrong-id"));
        check(requests.pending("wallet.withdraw"),"unmatched receipt cannot clear pending");
        restarted.resolve("wallet.withdraw",result);transport.callback.onSuccess(new JSONObject().put("transaction_id",7).put("client_request_id",id));
        check(result.confirmed==1&&!requests.pending("wallet.withdraw"),"matching receipt resolves original withdrawal");
        requests.submit("staking.create",new JSONObject().put("plan_id",1).put("amount","100000"),result);String stakeId=transport.body.getString("client_request_id");
        transport.callback.onHttpError(500,"busy");check(requests.pending("staking.create"),"server failure retains staking request");
        requests.resolve("staking.create",result);transport.callback.onSuccess(new JSONObject().put("cancelled",true).put("client_request_id",stakeId));
        check(result.cancelled==1&&!requests.pending("staking.create"),"server tombstone conclusively allows a new request");
        requests.submit("staking.create",new JSONObject().put("plan_id",1).put("amount","100000"),result);
        check(!transport.body.optString("client_request_id").equals(stakeId),"new request ID only after resolution");
        transport.callback.onHttpError(422,"invalid amount");check(!requests.pending("staking.create")&&result.rejected==1,"explicit validation rejection releases pending");
        requests.submit("staking.create",new JSONObject().put("plan_id",1).put("amount","100000"),result);
        transport.callback.onHttpError(409,"conflict");check(requests.pending("staking.create"),"conflict cannot discard an unknown committed result");
        requests.resolve("staking.create",result);transport.callback.onSuccess(new JSONObject().put("id",9).put("client_request_id",transport.body.optString("client_request_id")));
        check(!requests.pending("staking.create")&&result.confirmed==2,"staking receipt confirmed");
    }
    private void sessions()throws Exception {
        ApiClient.saveToken(isolated,"one");ApiClient.SessionStamp old=new ApiClient.SessionStamp(isolated);
        check(old.valid(isolated),"current session is valid");
        ApiClient.saveToken(isolated,"two");check(!old.valid(isolated),"old token callback rejected");
        ApiClient.saveToken(isolated,"one");check(!old.valid(isolated),"same token after account rotation does not revive old callback");
        ApiClient.SessionStamp current=new ApiClient.SessionStamp(isolated);ApiClient.saveToken(isolated,"one");check(current.valid(isolated),"saving unchanged token preserves requests");
        check(!ApiClient.mayRetryRead("wallet.withdraw")&&!ApiClient.mayRetryRead("staking.create"),"financial writes never auto retry");
        check(ApiClient.mayRetryRead("contacts")&&ApiClient.mayRetryRead("wallet"),"safe queries support bounded retry");
        check(!ApiClient.networkError(isolated,new java.net.SocketTimeoutException("raw-timeout")).contains("raw-timeout"),"friendly timeout hides raw exception");
        check(!AccountScope.id("https://a",1).equals(AccountScope.id("https://a",2)),"profile account namespace");
        check(!AccountScope.id("https://a",1).equals(AccountScope.id("https://b",1)),"profile server namespace");
        Response receiver=new Response();java.lang.reflect.Method wrapper=ApiClient.class.getDeclaredMethod("sessionCallback",Context.class,String.class,ApiClient.Callback.class);wrapper.setAccessible(true);
        ApiClient.Callback upload=(ApiClient.Callback)wrapper.invoke(null,isolated,ApiClient.token(isolated),receiver);
        ApiClient.saveToken(isolated,"upload-other-account");upload.onSuccess(new JSONObject().put("url","old-avatar"));
        check(receiver.done.getCount()==1,"old upload result cannot overwrite another account avatar");
    }
    private static final class Mock implements AutoCloseable {
        final ServerSocket server=new ServerSocket(0,20,java.net.InetAddress.getByName("127.0.0.1"));
        final AtomicInteger requests=new AtomicInteger();final CountDownLatch received=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile boolean running=true,delay;volatile int failCount;volatile String json="{\"code\":0,\"data\":[{\"symbol\":\"USDT\",\"balance\":\"8\"}]}";
        Mock()throws Exception{new Thread(()->{while(running)try(Socket socket=server.accept()){
            socket.setSoTimeout(3000);InputStream input=socket.getInputStream();ByteArrayOutputStream headers=new ByteArrayOutputStream();int c,last=0;
            while((c=input.read())!=-1){headers.write(c);last=(last<<8)|c;if(last==0x0d0a0d0a)break;}
            String text=headers.toString("UTF-8");java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)Content-Length: (\\d+)").matcher(text);int length=m.find()?Integer.parseInt(m.group(1)):0;
            while(length-->0&&input.read()!=-1){}int count=requests.incrementAndGet();received.countDown();if(delay)release.await(5,TimeUnit.SECONDS);
            int status=count<=failCount?503:200;byte[] body=(status==200?json:"busy").getBytes("UTF-8");
            OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" "+(status==200?"OK":"Busy")+"\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes("UTF-8"));out.write(body);out.flush();
        }catch(Exception error){if(running)android.util.Log.w("AccountTest",error.getClass().getSimpleName());}},"account-loopback").start();}
        String base(){return "http://127.0.0.1:"+server.getLocalPort();}
        public void close()throws Exception{running=false;release.countDown();server.close();}
    }
    private static final class Response implements ApiClient.Callback {
        final CountDownLatch done=new CountDownLatch(1);Object value;String error;int status;
        public void onSuccess(Object value){this.value=value;done.countDown();}
        public void onError(String error){this.error=error;done.countDown();}
        public void onHttpError(int status,String error){this.status=status;onError(error);}
    }
    private void await(CountDownLatch latch,String message)throws Exception{check(latch.await(8,TimeUnit.SECONDS),message);}
    private void networkAndCache()throws Exception {
        try(Mock server=new Mock()){
            ApiClient.saveBaseUrl(isolated,server.base());server.failCount=1;
            Response read=new Response();ApiClient.get(isolated,"wallet",null,read);await(read.done,"GET completes after transient failure");
            check(read.value instanceof JSONArray&&server.requests.get()==2,"read retries exactly once");
            server.failCount=99;Response write=new Response();ApiClient.post(isolated,"staking.create",new JSONObject().put("amount","100000"),write);await(write.done,"POST returns its first failure");
            check(server.requests.get()==3&&write.status==503,"POST not replayed and HTML service failure classified");
            server.failCount=0;server.json="{\"code\":0,\"data\":[]}";
            CountDownLatch cached=new CountDownLatch(1);AtomicInteger views=new AtomicInteger();
            AccountPageCache.get(isolated,201,"wallet",true,new ApiClient.Callback(){public void onSuccess(Object data){views.incrementAndGet();cached.countDown();}public void onError(String e){cached.countDown();}},(c,e)->{});
            await(cached,"snapshot initially fetched");int requestCount=server.requests.get();
            CountDownLatch fresh=new CountDownLatch(1);AccountPageCache.get(isolated,201,"wallet",false,new ApiClient.Callback(){public void onSuccess(Object data){fresh.countDown();}public void onError(String e){throw new AssertionError(e);}},(c,e)->{});
            await(fresh,"fresh cached snapshot displayed immediately");check(server.requests.get()==requestCount,"fresh snapshot avoids network request");
            CountDownLatch other=new CountDownLatch(1);AccountPageCache.get(isolated,202,"wallet",false,new ApiClient.Callback(){public void onSuccess(Object data){other.countDown();}public void onError(String e){other.countDown();}},(c,e)->{});
            await(other,"other account gets independent snapshot");check(server.requests.get()==requestCount+1,"another account cannot reuse first cache");
            AccountPageCache.invalidateForMutation(isolated,"staking.create");
            CountDownLatch invalidated=new CountDownLatch(1);AccountPageCache.get(isolated,201,"wallet",false,new ApiClient.Callback(){public void onSuccess(Object data){invalidated.countDown();}public void onError(String e){invalidated.countDown();}},(c,e)->{});
            await(invalidated,"balance refreshed after mutation");check(server.requests.get()==requestCount+2,"financial mutation invalidates display cache");
        }
        try(Mock server=new Mock()){
            ApiClient.saveBaseUrl(isolated,server.base());
            CountDownLatch initial=new CountDownLatch(1);
            AccountPageCache.get(isolated,301,"wallet",true,new ApiClient.Callback(){public void onSuccess(Object d){initial.countDown();}public void onError(String e){initial.countDown();}},(c,e)->{});
            await(initial,"offline fallback snapshot prepared");server.failCount=99;
            CountDownLatch failedRefresh=new CountDownLatch(1);AtomicInteger shown=new AtomicInteger(),errors=new AtomicInteger();boolean[] kept={false};
            AccountPageCache.get(isolated,301,"wallet",true,new ApiClient.Callback(){public void onSuccess(Object d){shown.incrementAndGet();}public void onError(String e){errors.incrementAndGet();}},(cached,error)->{if(!error.isEmpty()){kept[0]=cached;failedRefresh.countDown();}});
            await(failedRefresh,"refresh failure reports cache state");check(shown.get()==1&&errors.get()==0&&kept[0],"failed refresh keeps previously shown balance and reports stale state");
        }
        try(Mock server=new Mock()){
            ApiClient.saveBaseUrl(isolated,server.base());server.delay=true;AtomicInteger rendered=new AtomicInteger();CountDownLatch ignored=new CountDownLatch(1);
            AccountPageCache.get(isolated,401,"wallet",true,new ApiClient.Callback(){public void onSuccess(Object d){rendered.incrementAndGet();}public void onError(String e){ignored.countDown();}},(cached,error)->{if(!error.isEmpty())ignored.countDown();});
            await(server.received,"cache refresh in flight");AccountPageCache.invalidateForMutation(isolated,"redpackets.claim");server.release.countDown();
            await(ignored,"pre-mutation response rejected");check(rendered.get()==0,"old balance cannot overwrite a completed mutation");
            check(isolated.getSharedPreferences("spx_account_pages_v1",0).getAll().isEmpty(),"invalidated cache cannot be resurrected by late response");
        }
        try(Mock server=new Mock()){
            ApiClient.saveBaseUrl(isolated,server.base());ApiClient.saveToken(isolated,"before-switch");server.delay=true;
            Response stale=new Response();ApiClient.get(isolated,"wallet",null,stale);await(server.received,"in-flight request reached loopback");
            ApiClient.saveToken(isolated,"after-switch");server.release.countDown();
            check(!stale.done.await(800,TimeUnit.MILLISECONDS),"late response cannot update next account");
        }
    }
}
