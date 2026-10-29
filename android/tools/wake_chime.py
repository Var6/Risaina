#!/usr/bin/env python3
"""Writes app/src/main/res/raw/wake_alarm.wav, the Wake together alarm tone.

A soft rising three-note bell (G5, B5, D6) that is played twice, then a short rest, so it loops
calmly while the alarm notification rings (FLAG_INSISTENT repeats the whole file). Made here from
sine partials, so the tone is ours to ship and needs no library: python3 tools/wake_chime.py
"""
import math
import os
import struct
import wave

RATE = 16000
LENGTH_S = 2.6
NOTES = [(0.00, 783.99), (0.16, 987.77), (0.32, 1174.66), (1.10, 783.99), (1.26, 987.77), (1.42, 1174.66)]
# A bell: the fundamental plus two quieter, slightly stretched partials that fade faster.
PARTIALS = [(1.0, 1.0, 0.55), (2.01, 0.28, 0.30), (3.02, 0.08, 0.18)]
ATTACK_S = 0.008
PEAK = 0.62


def sample(t: float) -> float:
    v = 0.0
    for start, freq in NOTES:
        dt = t - start
        if dt < 0:
            continue
        attack = min(1.0, dt / ATTACK_S)
        for ratio, gain, tau in PARTIALS:
            v += attack * gain * math.exp(-dt / tau) * math.sin(2 * math.pi * freq * ratio * dt)
    return v


def main() -> None:
    n = int(RATE * LENGTH_S)
    raw = [sample(i / RATE) for i in range(n)]
    loudest = max(abs(x) for x in raw) or 1.0
    # A 30 ms fade at the end, so the loop restarts without a click.
    fade = int(RATE * 0.03)
    frames = bytearray()
    for i, x in enumerate(raw):
        g = min(1.0, (n - i) / fade)
        frames += struct.pack('<h', int(x / loudest * PEAK * g * 32767))
    out = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw', 'wake_alarm.wav')
    with wave.open(os.path.normpath(out), 'wb') as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(bytes(frames))


if __name__ == '__main__':
    main()
