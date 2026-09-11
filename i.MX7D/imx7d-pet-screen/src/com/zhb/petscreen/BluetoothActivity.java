package com.zhb.petscreen;
import android.app.Activity;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.*;
import dalvik.system.DexClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class BluetoothActivity extends Activity {
    BluetoothAdapter adapter;
    TextView state;
    LinearLayout list;
    Button outputMode;
    Button micTest;
    AudioManager audio;
    PcmRecorder micRecorder;
    BluetoothDevice voiceDevice;
    boolean rePairing;
    int scoAttempt;
    final LinkedHashMap<String,BluetoothDevice> found=new LinkedHashMap<>();
    final android.os.Handler timer=new android.os.Handler();
    final Runnable timeout=() -> {if(adapter!=null && adapter.isDiscovering()) adapter.cancelDiscovery();};
    final BroadcastReceiver receiver=new BroadcastReceiver(){
        public void onReceive(Context c,Intent i){
            if(BluetoothDevice.ACTION_FOUND.equals(i.getAction())){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if(d!=null){String address=d.getAddress();
                    found.put(address,d);refreshList();
                    if(rePairing&&"AirPods Pro".equals(d.getName())){if(adapter.isDiscovering())adapter.cancelDiscovery();state.setText("已重新发现 AirPods，正在配对…");d.createBond();}}
            }else if(BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(i.getAction()))
                state.setText("扫描完成 · 发现 "+found.size()+" 个设备 · 点击名称进行配对");
            else if(BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(i.getAction())){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);int value=i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,BluetoothDevice.BOND_NONE);
                if(d!=null){found.put(d.getAddress(),d);state.setText(label(d)+" · "+bondName(value));refreshList();
                    if(rePairing&&"AirPods Pro".equals(d.getName())&&value==BluetoothDevice.BOND_NONE)scan();
                    else if(rePairing&&"AirPods Pro".equals(d.getName())&&value==BluetoothDevice.BOND_BONDED){rePairing=false;connectAudio(d);mainDelay(1800,()->connectVoice());}}
            }else if(BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction())){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null)state.setText(label(d)+" · 已连接");
            }else if(BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(i.getAction())){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);if(d!=null)state.setText(label(d)+" · 已断开");
            }else if(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(i.getAction())){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);int value=i.getIntExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_DISCONNECTED);
                if(d!=null)state.setText(label(d)+" · "+(value==BluetoothProfile.STATE_CONNECTED?"A2DP 已连接":value==BluetoothProfile.STATE_CONNECTING?"正在建立 A2DP…":"A2DP 已断开"));
            }else if(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(i.getAction())){
                BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);int value=i.getIntExtra(BluetoothProfile.EXTRA_STATE,BluetoothProfile.STATE_DISCONNECTED);
                if(d!=null&&value==BluetoothProfile.STATE_CONNECTED){voiceDevice=d;startSco();}
            }else if(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED.equals(i.getAction())){
                int value=i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE,AudioManager.SCO_AUDIO_STATE_ERROR);
                if(value==AudioManager.SCO_AUDIO_STATE_CONNECTED)recordSco();
                else if(value==AudioManager.SCO_AUDIO_STATE_DISCONNECTED&&micRecorder==null)state.setText("耳机通话音频未连接");
            }
        }
    };
    public void onCreate(Bundle b){super.onCreate(b);adapter=BluetoothAdapter.getDefaultAdapter();
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(24,16,24,16);
        state=new TextView(this);state.setTextSize(20);state.setText(adapter==null?"未检测到蓝牙适配器":"蓝牙："+(adapter.isEnabled()?"开启":"关闭")+" · 扫描不会自动配对");root.addView(state);
        outputMode=new Button(this);outputMode.setText("启用蓝牙耳机输出");outputMode.setOnClickListener(v->enableHeadphoneOutput());root.addView(outputMode);
        micTest=new Button(this);micTest.setText("测试耳机麦克风 5 秒");micTest.setOnClickListener(v->connectVoice());root.addView(micTest);
        Button repair=new Button(this);repair.setText("重新配对 AirPods 并测试麦克风");repair.setOnClickListener(v->repairAirPods());root.addView(repair);
        Button scan=new Button(this);scan.setText("扫描附近蓝牙设备");root.addView(scan);
        scan.setOnClickListener(v->{if(checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION},8);else scan();});
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(0,8,0,0);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
        if(adapter!=null)for(BluetoothDevice d:adapter.getBondedDevices())found.put(d.getAddress(),d);refreshList();
        IntentFilter f=new IntentFilter(BluetoothDevice.ACTION_FOUND);f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);f.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);f.addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED);registerReceiver(receiver,f);
    }
    void scan(){if(adapter==null||!adapter.isEnabled()){state.setText("蓝牙未开启");return;}
        if(adapter.isDiscovering())return;found.clear();refreshList();
        state.setText(adapter.startDiscovery()?"正在扫描，请让目标进入配对模式…":"扫描启动失败");
        timer.removeCallbacks(timeout);timer.postDelayed(timeout,20000);
    }
    public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);
        if(r==8 && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED)scan();
        else if(r==9 && g.length>0 && g[0]==PackageManager.PERMISSION_GRANTED)enableHeadphoneOutput();
        else state.setText("缺少蓝牙操作权限");}
    String label(BluetoothDevice d){String name=d.getName();String address=d.getAddress();return (name==null?"未命名设备":name)+" · 尾号 "+address.substring(address.length()-5);}
    String bondName(int value){return value==BluetoothDevice.BOND_BONDED?"配对成功，正在建立音频连接":value==BluetoothDevice.BOND_BONDING?"正在配对…":"未配对";}
    void refreshList(){
        list.removeAllViews();
        for(BluetoothDevice d:found.values()){
            Button button=new Button(this);button.setAllCaps(false);button.setText(label(d)+(d.getBondState()==BluetoothDevice.BOND_BONDED?" · 已配对":""));button.setContentDescription("配对 "+label(d));
            button.setOnClickListener(v->pair(d));list.addView(button,new LinearLayout.LayoutParams(-1,54));
        }
    }
    void pair(BluetoothDevice d){
        if(adapter.isDiscovering())adapter.cancelDiscovery();
        if(d.getBondState()==BluetoothDevice.BOND_BONDED){connectAudio(d);return;}
        state.setText(d.createBond()?label(d)+" · 正在配对…":label(d)+" · 无法发起配对");
    }
    void enableHeadphoneOutput(){
        String permission="com.google.android.things.permission.MANAGE_BLUETOOTH";
        if(checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{permission},9);return;}
        try{
            DexClassLoader loader=new DexClassLoader("/system/framework/com.google.android.things.jar",getCodeCacheDir().getAbsolutePath(),null,getClassLoader());
            Class<?> type=loader.loadClass("com.google.android.things.bluetooth.BluetoothProfileManager");
            Object manager=type.getMethod("getInstance").invoke(null);
            @SuppressWarnings("unchecked") List<Integer> enabled=(List<Integer>)type.getMethod("getEnabledProfiles").invoke(manager);
            List<Integer> profiles=new ArrayList<>(enabled);
            profiles.remove(Integer.valueOf(11));profiles.remove(Integer.valueOf(12));profiles.remove(Integer.valueOf(16));
            if(!profiles.contains(2))profiles.add(2);
            if(!profiles.contains(1))profiles.add(1);
            if(profiles.equals(enabled)){state.setText("蓝牙耳机输出模式已启用");return;}
            boolean changed=(Boolean)type.getMethod("setEnabledProfiles",List.class).invoke(manager,profiles);
            state.setText(changed?"已切换为耳机输出模式，等待蓝牙服务重启…":"耳机输出模式切换失败");
            android.util.Log.i("PetBluetooth","output_profiles_changed="+changed+" profiles="+profiles);
        }catch(Exception e){state.setText("无法切换耳机输出模式");android.util.Log.i("PetBluetooth","profile_error="+e.getClass().getSimpleName());}
    }
    void connectAudio(BluetoothDevice device){
        state.setText(label(device)+" · 正在连接音频…");
        adapter.getProfileProxy(this,new android.bluetooth.BluetoothProfile.ServiceListener(){
            public void onServiceConnected(int profile,android.bluetooth.BluetoothProfile proxy){
                try{
                    if(proxy.getConnectedDevices().contains(device)){state.setText(label(device)+" · A2DP 已连接");return;}
                    java.lang.reflect.Method method=proxy.getClass().getMethod("connect",BluetoothDevice.class);method.setAccessible(true);
                    boolean started=(Boolean)method.invoke(proxy,device);state.setText(label(device)+(started?" · 正在建立 A2DP…":" · A2DP 连接未启动"));
                    android.util.Log.i("PetBluetooth","a2dp_connect_started="+started);
                }catch(Exception e){state.setText(label(device)+" · 系统未开放 A2DP 连接");android.util.Log.i("PetBluetooth","a2dp_connect_error="+e.getClass().getSimpleName());}
            }
            public void onServiceDisconnected(int profile){}
        },android.bluetooth.BluetoothProfile.A2DP);
    }
    void connectVoice(){
        voiceDevice=null;for(BluetoothDevice d:adapter.getBondedDevices())if("AirPods Pro".equals(d.getName())){voiceDevice=d;break;}
        if(voiceDevice==null){state.setText("没有已配对的 AirPods Pro");return;}
        state.setText("正在连接耳机通话 Profile…");
        adapter.getProfileProxy(this,new BluetoothProfile.ServiceListener(){
            public void onServiceConnected(int profile,BluetoothProfile proxy){
                try{
                    if(proxy.getConnectedDevices().contains(voiceDevice)){startSco();return;}
                    java.lang.reflect.Method method=proxy.getClass().getMethod("connect",BluetoothDevice.class);method.setAccessible(true);
                    boolean started=(Boolean)method.invoke(proxy,voiceDevice);state.setText(started?"正在连接耳机通话 Profile…":"耳机不接受通话 Profile");
                    mainDelay(1500,()->{if(micRecorder==null)startSco();});
                }catch(Exception e){state.setText("系统未开放耳机通话连接");android.util.Log.i("PetBluetooth","headset_connect_error="+e.getClass().getSimpleName());}
            }
            public void onServiceDisconnected(int profile){}
        },BluetoothProfile.HEADSET);
    }
    void repairAirPods(){
        rePairing=true;BluetoothDevice target=null;for(BluetoothDevice d:adapter.getBondedDevices())if("AirPods Pro".equals(d.getName())){target=d;break;}
        if(target==null){scan();return;}
        try{java.lang.reflect.Method method=target.getClass().getMethod("removeBond");method.setAccessible(true);boolean started=(Boolean)method.invoke(target);state.setText(started?"正在清除旧配对记录…":"无法清除旧配对记录");}
        catch(Exception e){rePairing=false;state.setText("系统未开放重新配对");android.util.Log.i("PetBluetooth","remove_bond_error="+e.getClass().getSimpleName());}
    }
    void mainDelay(long delay,Runnable action){new android.os.Handler().postDelayed(action,delay);}
    void startSco(){
        int attempt=++scoAttempt;state.setText("正在建立 SCO 麦克风通道…");audio.setMode(AudioManager.MODE_IN_COMMUNICATION);audio.setBluetoothScoOn(true);audio.startBluetoothSco();
        android.util.Log.i("PetBluetooth","sco_requested=true available_off_call="+audio.isBluetoothScoAvailableOffCall());
        mainDelay(8000,()->{if(attempt==scoAttempt&&micRecorder==null){audio.stopBluetoothSco();audio.setBluetoothScoOn(false);audio.setMode(AudioManager.MODE_NORMAL);state.setText("SCO 未建立，AirPods 麦克风当前不可用");android.util.Log.i("PetBluetooth","sco_timeout=true");}});
    }
    void recordSco(){
        if(micRecorder!=null)return;scoAttempt++;state.setText("耳机麦克风录音中，请说话…");
        micRecorder=new PcmRecorder(new java.io.File(getFilesDir(),"bluetooth-mic-test.wav"),5000,MediaRecorder.AudioSource.VOICE_COMMUNICATION);
        try{micRecorder.start(result->runOnUiThread(()->{
            micRecorder=null;audio.stopBluetoothSco();audio.setBluetoothScoOn(false);audio.setMode(AudioManager.MODE_NORMAL);
            state.setText("耳机麦克风："+result.durationMs()+" ms · 峰值 "+result.peak+" · 路由 "+result.routeName+" ("+result.routeType+")");
            android.util.Log.i("PetBluetooth","mic_result success="+result.success+" duration_ms="+result.durationMs()+" peak="+result.peak+" route_type="+result.routeType+" route_name="+result.routeName);
        }));}catch(Exception e){micRecorder=null;state.setText("耳机麦克风录音启动失败");}
    }
    protected void onDestroy(){scoAttempt++;timer.removeCallbacks(timeout);if(micRecorder!=null)micRecorder.stop();if(audio!=null){audio.stopBluetoothSco();audio.setBluetoothScoOn(false);audio.setMode(AudioManager.MODE_NORMAL);}if(adapter!=null && adapter.isDiscovering())adapter.cancelDiscovery();unregisterReceiver(receiver);super.onDestroy();}
}
