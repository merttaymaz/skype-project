"""
Mock STT servisi — test ve development icin.
Gelen audio dosyasini "alir" ve sahte bir transkript dondurur.
"""
from flask import Flask, request, jsonify
import os
import time
import uuid

app = Flask(__name__)

@app.route("/api/v1/transcribe", methods=["POST"])
def transcribe():
    if "file" not in request.files:
        return jsonify({"error": "file parametresi gerekli"}), 400

    audio_file = request.files["file"]
    file_size = 0
    # Dosyayi oku (boyutu hesapla ama kaydetme)
    data = audio_file.read()
    file_size = len(data)

    # Opsiyonel metadata
    language = request.form.get("language", "tr")
    conference_uri = request.form.get("conference_uri", "unknown")
    sample_rate = request.form.get("sample_rate", "16000")

    # Isleme suresi simule et (dosya boyutuna gore)
    processing_time = max(0.5, file_size / 100000)
    time.sleep(min(processing_time, 3))  # Max 3 saniye bekle

    transcript_id = str(uuid.uuid4())[:8]

    return jsonify({
        "text": f"[MOCK] Bu bir test transkriptidir (id={transcript_id}). "
                f"Dosya: {audio_file.filename}, boyut: {file_size} bytes, "
                f"dil: {language}, conference: {conference_uri}",
        "language": language,
        "duration": file_size / int(sample_rate) / 2,  # Tahmini sure (16bit mono)
        "segments": [
            {
                "start": 0.0,
                "end": 5.0,
                "text": f"[MOCK] Konusma baslangici — {audio_file.filename}"
            }
        ]
    })


@app.route("/health", methods=["GET"])
def health():
    return jsonify({"status": "OK", "service": "mock-stt"})


if __name__ == "__main__":
    port = int(os.environ.get("PORT", 9090))
    print(f"Mock STT servisi baslatiliyor: http://0.0.0.0:{port}")
    app.run(host="0.0.0.0", port=port, debug=True)
