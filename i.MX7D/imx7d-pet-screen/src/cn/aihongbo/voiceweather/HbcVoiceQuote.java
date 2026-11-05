package cn.aihongbo.voiceweather;
import org.json.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Speak actual structured quote options one at a time; never synthesize a price or vehicle choice. */
final class HbcVoiceQuote {
    final String id;final JSONArray options;final long expiresAt;
    int index;
    HbcVoiceQuote(JSONObject quote)throws Exception{
        id=quote.getString("quoteId");if(!id.matches("[A-Za-z0-9_:-]{1,160}"))throw new JSONException("QUOTE_ID");
        options=quote.getJSONArray("vehicleOptions");if(options.length()>100)throw new JSONException("QUOTE_LIMIT");
        expiresAt=OffsetDateTime.parse(quote.getString("expiresAt")).toInstant().toEpochMilli();
    }
    boolean expired(){return System.currentTimeMillis()>=expiresAt;}
    String page()throws Exception{
        if(expired())return "这份报价已过期，请说重新报价。";
        if(options.length()==0)return "本次没有返回可选车型，请说重新报价，或说明需要调整的行程。";
        JSONObject o=options.getJSONObject(index);
        String price=price(o),name=o.optString("modelName","未命名车型");
        StringBuilder text=new StringBuilder("第").append(index+1).append("个，共").append(options.length()).append("个车型：").append(name).append("。");
        if(o.has("seats"))text.append("乘客容量").append(o.getInt("seats")).append("人，");
        if(o.has("luggageCapacity"))text.append("行李容量").append(o.getInt("luggageCapacity")).append("件。");
        text.append(price==null?"价格尚未确认，暂不能选择。":"总价"+price+"。");
        if(!o.optBoolean("available",false))text.append("此车型不可选，原因：").append(o.optString("unavailableReason","不满足本次用车条件")).append("。");
        if(selectable(o))text.append("选择请说选这个车型。");
        return text.append("继续听请说下一个车型，重听请说重听车型。").toString();
    }
    private static String price(JSONObject option){
        try{
            JSONObject total=option.getJSONObject("total");String currency=total.getString("currency");if(!currency.matches("[A-Z]{3}"))return null;
            BigDecimal cents=new BigDecimal(total.get("amountInCents").toString());long amount=cents.longValueExact();if(amount<0)return null;
            String unit=currency.equals("CNY")?"人民币":currency.equals("JPY")?"日元":currency.equals("USD")?"美元":currency;
            return BigDecimal.valueOf(amount,2).stripTrailingZeros().toPlainString()+unit;
        }catch(Exception invalid){return null;}
    }
    private static boolean selectable(JSONObject o){return o.optBoolean("available",false)&&price(o)!=null&&o.optString("optionId","").matches("[A-Za-z0-9_:-]{1,160}");}
    // Null means it is an unrelated new request, not a vehicle decision.
    String navigate(String compact)throws Exception{
        if(compact.equals("下一个车型")||compact.equals("下一个")){if(index+1<options.length())index++;return page();}
        if(compact.equals("上一个车型")||compact.equals("上一个")){if(index>0)index--;return page();}
        if(compact.equals("重听车型"))return page();
        return null;
    }
    String choose(String text)throws Exception{
        if(!(text.equals("选这个车型")||text.equals("选择这个车型")||text.equals("就这个车型")))return null;
        if(expired()||options.length()==0)return "";
        JSONObject o=options.getJSONObject(index);if(!selectable(o))return "";
        return "我选择车型“"+o.getString("modelName")+"”（报价编号："+id+"，车型选项："+o.getString("optionId")+"）。请锁定该车型并展示最终报价，不要直接创建订单。";
    }
}
