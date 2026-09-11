package com.zhb.phonespeaker;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;

/** User-selected screenshot -> editable recognition -> confirmed deterministic plan. */
public class HomeworkActivity extends TextChatActivity {
    byte[] jpeg;ImageView preview;TextView notice,timeLabel;LinearLayout rows;Button choose,recognize,confirm,startButton;EditText available;
    final ArrayList<EditText> titles=new ArrayList<>(),durations=new ArrayList<>();int startMinutes;boolean loading,planShown;
    public void onCreate(Bundle b){super.onCreate(b);
        Calendar now=Calendar.getInstance();startMinutes=now.get(Calendar.HOUR_OF_DAY)*60+now.get(Calendar.MINUTE);
        ScrollView page=new ScrollView(this);LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(22,16,22,24);page.addView(root);
        TextView heading=new TextView(this);heading.setText("给小七看作业");heading.setTextSize(26);root.addView(heading);
        TextView tip=new TextView(this);tip.setText("选择一张清晰截图。点击识别后，图片经 iMX7 发送至已配置的模型服务。核对作业和预估耗时后，再生成计划。");root.addView(tip);
        choose=button("选择 / 更换作业截图",root,()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("image/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,42);});
        preview=new ImageView(this);preview.setAdjustViewBounds(true);preview.setMaxHeight(320);root.addView(preview,new LinearLayout.LayoutParams(-1,260));
        notice=new TextView(this);notice.setText("尚未选择截图");root.addView(notice);
        recognize=button("识别作业",root,()->{if(jpeg==null)return;try{runJob("/homework",new JSONObject().put("image",Base64.encodeToString(jpeg,Base64.NO_WRAP)).put("text","请识别截图中的作业，预估每项耗时，模糊内容标为待确认"),true);}catch(Exception e){notice.setText("图片处理失败");}});
        rows=new LinearLayout(this);rows.setOrientation(1);root.addView(rows);
        button("补充一项作业",root,()->{if(!busy&&titles.size()<20)addRow("",15,true);});
        startButton=button("",root,()->new TimePickerDialog(this,(v,h,m)->{startMinutes=h*60+m;updateTime();},startMinutes/60,startMinutes%60,true).show());updateTime();
        TextView label=new TextView(this);label.setText("本次可用时长（分钟，请确认）");root.addView(label);
        available=new EditText(this);available.setInputType(2);available.setText("90");watch(available);root.addView(available);
        confirm=button("已核对作业与耗时，生成计划",root,()->{
            try{JSONArray tasks=new JSONArray();for(int n=0;n<titles.size();n++){String title=titles.get(n).getText().toString().trim();int mins=Integer.parseInt(durations.get(n).getText().toString());if(title.isEmpty()||title.length()>200||title.contains("待确认")||mins<1||mins>180)throw new IllegalArgumentException();tasks.put(new JSONObject().put("title",title).put("minutes",mins));}
                int budget=Integer.parseInt(available.getText().toString());if(tasks.length()==0||budget<15||budget>720)throw new IllegalArgumentException();
                runJob("/homework/plan",new JSONObject().put("tasks",tasks).put("start_minutes",startMinutes).put("available_minutes",budget),false);
            }catch(Exception e){notice.setText("请核对每项作业，修正[待确认]文字；每项耗时1–180分钟，可用时间15–720分钟。");}
        });
        output=new TextView(this);output.setTextSize(18);output.setTextIsSelectable(true);root.addView(output);
        setContentView(page);controls();
    }
    Button button(String label,LinearLayout parent,Runnable run){Button b=new Button(this);b.setText(label);b.setOnClickListener(v->run.run());parent.addView(b);return b;}
    void markChanged(){if(planShown){planShown=false;output.setText("");notice.setText("内容已修改，请重新确认并生成计划。");}}
    void watch(EditText field){field.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){markChanged();}public void afterTextChanged(android.text.Editable e){}});}
    void updateTime(){markChanged();startButton.setText(String.format(Locale.ROOT,"开始时间 %02d:%02d（点击修改）",startMinutes/60,startMinutes%60));}
    void controls(){boolean ready=!busy&&!loading;choose.setEnabled(ready);recognize.setEnabled(ready&&jpeg!=null);confirm.setEnabled(ready&&!titles.isEmpty());startButton.setEnabled(ready);available.setEnabled(ready);for(EditText t:titles)t.setEnabled(ready);for(EditText d:durations)d.setEnabled(ready);}
    void addRow(String title,int minutes,boolean uncertain){
        markChanged();LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(0,12,0,12);
        TextView label=new TextView(this);label.setText(uncertain?"待核对内容 / 耗时为估计":"识别内容 / 耗时为估计");card.addView(label);
        EditText t=new EditText(this);t.setText(title);t.setHint("科目和作业内容");t.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});card.addView(t);
        EditText d=new EditText(this);d.setInputType(2);d.setText(String.valueOf(minutes));d.setHint("预估分钟数");card.addView(d);titles.add(t);durations.add(d);watch(t);watch(d);
        button("删除此项",card,()->{if(busy)return;markChanged();rows.removeView(card);titles.remove(t);durations.remove(d);controls();});rows.addView(card);controls();
    }
    protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=42||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();loading=true;controls();notice.setText("正在处理截图…");
        new Thread(()->{try{
            byte[] source;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];for(int n;(n=in.read(b))!=-1;){if(out.size()+n>15*1024*1024)throw new IOException();out.write(b,0,n);}source=out.toByteArray();}
            BitmapFactory.Options opts=new BitmapFactory.Options();opts.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(source,0,source.length,opts);if(opts.outWidth<1||opts.outHeight<1)throw new IOException();
            opts.inSampleSize=1;while(Math.max(opts.outWidth,opts.outHeight)/opts.inSampleSize>2000)opts.inSampleSize*=2;opts.inJustDecodeBounds=false;
            Bitmap decoded=BitmapFactory.decodeByteArray(source,0,source.length,opts);if(decoded==null)throw new IOException();float scale=Math.min(1f,1600f/Math.max(decoded.getWidth(),decoded.getHeight()));Bitmap small=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*scale)),Math.max(1,Math.round(decoded.getHeight()*scale)),true);if(small!=decoded)decoded.recycle();
            ByteArrayOutputStream out=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,88,out);if(out.size()>1048576){out.reset();small.compress(Bitmap.CompressFormat.JPEG,65,out);}if(out.size()>1048576){small.recycle();throw new IOException();}byte[] encoded=out.toByteArray();
            runOnUiThread(()->{jpeg=encoded;planShown=false;preview.setImageBitmap(small);titles.clear();durations.clear();rows.removeAllViews();output.setText("");notice.setText("截图已选择。请确认文字清晰，再点击识别。");loading=false;controls();});
        }catch(Exception e){runOnUiThread(()->{notice.setText("无法读取图片，请选择不超过15MB的清晰截图。");loading=false;controls();});}},"homework-image").start();
    }
    void runJob(String path,JSONObject data,boolean scan){
        if(busy||loading)return;if(host.isEmpty()||key.isEmpty()){notice.setText("请先配置与 iMX7 的连接");return;}busy=true;getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);controls();notice.setText(scan?"小七正在识别作业，请保持本页打开…":"正在生成时间计划…");
        new Thread(()->{try{String id=UUID.randomUUID().toString();data.put("id",id);request(path,data);long end=SystemClock.elapsedRealtime()+90000;
            while(active&&SystemClock.elapsedRealtime()<end){JSONObject result=request("/status?id="+id,null);String state=result.optString("state");if(state.equals("completed")){
                runOnUiThread(()->{if(!active)return;try{if(scan){JSONObject hw=result.getJSONObject("homework");JSONArray tasks=hw.getJSONArray("tasks");rows.removeAllViews();titles.clear();durations.clear();for(int i=0;i<tasks.length();i++){JSONObject t=tasks.getJSONObject(i);addRow(t.getString("title"),t.getInt("minutes"),t.getBoolean("uncertain"));}notice.setText("请逐项核对，可修改内容和耗时。\n"+hw.optString("notes"));output.setText("");}else{notice.setText("计划已同步给小七");planShown=true;output.setText(result.optString("reply"));}}catch(Exception e){notice.setText("识别结果格式不完整，请重试");}});return;}
                if(state.equals("failed")||state.equals("cancelled"))throw new IOException();Thread.sleep(700);
            }throw new IOException();
        }catch(Exception e){runOnUiThread(()->notice.setText("本次处理未完成，截图与编辑内容已保留。请检查 iMX7 页面与网络，再重试。"));}
        finally{runOnUiThread(()->{busy=false;getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);controls();});}},"homework-job").start();
    }
}
