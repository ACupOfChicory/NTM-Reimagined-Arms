"""Make the AI mobs' call-outs (AiVoice): each line in each voice, spoken by Kokoro, put through a radio, as OGG.

    python3 tools/voice/make_voices.py --tts <sherpa-onnx-offline-tts> --model <kokoro-multi-lang-v1_0 dir> pick
        speaks a probe line in each of the candidate speakers and prints its pitch (median F0), to choose VOICES
    python3 tools/voice/make_voices.py --tts <sherpa-onnx-offline-tts> --model <kokoro-multi-lang-v1_0 dir> make
        writes src/main/resources/assets/ntmgunfeel/sounds/ai/voice/v<n>/<line><k>.ogg and their sounds.json entries

The voice model: Kokoro-82M v1.0 (hexgrad), Apache 2.0, in sherpa-onnx's export; run by sherpa-onnx (k2-fsa),
Apache 2.0 (third-party/ai-voices.txt). Neither goes into the mod, only the lines they spoke.
Needs ffmpeg (libvorbis) and numpy.
"""
import argparse
import json
import pathlib
import subprocess
import sys
import tempfile
import wave

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/ntmgunfeel/sounds/ai/voice"
SOUNDS_JSON = ROOT / "src/main/resources/assets/ntmgunfeel/sounds.json"

# AiVoice.Line ids and what is said, two takes each
LINES = {
    "contact": ["Contact!", "Enemy spotted!"],
    "frag_out": ["Frag out!", "Grenade out!"],
    "smoke_out": ["Smoke out!", "Popping smoke!"],
    "grenade": ["Grenade!", "Grenade, get down!"],
    "reloading": ["Reloading!", "Changing mags!"],
    "falling_back": ["Falling back!", "Pulling back!"],
    "flanking": ["Flanking!", "Moving round the side!"],
    "man_down": ["Man down!", "We lost one!"],
    "moving": ["Moving!", "Changing position!"],
    "hit": ["I'm hit!", "Taking fire!"],
}
# Kokoro v1.0's English men's voices, sherpa-onnx speaker ids
SPEAKERS = {"am_adam": 11, "am_echo": 12, "am_eric": 13, "am_fenrir": 14, "am_liam": 15, "am_michael": 16,
            "am_onyx": 17, "am_puck": 18, "bm_daniel": 24, "bm_fable": 25, "bm_george": 26, "bm_lewis": 27}
# the voices (AiVoice.VOICES), chosen with `pick` apart in pitch: low (127 Hz), a British one (140), high (162)
VOICES = ["am_michael", "bm_fable", "am_fenrir"]
# a call-out is quicker than reading aloud
LENGTH = 0.9
PROBE = "Contact! Enemy spotted. Falling back, cover me!"
# how far the sound carries (sounds.json attenuation_distance; AiVoice.RANGE alike)
ATTENUATION = 32

# the radio: a narrow band, squeezed and a little overdriven, a hiss under it, and the key's click before and after
RADIO = (
    "highpass=f=320,lowpass=f=3200,acompressor=threshold=0.08:ratio=6:attack=5:release=80:makeup=3,"
    "asoftclip=type=tanh,volume=0.9"
)


def speak(tts, model, speaker, text, wav):
    m = pathlib.Path(model)
    subprocess.run([tts, f"--kokoro-model={m / 'model.onnx'}", f"--kokoro-voices={m / 'voices.bin'}",
                    f"--kokoro-tokens={m / 'tokens.txt'}", f"--kokoro-lexicon={m / 'lexicon-us-en.txt'}",
                    f"--kokoro-length-scale={LENGTH}", "--num-threads=2", f"--sid={SPEAKERS[speaker]}",
                    f"--output-filename={wav}", text], check=True, capture_output=True)


def pitch(wav):
    """median F0 of the voiced frames, Hz (an autocorrelation per 40 ms)"""
    w = wave.open(str(wav))
    sr = w.getframerate()
    x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(float) / 32768
    n = int(0.04 * sr)
    out = []
    for i in range(0, len(x) - n, n // 2):
        fr = x[i:i + n] * np.hanning(n)
        if np.sqrt(np.mean(fr ** 2)) < 0.02:
            continue
        ac = np.correlate(fr, fr, "full")[n - 1:]
        lo, hi = int(sr / 300), int(sr / 70)
        k = lo + int(np.argmax(ac[lo:hi]))
        if ac[k] > 0.4 * ac[0]:
            out.append(sr / k)
    return float(np.median(out)) if out else 0.0


def radio(src, dst):
    """src wav through the radio into dst ogg: a click, the line, a click; mono 22.05 kHz"""
    graph = (
        f"[0:a]{RADIO}[v];"
        "anoisesrc=d=0.05:c=white:a=0.25,highpass=f=1500,lowpass=f=4000[c1];"
        "anoisesrc=d=0.07:c=white:a=0.2,highpass=f=1500,lowpass=f=4000[c2];"
        "[c1][v][c2]concat=n=3:v=0:a=1[l];"
        "anoisesrc=c=pink:a=0.012,highpass=f=320,lowpass=f=3200[h];"
        "[l][h]amix=inputs=2:duration=first:normalize=0,aformat=sample_rates=22050:channel_layouts=mono[o]"
    )
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(src), "-filter_complex", graph, "-map", "[o]",
                    "-c:a", "libvorbis", "-q:a", "3", str(dst)], check=True)


def main():
    a = argparse.ArgumentParser()
    a.add_argument("--tts", required=True)
    a.add_argument("--model", required=True)
    a.add_argument("what", choices=["pick", "make"])
    args = a.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        tmp = pathlib.Path(tmp)
        if args.what == "pick":
            for s in SPEAKERS:
                w = tmp / f"{s}.wav"
                speak(args.tts, args.model, s, PROBE, w)
                print(f"{s}\t{pitch(w):.0f}")
            return
        if None in VOICES:
            sys.exit("choose VOICES first (pick)")
        sounds = json.loads(SOUNDS_JSON.read_text())
        sounds = {k: v for k, v in sounds.items() if not k.startswith("ai.voice.")}
        for v, speaker in enumerate(VOICES, 1):
            d = OUT / f"v{v}"
            d.mkdir(parents=True, exist_ok=True)
            for line, takes in LINES.items():
                names = []
                for k, text in enumerate(takes, 1):
                    w = tmp / "take.wav"
                    speak(args.tts, args.model, speaker, text, w)
                    radio(w, d / f"{line}{k}.ogg")
                    names.append({"name": f"ntmgunfeel:ai/voice/v{v}/{line}{k}", "attenuation_distance": ATTENUATION})
                sounds[f"ai.voice.v{v}.{line}"] = {"sounds": names, "subtitle": f"subtitles.ntmgunfeel.ai.voice.{line}"}
        SOUNDS_JSON.write_text(json.dumps(sounds, indent=2) + "\n")


if __name__ == "__main__":
    main()
