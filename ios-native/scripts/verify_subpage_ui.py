"""Compare UI-only subpage changes against the local pre-edit backup."""
from pathlib import Path
import json
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ios-native/.tools/python"))
from tree_sitter import Language, Parser
import tree_sitter_swift

parser = Parser(Language(tree_sitter_swift.language()))
source = ROOT / "ios-native/SpxChatIOS"
backup = ROOT / "output/ui-subpages-backup-20261025"


def members(data):
    found = {}
    for declaration in parser.parse(data).root_node.named_children:
        if declaration.type != "class_declaration":
            continue
        name = next((data[n.start_byte:n.end_byte].decode() for n in declaration.named_children if n.type == "type_identifier"), "")
        body = next((n for n in declaration.named_children if n.type == "class_body"), None)
        if body is None:
            continue
        for method in body.named_children:
            if method.type != "function_declaration":
                continue
            key = next((data[n.start_byte:n.end_byte].decode() for n in method.named_children if n.type == "simple_identifier"), str(method.start_byte))
            found[name + "." + key] = data[method.start_byte:method.end_byte].decode()
    return found


def api_calls(data):
    return sorted(re.findall(r'APIClient\.shared\.(?:get|post)\("([a-z][a-z0-9_.]+)"', data.decode()))


checks = []
expected_method_changes = {"ForwardMessageView.target", "ForwardMessageView.load"}
if not backup.exists():
    sys.exit("Missing local pre-edit backup")
for old in sorted(backup.rglob("*.swift")):
    current = source / old.relative_to(backup)
    before_data, after_data = old.read_bytes(), current.read_bytes()
    before, after = members(before_data), members(after_data)
    checks.append({
        "file": current.relative_to(ROOT).as_posix(),
        "removed_methods": [name for name in before if name not in after],
        "changed_methods": [name for name in before if name in after and before[name] != after[name]],
        "added_methods": [name for name in after if name not in before],
        "preserved_literal_API_calls": api_calls(before_data) == api_calls(after_data),
        "api_count_before": len(api_calls(before_data)),
        "api_count_after": len(api_calls(after_data)),
    })
print(json.dumps(checks, ensure_ascii=False, indent=2))
if len(checks) != 8 or any(
    check["removed_methods"]
    or not check["preserved_literal_API_calls"]
    or set(check["changed_methods"]) - expected_method_changes
    for check in checks
):
    sys.exit(1)
