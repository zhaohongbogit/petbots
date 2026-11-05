package cn.aihongbo.voiceweather;
import org.json.*;
import java.io.IOException;

/** Host-only, no audio/network/business calls. Uses real JSON and actual client state/quote code. */
public final class HbcQuoteFlowTest {
    static int checks;
    static final String ID="00000000-0000-4000-8000-000000000001";
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static JSONObject completed()throws Exception{return new JSONObject().put("status","completed").put("conversationId",ID).put("message","请在下方选择车型");}
    static JSONObject option(String id,String name,boolean available,Object price)throws Exception{return new JSONObject().put("optionId",id).put("modelName",name).put("available",available).put("seats",4).put("luggageCapacity",2).put("unavailableReason","容量不足").put("total",new JSONObject().put("currency","CNY").put("amountInCents",price));}
    static JSONObject quote()throws Exception{return new JSONObject().put("quoteId","quote-a").put("expiresAt","2099-12-01T00:00:00Z").put("vehicleOptions",new JSONArray().put(option("option-a","经济型",true,42505)).put(option("option-b","商务型",false,60000)));}
    static final class Api implements HbcTravelSession.Api {
        int calls;boolean fail;JSONObject next,body;
        public JSONObject post(String path,JSONObject body,String token)throws Exception{calls++;this.body=body;if(fail)throw new IOException("fixture");return next;}
        public void close(){}
    }
    public static void main(String[] args)throws Exception {
        Api a=new Api();a.next=completed().put("vehicleQuote",quote());HbcTravelSession s=new HbcTravelSession(a,()->0);
        String first=s.respond("包车报价").text;check(first.contains("经济型")&&first.contains("425.05人民币")&&!first.contains("下方"),"structured price actually spoken");
        check(first.contains("第1个，共2个")&&!first.contains("商务型"),"one option at a time");
        check(a.body.getString("interactionMode").equals("voice"),"explicit voice protocol");
        String next=s.respond("下一个车型").text;check(a.calls==1&&next.contains("商务型")&&next.contains("不可选"),"local navigation and unavailable reason");
        s.respond("选这个车型");check(a.calls==1,"unavailable cannot be submitted");
        s.respond("上一个车型");a.next=completed().put("message","最终报价425.05元，请选择支付方式");s.respond("选这个车型");
        check(a.calls==2&&a.body.getString("message").contains("quote-a")&&a.body.getString("message").contains("option-a")&&a.body.getString("message").contains("不要直接创建订单"),"explicit bound choice, no order");
        HbcVoiceQuote expired=new HbcVoiceQuote(quote().put("expiresAt","2000-01-01T00:00:00Z"));check(expired.page().contains("过期")&&expired.choose("选这个车型").isEmpty(),"expired quote blocked");
        HbcVoiceQuote fractional=new HbcVoiceQuote(quote().put("vehicleOptions",new JSONArray().put(option("x","无效价格",true,12.5))));check(fractional.page().contains("价格尚未确认")&&fractional.choose("选这个车型").isEmpty(),"fractional cents never rounded");
        HbcVoiceQuote missingCurrency=new HbcVoiceQuote(quote().put("vehicleOptions",new JSONArray().put(option("x","无币种",true,100).put("total",new JSONObject().put("amountInCents",100)))));check(missingCurrency.choose("选这个车型").isEmpty(),"no currency default");
        HbcVoiceQuote empty=new HbcVoiceQuote(quote().put("vehicleOptions",new JSONArray()));check(empty.page().contains("没有返回可选车型"),"empty list clear feedback");
        Api network=new Api();network.fail=true;HbcTravelSession retry=new HbcTravelSession(network,()->0);check(retry.respond("包车报价").text.contains("没有返回完整结果"),"readonly failure explained");
        network.fail=false;network.next=completed();retry.respond("重新报价");check(network.calls==2,"manual retry allowed after readonly failure");
        System.out.println("PASS "+checks+" quote flow assertions; no real audio/network/SMS/order");
    }
}
