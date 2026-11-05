import threading

import serial
import zmq

SERIAL_PORT = "COM5"   
BAUD_RATE = 115200     
PORT_COMMAND = 5555    
PORT_DATA = 5556
PORT_STATUS = 5557

ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=1)

context = zmq.Context()

pull = context.socket(zmq.PULL)
pull.bind(f"tcp://*:{PORT_COMMAND}")

pub = context.socket(zmq.PUB)
pub.bind(f"tcp://*:{PORT_DATA}")

status = context.socket(zmq.REP)
status.bind(f"tcp://*:{PORT_STATUS}")


def baca_stm32():
    while True:
        baris = ser.readline().decode(errors="ignore").strip()
        if baris:
            print("STM32 ->", baris)
            pub.send_string(baris)


def jawab_status():
    while True:
        request = status.recv_string()
        status.send_string("PONG" if request == "PING" else "UNKNOWN")


threading.Thread(target=baca_stm32, daemon=True).start()
threading.Thread(target=jawab_status, daemon=True).start()

print(
    f"Bridge aktif. Command: {PORT_COMMAND}, data: {PORT_DATA}, "
    f"status: {PORT_STATUS}."
)

while True:
    command = pull.recv_string()
    print("Android ->", command)
    ser.write((command + "\n").encode()) 