"""Download an official portable PHP runtime into the system temp directory for validation."""
import json
import pathlib
import urllib.request
import zipfile
import tempfile

target = pathlib.Path(tempfile.gettempdir()) / "spx-mall-validation-php"
target.mkdir(exist_ok=True)
if not (target / "php.exe").exists():
    with urllib.request.urlopen("https://windows.php.net/downloads/releases/releases.json", timeout=30) as response:
        releases = json.load(response)
    release = releases["8.4"]
    package = release["nts-vs17-x64"]["zip"]
    url = "https://windows.php.net/downloads/releases/" + package["path"]
    archive = target / "php.zip"
    urllib.request.urlretrieve(url, archive)
    import hashlib
    if hashlib.sha256(archive.read_bytes()).hexdigest() != package["sha256"]:
        raise RuntimeError("PHP archive checksum mismatch")
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(target)
print(target / "php.exe")
