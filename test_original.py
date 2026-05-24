import sys
sys.path.insert(0, r"D:\LiveSort-reference\LiveSortApp")
from audio_analyzer import extract_features
import json

file_path = r"D:\Cloudmusic\Gothic Storm - Flight Boom.mp3"
result = extract_features(file_path)
print(json.dumps(result, indent=2, ensure_ascii=False))
