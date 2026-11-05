#!/usr/bin/env python3
"""Explicit isolated A-channel bench pulse; never initializes servos or sonar."""
import argparse
import signal
import time
import math

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--confirm-wheels-raised',action='store_true')
    parser.add_argument('--duty',type=float,default=.2)
    parser.add_argument('--seconds',type=float,default=.2)
    args=parser.parse_args()
    if not math.isfinite(args.seconds) or not 0<args.seconds<=2:
        parser.error('Duration must be within (0, 2] seconds')
    if not math.isfinite(args.duty) or not 0<args.duty<=.4:
        parser.error('Duty must be within (0, 0.4]; duration separately bounded')
    if not args.confirm_wheels_raised:
        parser.error('Requires wheels raised, immediate battery disconnect, and operator confirmation')
    from gpiozero import OutputDevice,PWMOutputDevice
    from gpiozero.pins.lgpio import LGPIOFactory
    devices=[]
    def stop(*_):
        if devices:devices[0].off()
        for device in devices[1:]:device.off()
    def interrupted(*_):
        stop()
        raise KeyboardInterrupt
    signal.signal(signal.SIGINT,interrupted)
    signal.signal(signal.SIGTERM,interrupted)
    factory=LGPIOFactory()
    try:
        enable=PWMOutputDevice(19,initial_value=0,frequency=1000,pin_factory=factory);devices.append(enable)
        in1=OutputDevice(5,initial_value=False,pin_factory=factory);devices.append(in1)
        in2=OutputDevice(6,initial_value=False,pin_factory=factory);devices.append(in2)
        in1.on();in2.off()
        start=time.monotonic()
        print(f'PULSE_REQUEST duty={args.duty} duration_seconds={args.seconds}',flush=True)
        enable.value=args.duty
        time.sleep(args.seconds)
        stop()
        print('Pulse command completed; elapsed_seconds=',round(time.monotonic()-start,4),flush=True)
        print('GPIO command success does not prove wheel motion; operator observation required')
    finally:
        stop()
        for device in reversed(devices):device.close()
        factory.close()
if __name__=='__main__':main()
