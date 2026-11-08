"""Offline structural validation, never a replacement for Xcode compilation."""
import json
import pathlib
import plistlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / ".tools" / "python"))
from tree_sitter import Language, Parser
import tree_sitter_swift

parser = Parser(Language(tree_sitter_swift.language()))
failures = []
files = sorted((ROOT / "SpxChatIOS").rglob("*.swift")) + sorted((ROOT / "SpxChatIOSTests").rglob("*.swift"))
for file in files:
    source = file.read_bytes()
    tree = parser.parse(source)
    pending = [tree.root_node]
    while pending:
        node = pending.pop()
        if node.type == "ERROR" or node.is_missing:
            failures.append({"file": str(file.relative_to(ROOT)), "line": node.start_point.row + 1,
                             "kind": node.type, "text": source[node.start_byte:node.end_byte].decode("utf8")[:180]})
        if node.type == "function_declaration":
            parent = node.parent
            while parent is not None:
                if parent.type in ("function_declaration", "computed_property"):
                    failures.append({"file": str(file.relative_to(ROOT)), "line": node.start_point.row + 1, "kind": "nested-function", "text": source[node.start_byte:node.end_byte].decode("utf8")[:100]})
                    break
                parent = parent.parent
        pending.extend(reversed(node.children))
    for line, value in enumerate(source.decode("utf8").splitlines(), 1):
        if "@Environment(" in value and "@Environment(\\." not in value:
            failures.append({"file": str(file.relative_to(ROOT)), "line": line, "kind": "invalid-environment-keypath", "text": value.strip()})

components = (ROOT / "SpxChatIOS" / "Design" / "Components.swift").read_text(encoding="utf8")
color_declarations = components.split("enum SPXColor", 1)[1].split("struct SPXCard", 1)[0]
declared_colors = set(re.findall(r"static let (\w+)", color_declarations))
declared_colors.update(re.findall(r"static func (\w+)\([^)]*\) -> Color", color_declarations))
for file in files:
    source = file.read_text(encoding="utf8")
    for match in re.finditer(r"SPXColor\.(\w+)", source):
        if match.group(1) not in declared_colors:
            failures.append({"file": str(file.relative_to(ROOT)), "line": source.count("\n", 0, match.start()) + 1,
                             "kind": "unknown-spx-color", "text": match.group(0)})

with (ROOT / "SpxChatIOS" / "Info.plist").open("rb") as stream:
    plistlib.load(stream)
for file in (ROOT / "SpxChatIOS/Resources").rglob("*.xcprivacy"):
    with file.open("rb") as stream:
        plistlib.load(stream)
for file in (ROOT / "SpxChatIOS/Resources").rglob("Contents.json"):
    spec = json.loads(file.read_text(encoding="utf8"))
    for image in spec.get("images", []):
        if image.get("filename") and not (file.parent / image["filename"]).is_file():
            failures.append({"file": str(file.relative_to(ROOT)), "kind": "missing-asset", "text": image["filename"]})

backend_files = [f for f in (ROOT.parent / "spxchat").glob("*.php") if "config" not in f.name.lower()]
backend = "\n".join(f.read_text(encoding="utf8", errors="replace") for f in backend_files)
api_actions = set()
for file in files:
    source = file.read_text(encoding="utf8")
    api_actions.update(re.findall(r'APIClient\.shared\.(?:get|post|request|data|backgroundUploadRequest)\("([a-z][a-z0-9_.]+)"', source))
    if file.name == "APIClient.swift":
        api_actions.update(re.findall(r'\bget\("([a-z][a-z0-9_.]+)"', source))
contract = ROOT / "scripts" / "backend-actions.json"
if not contract.is_file():
    raise SystemExit("scripts/backend-actions.json is required for reproducible CI API validation")
known_actions = set(json.loads(contract.read_text(encoding="utf8"))["actions"])
snapshot_missing_actions = sorted(api_actions - known_actions)
if backend_files:
    unknown_actions = sorted(action for action in api_actions if action not in backend)
    action_validation_source = "local_backend"
else:
    unknown_actions = sorted(api_actions - known_actions)
    action_validation_source = "exported_backend_action_snapshot_not_live_server"

policies = []
for name in ("privacy", "agreement"):
    file = ROOT / "SpxChatIOS/Resources" / f"{name}.txt"
    if not file.is_file() or len(file.read_text(encoding="utf8").strip()) < 300:
        failures.append({"file": str(file.relative_to(ROOT)), "kind": "missing-policy", "text": name})
    else:
        policies.append(str(file.relative_to(ROOT)))

report = {"swift_files": len(files), "lines": sum(len(f.read_text(encoding="utf8").splitlines()) for f in files),
          "syntax_errors": failures, "literal_api_actions": len(api_actions), "actions_not_found_in_local_backend": unknown_actions,
          "actions_missing_from_backend_snapshot": snapshot_missing_actions,
          "api_action_validation_source": action_validation_source,
          "policy_resources": policies, "xcode_compilation": "not_run", "unit_tests": "not_run", "device_testing": "not_run"}
output = ROOT / "Validation"
output.mkdir(exist_ok=True)
(output / "source-validation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
print(json.dumps(report, ensure_ascii=True, indent=2))
sys.exit(bool(failures or unknown_actions or snapshot_missing_actions))
