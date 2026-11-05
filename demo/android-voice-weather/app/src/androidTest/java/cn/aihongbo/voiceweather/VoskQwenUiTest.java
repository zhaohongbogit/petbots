package cn.aihongbo.voiceweather;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
public class VoskQwenUiTest {
    private final Instrumentation ins=InstrumentationRegistry.getInstrumentation();
    private MainActivity activity;
    @Test public void audioToModelConfirmationAndWeather() throws Exception {
        try(android.os.ParcelFileDescriptor output=ins.getUiAutomation().executeShellCommand("am start -W -n cn.aihongbo.voiceweather/.MainActivity");java.io.InputStream input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(output)){while(input.read()!=-1){}}
        long deadline=SystemClock.elapsedRealtime()+20000;
        while(activity==null && SystemClock.elapsedRealtime()<deadline){ins.runOnMainSync(() -> {for(android.app.Activity value:ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))if(value instanceof MainActivity)activity=(MainActivity)value;});SystemClock.sleep(100);}
        assertNotNull(activity);
        try {
            await("Vosk + Qwen3 已就绪",120000);
            submit("郑重提醒大家明天天气有变化");awaitIdle();assertEquals(DialogueEngine.State.LISTENING,state());assertTrue(text().contains("未以“阿洛”唤醒"));assertFalse(text().contains("[助手] 收到"));
            submit("阿洛，郑重提醒大家明天天气有变化");awaitIdle();assertEquals(DialogueEngine.State.LISTENING,state());assertTrue(text().contains("[助手] 收到"));assertTrue(text().contains("[助手] 我还不会"));assertTrue(text().contains("真实天气请求：0 次"));
            submit("阿洛，上海后天天气怎么样");awaitIdle();assertEquals(DialogueEngine.State.LISTENING,state());assertTrue(text().contains("目前只支持今天或明天"));assertTrue(text().contains("[助手] 我还不会"));
            click("阿洛");awaitIdle();click("识别郑州天气音频");awaitState(DialogueEngine.State.CONFIRMING,120000);
            assertTrue(text().contains("需要帮主人查询郑州明天的天气吗"));assertTrue(text().contains("真实天气请求：0 次"));
            submit("不是的");awaitIdle();assertEquals(DialogueEngine.State.LISTENING,state());assertTrue(text().contains("已拒绝"));assertTrue(text().contains("真实天气请求：0 次"));
            click("停止 / 重置当前流程");
            boolean live="true".equals(InstrumentationRegistry.getArguments().getString("live"));
            if(live)ins.runOnMainSync(() -> ((Switch)find(activity.getWindow().getDecorView(),"真实天气 · 联网（关闭时使用模拟数据）",Switch.class)).setChecked(true));
            click("阿洛");awaitIdle();click("识别上海明天天气音频");awaitState(DialogueEngine.State.CONFIRMING,120000);
            assertTrue(text().contains("需要帮主人查询上海明天的天气吗"));
            click("识别肯定音频");await("[预报] "+(live?"上海明天":"模拟数据：上海明天"),120000);await("播报流程结束",30000);
            if(live)assertTrue(text().contains("明确确认后请求 Open-Meteo，城市：上海"));
            click("停止 / 重置当前流程");submit("阿洛，上海明天天气怎么样");click("停止 / 重置当前流程");awaitIdle();assertEquals(DialogueEngine.State.LISTENING,state());
            try(java.io.FileOutputStream out=activity.openFileOutput("vosk-qwen-ui-report.txt",0)){out.write(text().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }finally{ins.runOnMainSync(() -> activity.finish());}
    }
    private void submit(String value){ins.runOnMainSync(() -> {EditText field=(EditText)findType(activity.getWindow().getDecorView(),EditText.class);assertNotNull(field);field.setText(value);});click("送入一句文字");}
    private void click(String label){ins.runOnMainSync(() -> {View view=find(activity.getWindow().getDecorView(),label,Button.class);assertNotNull(label,view);assertTrue(view.performClick());});}
    private DialogueEngine.State state(){AtomicReference<DialogueEngine.State> value=new AtomicReference<>();ins.runOnMainSync(() -> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("engine");f.setAccessible(true);value.set(((DialogueEngine)f.get(activity)).state());}catch(Exception error){throw new RuntimeException(error);}});return value.get();}
    private boolean busy(){AtomicReference<Boolean> value=new AtomicReference<>(false);ins.runOnMainSync(() -> {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField("busy");f.setAccessible(true);value.set(f.getBoolean(activity));}catch(Exception error){throw new RuntimeException(error);}});return value.get();}
    private void awaitIdle(){long deadline=SystemClock.elapsedRealtime()+120000;while(SystemClock.elapsedRealtime()<deadline){if(!busy())return;SystemClock.sleep(100);}fail("Model did not become idle");}
    private void awaitState(DialogueEngine.State expected,long timeout){long deadline=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<deadline){if(state()==expected)return;SystemClock.sleep(100);}fail("Expected "+expected+"\n"+text());}
    private void await(String expected,long timeout){long deadline=SystemClock.elapsedRealtime()+timeout;while(SystemClock.elapsedRealtime()<deadline){if(text().contains(expected))return;SystemClock.sleep(100);}fail("Missing "+expected+"\n"+text());}
    private String text(){AtomicReference<String> value=new AtomicReference<>();ins.runOnMainSync(() -> {StringBuilder b=new StringBuilder();collect(activity.getWindow().getDecorView(),b);value.set(b.toString());});return value.get();}
    private static void collect(View v,StringBuilder out){if(v instanceof TextView)out.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
    private static View findType(View v,Class<?> type){if(type.isInstance(v))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=findType(((ViewGroup)v).getChildAt(i),type);if(found!=null)return found;}return null;}
    private static View find(View v,String label,Class<?> type){if(type.isInstance(v)&&v instanceof TextView&&((TextView)v).getText().toString().equals(label))return v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),label,type);if(found!=null)return found;}return null;}
}
