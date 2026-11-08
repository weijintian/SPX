"""Prepare an official portable MariaDB runtime for an isolated local test database."""
import hashlib
import pathlib
import tempfile
import urllib.request
import zipfile

target = pathlib.Path(tempfile.gettempdir()) / "spx-mall-validation-mariadb"
target.mkdir(exist_ok=True)
version = "11.4.5"
name = f"mariadb-{version}-winx64.zip"
base = f"https://archive.mariadb.org/mariadb-{version}/winx64-packages/"
server = target / f"mariadb-{version}-winx64" / "bin" / "mariadbd.exe"
if not server.exists():
    archive = target / name
    if not archive.exists():
        urllib.request.urlretrieve(base + name, archive)
    with urllib.request.urlopen(base + "sha256sums.txt", timeout=30) as response:
        checksums = response.read().decode()
    expected = next(line.split()[0] for line in checksums.splitlines() if line.split()[-1].lstrip("*./") == name)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError("MariaDB archive checksum mismatch")
    with zipfile.ZipFile(archive) as bundle:
        bundle.extractall(target)
print(server)
