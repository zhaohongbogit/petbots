package cn.aihongbo.voiceweather;

import org.json.*;
import java.util.UUID;

/** One process-local customer conversation, with explicit voice gates before SMS and order creation. */
final class HbcTravelSession {
    interface Api {JSONObject post(String path,JSONObject body,String token)throws Exception;void close();default void cancel(){}default boolean configured(){return true;}default boolean allowsFixedLogin(String base){return false;}}
    static final class Reply {
        final String text;final boolean listen;
        Reply(String text,boolean listen){this.text=text;this.listen=listen;}
    }
    private final Api api;
    private final HbcFixedLogin fixed;
    private HbcVoiceQuote quote;
    private String selectedQuoteReview="";
    private final java.util.ArrayDeque<String> recentReplies=new java.util.ArrayDeque<>();
    private volatile boolean active,closed,sensitive;
    private String conversation,token,status="",phone,approval,review="";
    private volatile boolean armed;
    private boolean uncertain,orderSupported;
    private long armedAt;
    private final java.util.function.LongSupplier now;
    HbcTravelSession(Api api){this(api,()->android.os.SystemClock.elapsedRealtime());}
    HbcTravelSession(Api api,java.util.function.LongSupplier now){this(api,now,null);}
    HbcTravelSession(Api api,java.util.function.LongSupplier now,HbcFixedLogin fixed){this.api=api;this.now=now;this.fixed=fixed!=null&&api.allowsFixedLogin(fixed.agentBase)?fixed:null;}
    boolean active(){return active;}
    boolean sensitive(){return sensitive;}
    static boolean starts(String raw){return raw.matches("(?s).*(皇包车|旅行下单|旅行服务|预订用车|预定用车|包车|接机|送机|查订单|查一下订单|我的订单).*");}
    static String compact(String s){return s.replaceAll("[\\s\\p{P}\\p{Z}]","");}
    static String digits(String raw){
        String s=compact(raw).replaceFirst("^(我的)?(手机号(码)?(是)?|验证码(是)?)","");
        StringBuilder out=new StringBuilder();String zh="零一二三四五六七八九";
        for(char c:s.toCharArray()){int i=zh.indexOf(c);if(i>=0)out.append(i);else if(c=='幺')out.append('1');else if(c=='〇')out.append('0');else if(c>='0'&&c<='9')out.append(c);else return "";}
        return out.toString();
    }
    // Called only after playback has completed and microphone has actually become ready.
    void inputReady(){armed=(phone!=null||approval!=null)&&!uncertain;armedAt=now.getAsLong();}
    void suspend(){armed=false;api.cancel();}
    Reply respond(String raw){
        if(closed)return new Reply("旅行服务已关闭",false);
        String text=compact(raw);active=true;
        if(!api.configured()){active=false;return new Reply("旅行服务尚未连接，请先由设备管理员配置测试服务地址",false);}
        if(text.equals("退出旅行服务")||text.equals("结束旅行对话")){armed=false;active=false;return new Reply("已退出旅行对话",false);}
        if(text.equals("新建旅行对话")||text.equals("退出旅行登录")){
            conversation=null;token=null;status="";sensitive=false;phone=null;approval=null;armed=false;uncertain=false;review="";quote=null;selectedQuoteReview="";recentReplies.clear();
            return new Reply("已清除本机旅行登录。请说出新的旅行需求",true);
        }
        if(uncertain)return new Reply("上次请求结果不确定，不能重复提交。请先通过原客户端核对短信或最新订单；需要重新开始时说新建旅行对话",true);
        boolean sideEffect=false;
        try{
            if(phone!=null){
                if(text.equals("取消发送验证码")){phone=null;armed=false;return reply(fixed!=null?"已取消发送。继续登录请重新唤醒并说明旅行需求":"已取消发送，请重新提供手机号");}
                if(!text.equals("确认发送验证码"))return reply("请核对刚才的号码。发送请说确认发送验证码，取消请说取消发送验证码");
                if(!authorized())return reply("确认窗口已结束。请重新听完号码后再确认："+spelled(phone));
                String mobile=phone;phone=null;armed=false;sideEffect=true;
                Reply answer=receive(api.post("/api/chat",request(mobile),token));
                if(fixed!=null&&status.equals("captcha_required"))return receive(api.post("/api/chat",request(fixed.captcha),token));
                return answer;
            }
            if(approval!=null){
                if(text.equals("重听待办"))return reply(review);
                boolean reject=text.equals("拒绝本次操作");
                if(!reject&&!text.equals("确认创建测试订单"))return reply(review);
                if(!reject&&!orderSupported)return reply("本次包含板端尚不支持审批的操作。请说拒绝本次操作，或通过原客户端处理");
                if(!authorized())return reply(review);
                String id=approval;approval=null;armed=false;sideEffect=true;
                return receive(api.post("/api/approvals/"+id,new JSONObject().put("decision",reject?"reject":"approve"),token));
            }
            if(status.equals("authentication_required")){
                if(fixed!=null){phone=fixed.phone;return reply("本测试包固定使用已指定的账号。手机号"+spelled(phone)+"。发送登录短信请说确认发送验证码；取消请说取消发送验证码");}
                String d=digits(raw);
                if(!d.matches("1[0-9]{10}"))return reply("请逐位说出中国大陆十一位手机号；其他区号请使用原客户端登录");
                phone=d;sensitive=true;return reply("测试环境将向手机号"+spelled(d)+"发送登录短信，可能消耗短信次数。确认号码无误请说确认发送验证码；否则说取消发送验证码");
            }
            if(quote!=null){
                String page=quote.navigate(text);if(page!=null)return reply(page);
                String selection=quote.choose(text);
                if(selection!=null){if(selection.isEmpty())return reply(quote.page());selectedQuoteReview=quote.page();quote=null;return receive(api.post("/api/chat",request(selection),token));}
                // A new explicit request may update the itinerary or obtain a new quote.
                quote=null;
            }
            String message=raw;
            if(status.equals("captcha_required")){
                sideEffect=true;message=digits(raw);if(!message.matches("[0-9]{4,8}"))return reply("请逐位说出短信中的四到八位验证码");
            }
            return receive(api.post("/api/chat",request(message),token));
        }catch(HbcAgentClient.AuthenticationException expired){
            token=null;conversation=null;status="";phone=null;approval=null;armed=false;sensitive=false;
            return reply("旅行登录已失效，请重新说出旅行需求后登录");
        }catch(Exception failure){
            // A timeout can occur after SMS/order side effects. Never automatically repeat the request.
            armed=false;
            if(!sideEffect)return reply("旅行服务这次没有返回完整结果，尚未取得新的报价。请说重新报价，或补充上一项出行信息；不会自动提交订单。");
            uncertain=true;
            return reply("旅行服务请求未完成，结果可能不确定。不会自动重试，请先核对短信或最新订单。需要重新开始时说新建旅行对话");
        }
    }
    private boolean authorized(){boolean ok=armed&&now.getAsLong()-armedAt<=20000;armed=false;return ok;}
    private static String spelled(String digits){return digits.replaceAll("(.)","$1，");}
    private Reply reply(String text){return new Reply(text,true);}
    private JSONObject request(String message)throws JSONException{JSONObject b=new JSONObject().put("message",message).put("interactionMode","voice");if(conversation!=null)b.put("conversationId",conversation);return b;}
    private Reply receive(JSONObject r)throws Exception{
        if(closed)throw new Exception("CLOSED");
        String id=r.optString("conversationId","");UUID.fromString(id);
        String next=r.getString("status"),message=r.optString("message","");
        if(java.util.Arrays.asList("completed","authentication_required","captcha_required","authenticated","approval_required").contains(next))android.util.Log.i("AluoHbc","status="+next);
        if(message.length()>12000)throw new Exception("REPLY_TOO_LONG");
        if(next.equals("authenticated")){
            String value=r.getString("sessionToken");if(!value.matches("[A-Za-z0-9_-]{20,256}"))throw new Exception("SESSION");
            token=value;conversation=null;status=next;sensitive=false;recentReplies.clear();
            return reply(fixed!=null?"测试环境固定账号登录成功，请继续说明旅行需求":"测试环境登录成功。请继续说明旅行需求；手机号和验证码不在本机保存");
        }
        if(!next.equals("completed")&&!next.equals("authentication_required")&&!next.equals("captcha_required")&&!next.equals("approval_required"))throw new Exception("STATUS");
        conversation=id;status=next;sensitive=next.equals("authentication_required")||next.equals("captcha_required");
        if(sensitive){token=null;recentReplies.clear();}
        if(next.equals("approval_required")){
            approval=r.getString("approvalId");UUID.fromString(approval);JSONArray actions=r.getJSONArray("actions");
            orderSupported=false;review="";
            if(actions.length()==1){JSONObject action=actions.getJSONObject(0),args=action.optJSONObject("arguments");
                if(action.optString("toolName").equals("create_order")&&args!=null&&args.length()==2){
                    String quote=args.optString("quoteId",""),pay=args.optString("payMethod","");
                    if(quote.matches("[A-Za-z0-9_-]{1,100}")&&pay.matches("[A-Za-z0-9_:-]{1,60}")){
                        orderSupported=true;
                        StringBuilder context=new StringBuilder(selectedQuoteReview.isEmpty()?"":"已选择的车型参考报价："+selectedQuoteReview+"。 ");for(String prior:recentReplies)context.append(prior).append("。 ");
                        review="请核对最近的旅行回复："+context+"待确认：根据上述行程和报价创建一笔皇包车测试订单。报价编号"+quote+"，支付方式代码"+pay+"。这将创建测试订单，不会发起支付。请确认行程、联系人、车型和金额均已核对；同意请说确认创建测试订单，拒绝请说拒绝本次操作。重听请说重听待办";
                    }
                }
            }
            if(review.length()>6000)orderSupported=false;
            if(!orderSupported)review="服务返回待审批操作，本次操作类型或批次尚不支持板端审批，不会执行。请说拒绝本次操作；付款、取消、退款等请通过原客户端处理";
            return reply(review);
        }
        if(next.equals("authentication_required")&&fixed!=null){phone=fixed.phone;return reply("测试环境使用已指定的固定账号。将向手机号"+spelled(phone)+"发送登录短信，可能消耗短信次数，并使用内置测试验证码登录。确认请说确认发送验证码，取消请说取消发送验证码");}
        if(next.equals("authentication_required"))return reply("皇包车旅行服务使用测试环境，需要登录。请逐位说出中国大陆手机号；我会先回读号码，确认后才发送验证码");
        if(next.equals("captcha_required"))return reply("请查收登录短信，并逐位说出四到八位验证码。验证码不会播报或保存");
        if(r.has("vehicleQuote")){
            quote=new HbcVoiceQuote(r.getJSONObject("vehicleQuote"));selectedQuoteReview="";message=quote.page();android.util.Log.i("AluoHbc","vehicle_options="+quote.options.length());
        }
        if(!message.isEmpty()){recentReplies.addLast(message);while(recentReplies.size()>4)recentReplies.removeFirst();}
        return reply(message.isEmpty()?"请继续说明旅行需求":message);
    }
    void resetOnClose(){closed=true;armed=false;token=null;conversation=null;phone=null;approval=null;review="";quote=null;selectedQuoteReview="";recentReplies.clear();}
    void close(){api.close();resetOnClose();}
}
