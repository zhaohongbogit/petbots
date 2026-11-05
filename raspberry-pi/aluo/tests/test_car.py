import sys,pathlib,unittest,time
sys.path.insert(0,str(pathlib.Path(__file__).resolve().parents[1]))
from car import Car,SimHardware,Step,command,validate,load
from aluo import Session
class CarTests(unittest.TestCase):
 def test_no_gpio_without_arming(self):
  self.assertFalse(load(None).enabled)
  self.assertFalse(load(pathlib.Path(__file__).parents[1]/'car.example.json').enabled)
 def test_invalid_pin_and_confirmation(self):
  import json
  c=json.loads((pathlib.Path(__file__).parents[1]/'car.example.json').read_text())
  with self.assertRaises(ValueError):validate(c)
 def test_exact_commands(self):
  self.assertEqual(command('阿洛前进一下'),'forward')
  self.assertEqual(command('你好阿洛跳舞'),'dance')
  for text in ['不要前进','他说阿洛前进','前进十分钟','前进然后后退']:
   self.assertIsNone(command(text))
 def test_obstacle_stops_before_drive(self):
  for distance in [None,float('nan'),.1]:
   h=SimHardware();h.distance=lambda:distance
   c=Car(h);c.execute([Step(.1,.2)])
   self.assertFalse(any(e[0]=='drive' and e[1]!=0 for e in h.events))
   self.assertEqual(h.events[-1],('stop',))
 def test_obstacle_during_motion_stops(self):
  h=SimHardware();readings=iter([1,.1]);h.distance=lambda:next(readings)
  c=Car(h);c.execute([Step(.3,.2)])
  self.assertIn(('drive',.2),h.events);self.assertEqual(h.events[-1],('stop',))
 def test_dance_never_drives_wheels(self):
  from car import PLANS
  h=SimHardware();c=Car(h,simulated=True);c.execute(PLANS['dance'])
  self.assertTrue(any(e[0]=='pose' for e in h.events))
  self.assertFalse(any(e[0]=='drive' and e[1]!=0 for e in h.events))
  self.assertEqual(h.events[-1],('stop',))
 def test_explicit_stop_interrupts(self):
  h=SimHardware();c=Car(h,simulated=True)
  c.start('dance');c.stop();c.thread.join(1)
  self.assertFalse(c.thread.is_alive());self.assertEqual(h.events[-1],('stop',))
 def test_bounds_and_rear_guard(self):
  h=SimHardware();c=Car(h)
  with self.assertRaises(RuntimeError):c.start('backward')
  c.execute([Step(20,.2)])
  self.assertFalse(any(e[0]=='drive' and e[1] for e in h.events))
 def test_confirm_then_action_and_stop_any_state(self):
  class Fake:
   enabled=True;simulated=True
   def __init__(self):self.actions=[]
   def start(self,a):self.actions.append(a)
   def stop(self):self.actions.append('stop')
  c=Fake();s=Session(lambda _:None,car=c)
  s.accept('阿洛前进一下');self.assertEqual(c.actions,[])
  s.accept('是的');self.assertEqual(c.actions,['forward'])
  s.accept('停车');self.assertEqual(c.actions[-1],'stop')
 def test_cancel_and_timeout_never_move(self):
  h=SimHardware();c=Car(h);now=[1]
  s=Session(lambda _:None,clock=lambda:now[0],car=c)
  s.accept('阿洛前进一下');s.accept('取消')
  s.accept('阿洛前进一下');now[0]=20;s.accept('是的')
  self.assertEqual(h.events,[])
if __name__=='__main__':unittest.main()
