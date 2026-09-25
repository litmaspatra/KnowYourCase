#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent
FILES = [
    ROOT / "src/main/java/com/knowyourcase/notice/MainActivity.kt",
    ROOT / "src/main/java/com/knowyourcase/notice/ModernScannerActivity.kt",
    ROOT / "src/main/java/com/knowyourcase/notice/ECourtWebViewActivity.kt",
]
checks = [
    ("raw text size", re.compile(r"textSize\s*=\s*[0-9.]+f")),
    ("raw dp literal", re.compile(r"\bdp\(\d+\)")),
    ("legacy icon family", re.compile(r"R\.drawable\.(?:ic_action|ic_ui|ic_pixel|ic_scanner)_[A-Za-z0-9_]+")),
    ("Toast feedback", re.compile(r"\bToast\.|\btoast\(")),
    ("hard-coded hex color", re.compile(r"#[0-9A-Fa-f]{6,8}|0x[0-9A-Fa-f]{6,8}")),
    ("hard-coded RGB", re.compile(r"Color\.rgb\(|Color\.(?:BLACK|WHITE|RED|GREEN|BLUE)")),
]
errors = []
for path in FILES:
    text = path.read_text(encoding="utf-8")
    for label, pattern in checks:
        for m in pattern.finditer(text):
            line = text.count("\n", 0, m.start()) + 1
            errors.append(f"{path.name}:{line}: {label}: {m.group(0)}")

tokens = (ROOT / "src/main/java/com/knowyourcase/notice/ui/UiTokens.kt").read_text(encoding="utf-8")
required_spacing = ["XXS = 4", "XS = 8", "SM = 12", "MD = 16", "LG = 24", "XL = 32"]
for item in required_spacing:
    if item not in tokens:
        errors.append(f"UiTokens.kt: missing spacing token {item}")

if errors:
    print("NOTICE TRACKER UI AUDIT FAILED")
    print("\n".join(errors))
    sys.exit(1)

print("NOTICE TRACKER UI AUDIT PASSED")
print("Screen files use centralized type, spacing, color and icon systems.")
