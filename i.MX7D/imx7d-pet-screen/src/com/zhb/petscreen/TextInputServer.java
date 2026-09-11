package com.zhb.petscreen;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Authenticated, foreground-only Chinese text input from the paired phone. */
final class TextInputServer {
    final ChatActivity app;final String key,debugKey;
    volatile boolean open=true;volatile ServerSocket server;volatile Socket client;
    TextInputServer(ChatActivity app,String key,String debugKey){this.app=app;this.key=key;this.debugKey=debugKey;}
    void start(){new Thread(()->{
        try(ServerSocket ss=new ServerSocket()){
            String ip=null;NetworkInterface wifi=NetworkInterface.getByName("wlan0");
            if(wifi!=null)for(InetAddress a:Collections.list(wifi.getInetAddresses()))if(a instanceof Inet4Address)ip=a.getHostAddress();
            if(ip==null)throw new IOException("no_wifi");ss.setReuseAddress(true);ss.bind(new InetSocketAddress(ip,8766));server=ss;
            android.util.Log.i("PetChat","phone_input_ready");
            while(open){try(Socket s=ss.accept()){client=s;s.setSoTimeout(5000);handle(s);}catch(Exception ignored){}finally{client=null;}}
        }catch(Exception e){if(open)android.util.Log.i("PetChat","phone_input_error="+e.getClass().getSimpleName());}
    },"pet-phone-input").start();}
    String line(InputStream in)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(int c;(c=in.read())!=-1;){if(c==10)return b.toString("US-ASCII").replace("\r","");if(b.size()>2048)throw new IOException();b.write(c);}throw new EOFException();}
    void handle(Socket s)throws Exception{
        InputStream in=new BufferedInputStream(s.getInputStream());String[] first=line(in).split(" ");if(first.length!=3)return;
        Map<String,String> h=new HashMap<>();int total=0;for(String l;!(l=line(in)).isEmpty();){total+=l.length();if(total>8192)return;int n=l.indexOf(':');if(n>0)h.put(l.substring(0,n).toLowerCase(Locale.ROOT),l.substring(n+1).trim());}
        String supplied=h.getOrDefault("x-pet-key","");
        boolean primary=secureEquals(key,supplied);
        boolean debug=secureEquals(debugKey,supplied);
        if(!primary&&!debug){reply(s,403,new JSONObject());return;}
        if(first[0].equals("GET")&&first[1].startsWith("/status?id=")){
            if(!app.remoteId.equals(first[1].substring(11))){reply(s,404,new JSONObject());return;}
            reply(s,200,new JSONObject().put("state",app.remoteState).put("reply",app.remoteReply).put("error",app.remoteError).put("homework",app.remoteHomework));return;
        }
        boolean homework=first[1].equals("/homework"),plan=first[1].equals("/homework/plan");
        if(!first[0].equals("POST")||!(first[1].equals("/input")||homework||plan)){reply(s,404,new JSONObject());return;}
        int n=Integer.parseInt(h.getOrDefault("content-length","0"));if(n<1||n>(homework?1500000:plan?16000:4096)||h.containsKey("transfer-encoding")){reply(s,413,new JSONObject());return;}
        byte[] bytes=new byte[n];int at=0;while(at<n){int read=in.read(bytes,at,n-at);if(read<0)throw new EOFException();at+=read;}
        JSONObject data=new JSONObject(new String(bytes,"UTF-8"));String text=data.optString("text",homework?"请识别截图中的作业":plan?"规划作业":"").trim(),id=data.optString("id","");
        if(text.isEmpty()||text.length()>500||!id.matches("[a-zA-Z0-9-]{16,64}")){reply(s,400,new JSONObject());return;}
        String image=homework?data.optString("image",""):null;
        if(homework&&!HomeworkSupport.validImage(image)){reply(s,400,new JSONObject());return;}
        if(plan){try{HomeworkSupport.plan(data);}catch(Exception e){reply(s,400,new JSONObject());return;}}
        if(id.equals(app.remoteId)){reply(s,200,new JSONObject().put("id",id));return;}
        CountDownLatch latch=new CountDownLatch(1);AtomicBoolean valid=new AtomicBoolean(true),accepted=new AtomicBoolean();
        app.main.post(()->{if(open&&valid.get())accepted.set(plan?app.beginPlan(data,id):app.begin(text,id,image));latch.countDown();});
        if(!latch.await(5,TimeUnit.SECONDS)){valid.set(false);reply(s,503,new JSONObject());return;}
        reply(s,accepted.get()?202:409,new JSONObject().put("id",id));
    }
    boolean secureEquals(String expected,String supplied)throws UnsupportedEncodingException{
        return expected.length()>=24&&java.security.MessageDigest.isEqual(expected.getBytes("UTF-8"),supplied.getBytes("UTF-8"));
    }
    void reply(Socket s,int code,JSONObject data)throws IOException{byte[] b=data.toString().getBytes("UTF-8");OutputStream out=s.getOutputStream();out.write(("HTTP/1.1 "+code+" Result\r\nContent-Type: application/json\r\nContent-Length: "+b.length+"\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));out.write(b);out.flush();}
    void close(){open=false;try{if(server!=null)server.close();if(client!=null)client.close();}catch(IOException ignored){}}
}
