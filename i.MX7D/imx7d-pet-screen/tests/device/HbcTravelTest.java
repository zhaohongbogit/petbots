package cn.aihongbo.voiceweather;

import android.app.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Android HTTP/JSON plus controller with substituted audio; all business responses are local fixtures. */
public final class HbcTravelTest extends Instrumentation {
    int checks;long now;
    static final String ID="00000000-0000-4000-8000-000000000001",APPROVAL="00000000-0000-4000-8000-000000000002";
    void check(boolean condition,String reason){checks++;if(!condition)throw new AssertionError(reason);}
    static JSONObject response(String status,String message)throws Exception{return new JSONObject().put("conversationId",ID).put("status",status).put("message",message);}
    static JSONObject order()throws Exception{return response("approval_required","").put("approvalId",APPROVAL).put("actions",new JSONArray().put(new JSONObject().put("toolName","create_order").put("arguments",new JSONObject().put("quoteId","test-quote-123").put("payMethod","TEST_PAY"))));}
    static final class FakeApi implements HbcTravelSession.Api {
        int calls,approvals,sms;boolean fail,allowFixed;java.util.ArrayDeque<JSONObject> responses=new java.util.ArrayDeque<>();String lastPath,lastToken;JSONObject lastBody,next;
        public JSONObject post(String path,JSONObject body,String token)throws Exception{calls++;lastPath=path;lastBody=body;lastToken=token;if(path.contains("approvals"))approvals++;if(body.optString("message").matches("1[0-9]{10}"))sms++;if(fail)throw new IOException("simulated timeout");if(!responses.isEmpty())return responses.removeFirst();if(next==null)throw new AssertionError("no response fixture");return next;}
        public void close(){}public boolean allowsFixedLogin(String base){return allowFixed;}
    }
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();try{
        check(HbcTravelSession.starts("我想预订东京三日包车"),"travel route");check(!HbcTravelSession.starts("上海明天天气怎么样"),"weather isolated");
        check(HbcTravelSession.digits("我的手机号是一三八零零零零零零零零").equals("13800000000"),"Chinese digit phone");
        check(HbcTravelSession.digits("验证码是幺二三四五六").equals("123456"),"Chinese captcha");
        check(HbcTravelSession.digits("不是一二三四").isEmpty(),"cannot extract digits out of refusal");
        FakeApi api=new FakeApi();HbcTravelSession s=new HbcTravelSession(api,()->now);
        api.next=response("authentication_required","");s.respond("我要包车");check(api.calls==1&&s.sensitive(),"login challenge");
        HbcTravelSession.Reply p=s.respond("13800000000");check(api.sms==0&&p.text.contains("确认发送验证码"),"phone not automatically sent");
        s.respond("是的");check(api.sms==0,"ambiguous SMS consent");
        s.respond("确认发送验证码");check(api.sms==0,"SMS gate requires completed readback");
        s.inputReady();api.next=response("captcha_required","");s.respond("确认发送验证码");check(api.sms==1&&api.lastBody.getString("conversationId").equals(ID),"SMS explicit bound conversation");
        api.next=response("authenticated","").put("sessionToken","synthetic_test_session_0123456789");s.respond("一二三四五六");check(api.lastBody.getString("message").equals("123456"),"captcha exact normalized");
        api.next=response("completed","请补充出行人数和行李");s.respond("东京三天包车");check(!api.lastBody.has("conversationId")&&api.lastToken!=null&&!s.sensitive(),"auth starts business conversation with bearer");
        api.next=order();p=s.respond("选择该报价");check(p.text.contains("test-quote-123")&&p.text.contains("TEST_PAY")&&api.approvals==0,"approval parameters read back");
        s.inputReady();s.respond("是的");check(api.approvals==0,"no broad yes approval");
        s.suspend();s.respond("确认创建测试订单");check(api.approvals==0,"background disarms approval");
        s.inputReady();now=21000;s.respond("确认创建测试订单");check(api.approvals==0,"approval voice window expires");
        s.inputReady();api.next=response("completed","模拟订单已创建");s.respond("确认创建测试订单");check(api.approvals==1&&api.lastPath.endsWith(APPROVAL)&&api.lastBody.getString("decision").equals("approve"),"one explicit bound approval");
        api.next=order();s.respond("再次报价");s.inputReady();api.fail=true;s.respond("确认创建测试订单");int calls=api.calls;
        s.respond("确认创建测试订单");check(api.calls==calls&&api.approvals==2,"no retry after uncertain write");
        api.fail=false;s.respond("新建旅行对话");api.next=response("authentication_required","");s.respond("包车");check(api.lastToken==null,"new login clears identity");
        FakeApi other=new FakeApi();other.next=response("approval_required","").put("approvalId",APPROVAL).put("actions",new JSONArray().put(new JSONObject().put("toolName","initiate_payment").put("arguments",new JSONObject())));
        HbcTravelSession unsupported=new HbcTravelSession(other,()->now);unsupported.respond("我要包车");unsupported.inputReady();unsupported.respond("确认创建测试订单");check(other.approvals==0,"payment not permitted");
        other.next=response("completed","拒绝成功");unsupported.inputReady();unsupported.respond("拒绝本次操作");check(other.approvals==1&&other.lastBody.getString("decision").equals("reject"),"explicit reject");
        for(String bad:new String[]{"http://example.com","https://user:pass@example.com","https://example.com/?token=x","https://example.com/#x","file:///tmp/a"}){boolean rejected=false;try{HbcAgentClient.validateBase(bad);}catch(IllegalArgumentException e){rejected=true;}check(rejected,"endpoint rejected");}
        check(HbcAgentClient.validateBase("http://192.168.211.224:8788/").equals("http://192.168.211.224:8788"),"LAN test endpoint");
        fixedLogin();HbcQuoteFlowTest.main(new String[0]);httpContract();controllerFlow();
        result.putString("stream","PASS "+checks+" checks: local HTTP, login/SMS/order voice gates, isolated session, fixed test login, no retry, weather regression and controller audio substitution. No real SMS/order/payment/audio.");finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("stream","FAIL "+e.getClass().getSimpleName()+": "+e.getMessage());finish(Activity.RESULT_CANCELED,result);}}
    void fixedLogin()throws Exception{
        FakeApi api=new FakeApi();api.allowFixed=true;
        HbcFixedLogin fixed=new HbcFixedLogin("13800000000","9876","http://127.0.0.1:1");
        HbcTravelSession s=new HbcTravelSession(api,()->now,fixed);
        api.next=response("authentication_required","");HbcTravelSession.Reply first=s.respond("包车");
        check(first.text.contains("固定账号")&&api.calls==1&&api.sms==0,"fixed test account readback, no auto SMS");
        s.respond("是的");check(api.sms==0,"fixed login still requires explicit SMS approval");
        s.inputReady();api.responses.add(response("captcha_required",""));api.responses.add(response("authenticated","").put("sessionToken","synthetic_fixed_session_0123456789"));
        HbcTravelSession.Reply logged=s.respond("确认发送验证码");
        check(api.calls==3&&api.sms==1&&api.lastBody.getString("message").equals("9876")&&logged.text.contains("登录成功"),"fixed captcha only after SMS approval");
        check(!logged.text.contains("9876"),"fixed captcha never read aloud");
        FakeApi wrong=new FakeApi();wrong.next=response("authentication_required","");HbcTravelSession separate=new HbcTravelSession(wrong,()->now,fixed);
        check(!separate.respond("包车").text.contains("固定账号"),"fixed credentials not used at another endpoint");
    }
    void httpContract()throws Exception{
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            ExecutorService worker=Executors.newSingleThreadExecutor();Future<String> received=worker.submit(()->{
                try(Socket socket=server.accept()){
                    BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),"UTF-8"));String first=reader.readLine(),line;int length=0;String auth="";
                    while(!(line=reader.readLine()).isEmpty()){if(line.toLowerCase(Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(15).trim());if(line.startsWith("Authorization:"))auth=line;}
                    char[] body=new char[length];int n=0;while(n<length){int k=reader.read(body,n,length-n);if(k<0)throw new EOFException();n+=k;}
                    byte[] out=response("completed","mock reply").toString().getBytes("UTF-8");OutputStream stream=socket.getOutputStream();stream.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "+out.length+"\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));stream.write(out);stream.flush();return first+"\n"+auth+"\n"+new String(body);
                }
            });
            HbcAgentClient c=new HbcAgentClient("http://127.0.0.1:"+server.getLocalPort());JSONObject r=c.post("/api/chat",new JSONObject().put("message","hello"),"synthetic_session");String request=received.get(5,TimeUnit.SECONDS);
            check(r.getString("message").equals("mock reply"),"Android HTTP JSON response");check(request.startsWith("POST /api/chat HTTP/1.1")&&request.contains("Bearer synthetic_session")&&request.contains("\"message\":\"hello\""),"POST JSON bearer contract");c.close();worker.shutdownNow();
        }
    }
    void controllerFlow()throws Exception{
        FakeApi api=new FakeApi();api.next=response("authentication_required","");
        AluoIntegrationTest.FakeInput in=new AluoIntegrationTest.FakeInput();TestOutput out=new TestOutput();
        VoiceController[] controller={null};Activity[] activity={null};
        VoiceController.factory=(host,listener)->{activity[0]=host;controller[0]=new VoiceController(host,listener,in,out,(city,day)->{throw new AssertionError("weather forbidden");},new HbcTravelSession(api,()->now));return controller[0];};
        try{
            android.content.Intent intent=new android.content.Intent();intent.setClassName("com.zhb.petscreen","com.zhb.petscreen.ChatActivity");intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);activity[0]=startActivitySync(intent);
            await(()->in.active&&in.mode==BoardSpeech.Mode.WAKE);
            runOnMainSync(()->in.listener.wake("","",SystemClock.elapsedRealtime()));await(()->out.pending!=null);finishSpeech(out);await(()->in.active&&in.mode==BoardSpeech.Mode.COMMAND);
            runOnMainSync(()->in.listener.result("上海明天天气怎么样",SystemClock.elapsedRealtime()));
            await(()->out.pending!=null&&out.last.contains("需要帮主人查询上海明天"));finishSpeech(out);await(()->in.active&&in.mode==BoardSpeech.Mode.CONFIRM);
            check(api.calls==0,"weather does not enter travel API");
            runOnMainSync(()->in.listener.result("不用",SystemClock.elapsedRealtime()));await(()->in.active&&in.mode==BoardSpeech.Mode.WAKE);
            runOnMainSync(()->in.listener.wake("","",SystemClock.elapsedRealtime()));await(()->out.pending!=null);finishSpeech(out);await(()->in.active&&in.mode==BoardSpeech.Mode.COMMAND);
            runOnMainSync(()->in.listener.result("我要东京包车",SystemClock.elapsedRealtime()));await(()->out.pending!=null&&out.last.contains("测试环境"));
            check(!in.active&&api.calls==1,"travel request uses worker, capture stops during TTS");
            check(caption(activity[0].getWindow().getDecorView()).equals("我要东京包车"),"recognized text restored after processing");
            finishAll(out,in);check(in.mode==BoardSpeech.Mode.COMMAND,"travel multi-turn resumes without wake");
            int starts=in.starts;runOnMainSync(()->in.listener.ended());await(()->in.active&&in.mode==BoardSpeech.Mode.COMMAND&&in.starts>starts);check(true,"travel silence keeps command mode");
            runOnMainSync(()->in.listener.result("13800000000",SystemClock.elapsedRealtime()));await(()->out.pending!=null);check(api.sms==0,"controller SMS readback before send");
            out.success=false;finishSpeech(out);await(()->in.active&&in.mode==BoardSpeech.Mode.WAKE);
            check(api.sms==0,"failed readback cannot arm SMS");
            runOnMainSync(()->controller[0].pause());check(!in.active,"pause stops microphone");
        }finally{if(controller[0]!=null)runOnMainSync(()->controller[0].close());if(activity[0]!=null)runOnMainSync(()->activity[0].finish());VoiceController.factory=VoiceController::createDefault;}
    }
    static final class TestOutput implements VoiceController.Output {
        volatile String last="";volatile Runnable pending;volatile boolean success=true;
        public void initialize(java.util.function.Consumer<Boolean> ready){ready.accept(true);}
        public boolean speak(String text,Runnable done){last=text;pending=done;return true;}
        public boolean playbackSucceeded(){return success;}
        public void stop(){pending=null;}public void close(){stop();}
    }
    static String caption(android.view.View view){
        if(view instanceof android.widget.TextView&&"实时语音转写".contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return ((android.widget.TextView)view).getText().toString();
        if(view instanceof android.view.ViewGroup)for(int i=0;i<((android.view.ViewGroup)view).getChildCount();i++){String text=caption(((android.view.ViewGroup)view).getChildAt(i));if(text!=null)return text;}
        return null;
    }
    interface Condition{boolean yes();}
    void await(Condition condition)throws Exception{long deadline=SystemClock.elapsedRealtime()+7000;while(!condition.yes()){if(SystemClock.elapsedRealtime()>deadline)throw new AssertionError("controller timeout");Thread.sleep(30);}}
    void finishSpeech(TestOutput output){runOnMainSync(()->{Runnable done=output.pending;output.pending=null;done.run();});}
    void finishAll(TestOutput out,AluoIntegrationTest.FakeInput in)throws Exception{for(int i=0;i<20&&!in.active;i++){await(()->out.pending!=null||in.active);if(out.pending!=null){finishSpeech(out);Thread.sleep(200);}}await(()->in.active);}
}
