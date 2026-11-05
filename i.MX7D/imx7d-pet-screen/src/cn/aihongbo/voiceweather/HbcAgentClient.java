package cn.aihongbo.voiceweather;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Client of the existing customer API. No HBC business keys, upstream calls or retries. */
final class HbcAgentClient implements HbcTravelSession.Api {
    private final String base;
    private volatile HttpURLConnection active;
    private volatile boolean closed;
    static HbcAgentClient from(android.content.Context context){
        String base="";java.io.File file=new java.io.File(context.getFilesDir(),"hbc-agent.json");
        try{if(file.isFile()&&file.length()<=4096){byte[] b=new byte[(int)file.length()];try(java.io.DataInputStream in=new java.io.DataInputStream(new java.io.FileInputStream(file))){in.readFully(b);}base=new JSONObject(new String(b,StandardCharsets.UTF_8)).getString("baseUrl");}}catch(Exception invalid){base="";}
        try{return new HbcAgentClient(base);}catch(IllegalArgumentException invalid){return new HbcAgentClient("");}
    }
    public boolean allowsFixedLogin(String expectedBase){return base.equals(expectedBase);}
    public boolean configured(){return !base.isEmpty();}
    HbcAgentClient(String base){this.base=validateBase(base);}
    static String validateBase(String value){
        if(value==null||value.trim().isEmpty())return "";
        try{
            URI uri=new URI(value.trim());String host=uri.getHost(),scheme=uri.getScheme();
            if(host==null||uri.getUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!("https".equals(scheme)||"http".equals(scheme)))throw new Exception();
            if("http".equals(scheme)&&!localHost(host))throw new Exception();
            return uri.toASCIIString().replaceAll("/+$","");
        }catch(Exception invalid){throw new IllegalArgumentException("旅行服务须使用HTTPS，或测试机的局域网HTTP地址");}
    }
    private static boolean localHost(String host){
        if(host.equals("localhost")||host.equals("127.0.0.1")||host.equals("[::1]")||host.equals("::1"))return true;
        String[] p=host.split("\\.");if(p.length!=4)return false;
        int[] n=new int[4];try{for(int i=0;i<4;i++){if(!p[i].matches("[0-9]{1,3}"))return false;n[i]=Integer.parseInt(p[i]);if(n[i]>255)return false;}}catch(Exception e){return false;}
        return n[0]==10||(n[0]==192&&n[1]==168)||(n[0]==172&&n[1]>=16&&n[1]<=31);
    }
    public JSONObject post(String path,JSONObject body,String token)throws Exception{
        if(base.isEmpty())throw new IOException("NOT_CONFIGURED");
        if(closed)throw new IOException("CLOSED");
        HttpURLConnection c=(HttpURLConnection)new URL(base+path).openConnection();active=c;
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(180000);c.setRequestMethod("POST");c.setDoOutput(true);
            c.setRequestProperty("Content-Type","application/json; charset=utf-8");c.setRequestProperty("Accept","application/json");
            if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);
            byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(bytes.length);
            if(closed)throw new IOException("CLOSED");
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();
            if(status==401)throw new AuthenticationException();
            if(status<200||status>=300)throw new IOException("HTTP_"+status);
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>262144)throw new IOException("RESPONSE_LIMIT");out.write(b,0,n);}}
            return new JSONObject(new String(out.toByteArray(),StandardCharsets.UTF_8));
        }finally{c.disconnect();if(active==c)active=null;}
    }
    public void cancel(){HttpURLConnection c=active;if(c!=null)c.disconnect();}
    public void close(){closed=true;cancel();}
    static final class AuthenticationException extends IOException{}
}
