"""Create the iOS icon from the repository's existing Android brand asset."""
import json
from pathlib import Path
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
source = ROOT.parent / "android-native/app/src/main/res/drawable-nodpi/spx_im_logo.png"
target = ROOT / "SpxChatIOS/Resources/Assets.xcassets/AppIcon.appiconset"
target.mkdir(parents=True, exist_ok=True)
logo = Image.open(source).convert("RGBA")
canvas = Image.new("RGB", (1024, 1024), "white")
logo = ImageOps.contain(logo, (1024, 1024), Image.Resampling.LANCZOS)
canvas.paste(logo, ((1024-logo.width)//2, (1024-logo.height)//2), logo)
canvas.save(target / "AppIcon.png")
(target / "Contents.json").write_text(json.dumps({"images": [{"filename": "AppIcon.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}], "info": {"author": "xcode", "version": 1}}, indent=2), encoding="utf8")
print("Generated opaque 1024x1024 AppIcon from existing SPX logo.")
