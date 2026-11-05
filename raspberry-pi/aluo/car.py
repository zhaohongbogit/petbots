"""Bounded car choreography. Hardware stays disabled until explicitly configured."""
import json
import math
import threading
import time
from dataclasses import dataclass

@dataclass(frozen=True)
class Step:
    seconds: float
    speed: float = 0
    steering: float = 0
    radar: float = 0

PLANS = {
 'forward': [Step(.5,.25)], 'backward': [Step(.3,-.2)],
 'left': [Step(.5,.2,-10)], 'right': [Step(.5,.2,10)],
 'wave': [Step(.35,0,s,r) for s,r in [(-10,-15),(10,15),(-10,15),(10,-15),(0,0)]],
 'dance': [Step(.3,0,s,r) for s,r in [(-10,-15),(10,15),(-10,15),(10,-15),(0,0)]],
 'steps': [Step(.3,0,-10,-15),Step(.2,.2,-10),Step(.3,0,10,15),Step(.2,.2,10),Step(.3)],
}
LABELS = {'forward':'低速前进半秒','backward':'低速后退零点三秒，后方没有测距保护，请确认后方无人无障碍',
          'left':'低速向左行驶半秒','right':'低速向右行驶半秒',
          'dance':'原地摇摆舞，不驱动车轮','wave':'原地招手舞，不驱动车轮','steps':'低速小碎步舞，包含两次零点二秒前进'}
WORDS = {'前进':'forward','前进一下':'forward','往前走':'forward','后退':'backward','后退一下':'backward',
         '左转':'left','左转一下':'left','右转':'right','右转一下':'right',
         '跳舞':'dance','跳个舞':'dance','跳舞一下':'dance','跳摇摆舞':'dance','跳招手舞':'wave','跳小碎步舞':'steps'}
STOP = {'停','停止','停车','停下','停下来','急停','停止跳舞'}

def command(text):
    for prefix in ('你好阿洛','你好阿路','阿洛','阿路'):
        if text.startswith(prefix):
            text=text[len(prefix):]
            break
    return 'stop' if text in STOP else WORDS.get(text)

def validate(config):
    if config.get('numbering') != 'BCM': raise ValueError('必须使用BCM编号')
    names=('in1','in2','ena','steering','radar','trig','echo')
    pins=config.get('pins',{})
    values=[pins.get(n) for n in names]
    if any(type(p) is not int or not 0<=p<=27 for p in values) or len(set(values))!=len(values):
        raise ValueError('七个独立GPIO尚未确认或有冲突')
    if not all(config.get(k) is True for k in ('pins_confirmed','echo_3v3_safe','external_servo_power_common_ground','ena_jumper_removed','calibration_confirmed')):
        raise ValueError('接线、电平、供电或舵机标定未确认')
    for name in ('steering','radar'):
        c=config[name]
        if not 0<c['min_pulse_ms']<c['max_pulse_ms']<=2.5: raise ValueError('舵机脉宽无效')
        if not 0<=c['min']<c['center']<c['max']<=180: raise ValueError('舵机限位无效')
        if min(c['center']-c['min'],c['max']-c['center']) < (10 if name=='steering' else 15): raise ValueError('舞蹈超出标定范围')
    return config

class GpioHardware:
    def __init__(self, config):
        validate(config)
        from gpiozero import PWMOutputDevice,AngularServo,OutputDevice,DigitalInputDevice
        self.config=config
        self.devices=[]
        p=config['pins']
        try:
            self.enable=PWMOutputDevice(p['ena'],initial_value=0,frequency=1000)
            self.devices.append(self.enable)
            self.in1=OutputDevice(p['in1'],initial_value=False);self.devices.append(self.in1)
            self.in2=OutputDevice(p['in2'],initial_value=False);self.devices.append(self.in2)
            self.direction=0
            self.servos={}
            for name in ('steering','radar'):
                c=config[name]
                servo=AngularServo(p[name],initial_angle=None,min_angle=0,max_angle=180,
                    min_pulse_width=c['min_pulse_ms']/1000,max_pulse_width=c['max_pulse_ms']/1000)
                self.servos[name]=servo;self.devices.append(servo)
            self.trig=OutputDevice(p['trig'],initial_value=False);self.devices.append(self.trig)
            self.echo=DigitalInputDevice(p['echo'],pull_up=False);self.devices.append(self.echo)
        except Exception:
            self.close();raise
    def pose(self,steering,radar):
        for name,offset in [('steering',steering),('radar',radar)]:
            c=self.config[name];angle=c['center']+offset
            if not c['min']<=angle<=c['max']: raise ValueError('舵机超出标定限位')
            self.servos[name].angle=angle
    def drive(self,speed):
        direction=1 if speed>0 else -1 if speed<0 else 0
        if direction!=self.direction or direction==0:
            self.enable.off();self.in1.off();self.in2.off()
        self.in1.value=direction>0;self.in2.value=direction<0
        self.enable.value=abs(speed)
        self.direction=direction
    def distance(self):
        if self.echo.is_active: return None
        self.trig.on();time.sleep(.00001);self.trig.off()
        if not self.echo.wait_for_active(timeout=.03):return None
        start=time.monotonic()
        if not self.echo.wait_for_inactive(timeout=.03):return None
        result=(time.monotonic()-start)*171.5
        return result if .02<=result<=3 else None
    def stop(self):
        if hasattr(self,'enable'):self.enable.off()
        if hasattr(self,'in1'):self.in1.off()
        if hasattr(self,'in2'):self.in2.off()
        self.direction=0
        for servo in getattr(self,'servos',{}).values():servo.detach()
    def close(self):
        try:self.stop()
        finally:
            for device in reversed(self.devices):device.close()

class SimHardware:
    def __init__(self):self.events=[]
    def pose(self,s,r):self.events.append(('pose',s,r))
    def drive(self,v):self.events.append(('drive',v))
    def distance(self):return 1.0  # synthetic, never a real sensor measurement
    def stop(self):self.events.append(('stop',))
    def close(self):self.stop()

class Car:
    def __init__(self,hardware=None,reverse=False,simulated=False):
        self.hardware=hardware;self.reverse=reverse;self.simulated=simulated
        self.cancel=threading.Event();self.lock=threading.RLock();self.thread=None
        self.last_result='';self.closing=False
    @property
    def enabled(self):return self.hardware is not None and not self.closing
    def stop(self):
        self.cancel.set()
        with self.lock:
            if self.hardware:self.hardware.stop()
    def start(self,action):
        if not self.enabled:raise RuntimeError('小车硬件尚未启用，请先确认接线和标定')
        if action not in PLANS:raise ValueError('未支持的动作')
        if action=='backward' and not self.reverse:raise RuntimeError('后退尚未启用，需确认后方安全与接线')
        if self.thread and self.thread.is_alive():raise RuntimeError('正在执行动作，请先停车')
        self.cancel.clear()
        self.thread=threading.Thread(target=self.execute,args=(PLANS[action],),daemon=True)
        self.thread.start()
    def execute(self,steps):
        try:
            if sum(s.seconds for s in steps)>6 or any(not 0<s.seconds<=.6 or abs(s.speed)>.25 for s in steps):
                raise ValueError('动作超出限时限速')
            for step in steps:
                if self.cancel.is_set():break
                with self.lock:
                    if self.cancel.is_set():break
                    self.hardware.drive(0)
                    # Radar must face forward whenever driving, including a turning car.
                    self.hardware.pose(step.steering,0 if step.speed else step.radar)
                if self.cancel.wait(.25):break
                end=time.monotonic()+step.seconds
                while time.monotonic()<end and not self.cancel.is_set():
                    with self.lock:
                        if self.cancel.is_set():break
                        if step.speed>0:
                            distance=self.hardware.distance()
                            if distance is None or not math.isfinite(distance) or distance<.35:
                                raise RuntimeError('障碍过近或测距无效，已停车')
                        if self.cancel.is_set():break
                        self.hardware.drive(step.speed)
                    self.cancel.wait(.05)
            self.last_result='模拟完成' if self.simulated else '动作结束'
        except Exception as error:
            self.last_result=str(error)
        finally:
            self.stop()
            print(json.dumps({'event':'car_finished','simulated':self.simulated,'result':self.last_result},ensure_ascii=False),flush=True)
    def close(self):
        self.closing=True;self.stop()
        if self.thread:self.thread.join(timeout=2)
        if self.hardware:self.hardware.close()

def load(path,simulate=False):
    if simulate:return Car(SimHardware(),reverse=True,simulated=True)
    if not path or not path.is_file():return Car()
    config=json.loads(path.read_text())
    if config.get('armed') is not True:return Car()
    return Car(GpioHardware(config),reverse=config.get('reverse_enabled') is True)
