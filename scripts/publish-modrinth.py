#!/usr/bin/env python3
import os
import sys
import json
import requests

MODRINTH_TOKEN = os.environ.get("MODRINTH_TOKEN")
if not MODRINTH_TOKEN:
    print("Error: MODRINTH_TOKEN environment variable is not set.", file=sys.stderr)
    sys.exit(1)

PROJECT_SLUG = "polymersplitter"
POLYMER_PROJECT_ID = "xGdtZczs"
USER_AGENT = "Kardane/PolymerSplitter/1.0.0 (qkrehf2@naver.com)"
BASE_URL = "https://api.modrinth.com/v2"

headers = {
    "Authorization": MODRINTH_TOKEN,
    "User-Agent": USER_AGENT,
}

print(f"Connecting to Modrinth API for project: {PROJECT_SLUG}...")
proj_resp = requests.get(f"{BASE_URL}/project/{PROJECT_SLUG}", headers=headers)
if proj_resp.status_code != 200:
    print(f"Error fetching project '{PROJECT_SLUG}': {proj_resp.status_code} {proj_resp.text}", file=sys.stderr)
    sys.exit(1)

proj_data = proj_resp.json()
project_id = proj_data["id"]
project_title = proj_data.get("title", PROJECT_SLUG)
print(f"Verified project: {project_title} (ID: {project_id})")

CHANGELOG = """## PolymerSplitter v1.0.0

A server-side Fabric companion mod for [Polymer](https://github.com/Patbox/polymer).
Splits Polymer-generated resource packs into independently cacheable packs per resource namespace, with a dedicated `minecraft.sounds` pack for heavy OGG payloads.

### Highlights
- **Namespace Splitting:** Partitions Polymer's final pack into separate per-namespace ZIPs.
- **Dedicated Audio Pack:** Extracts OGG audio from `assets/minecraft/sounds/` into `minecraft.sounds` (`sounds.json` stays in the primary pack).
- **Primary Pack Preservation:** Root metadata (`pack.mcmeta`, `pack.png`), licenses, and undeclared overlays remain in the primary pack.
- **AutoHost Integration:** Leverages Polymer AutoHost's built-in local providers for hosting and delivery.
- **Fail-Safe Fallback:** Seamlessly falls back to Polymer's original monolithic pack delivery on any failure.
- **Vanilla Compatibility:** No client-side mod required.
"""

VERSIONS = [
    {
        "name": "PolymerSplitter 1.0.0 (1.21.8)",
        "version_number": "1.0.0+1.21.8",
        "file": "versions/mc-1.21.8/build/libs/PolymerSplitter-1.21.8-1.0.jar",
        "game_versions": ["1.21.8"],
    },
    {
        "name": "PolymerSplitter 1.0.0 (1.21.9 - 1.21.10)",
        "version_number": "1.0.0+1.21.10",
        "file": "versions/mc-1.21.10/build/libs/PolymerSplitter-1.21.9-1.21.10-1.0.jar",
        "game_versions": ["1.21.9", "1.21.10"],
    },
    {
        "name": "PolymerSplitter 1.0.0 (1.21.11)",
        "version_number": "1.0.0+1.21.11",
        "file": "versions/mc-1.21.11/build/libs/PolymerSplitter-1.21.11-1.0.jar",
        "game_versions": ["1.21.11"],
    },
    {
        "name": "PolymerSplitter 1.0.0 (26.1 - 26.1.2)",
        "version_number": "1.0.0+26.1",
        "file": "versions/mc-26.1/build/libs/PolymerSplitter-26.1.x-1.0.jar",
        "game_versions": ["26.1", "26.1.1", "26.1.2"],
    },
    {
        "name": "PolymerSplitter 1.0.0 (26.2)",
        "version_number": "1.0.0+26.2",
        "file": "versions/mc-26.2/build/libs/PolymerSplitter-26.2-1.0.jar",
        "game_versions": ["26.2"],
    },
    {
        "name": "PolymerSplitter 1.0.0 (26.3)",
        "version_number": "1.0.0+26.3",
        "file": "versions/mc-26.3/build/libs/PolymerSplitter-26.3-1.0.jar",
        "game_versions": ["26.3"],
    },
]

# Fetch existing version numbers to avoid duplicate conflict
existing_versions_resp = requests.get(f"{BASE_URL}/project/{project_id}/version", headers=headers)
existing_numbers = set()
if existing_versions_resp.status_code == 200:
    for item in existing_versions_resp.json():
        existing_numbers.add(item.get("version_number"))

print(f"Existing version numbers on Modrinth: {existing_numbers or '(none)'}")

for v in VERSIONS:
    file_path = v["file"]
    if not os.path.isfile(file_path):
        print(f"Error: Artifact not found at {file_path}", file=sys.stderr)
        sys.exit(1)

    v_num = v["version_number"]
    if v_num in existing_numbers:
        print(f"Skipping {v['name']} ({v_num}) - already exists on Modrinth.")
        continue

    file_name = os.path.basename(file_path)
    metadata = {
        "name": v["name"],
        "version_number": v_num,
        "changelog": CHANGELOG,
        "dependencies": [
            {
                "project_id": POLYMER_PROJECT_ID,
                "dependency_type": "required",
            }
        ],
        "game_versions": v["game_versions"],
        "version_type": "release",
        "loaders": ["fabric"],
        "featured": True,
        "status": "listed",
        "project_id": project_id,
        "file_parts": ["file"],
        "primary_file": "file",
    }

    print(f"Uploading {v['name']} ({file_name}, game_versions: {v['game_versions']})...")
    with open(file_path, "rb") as f:
        files = {
            "file": (file_name, f, "application/java-archive"),
        }
        data = {
            "data": json.dumps(metadata),
        }
        upload_resp = requests.post(f"{BASE_URL}/version", headers=headers, data=data, files=files)

    if upload_resp.status_code in (200, 201):
        result = upload_resp.json()
        print(f"Successfully uploaded: {v['name']} -> Version ID: {result.get('id')}")
    else:
        print(f"Failed to upload {v['name']}: {upload_resp.status_code} {upload_resp.text}", file=sys.stderr)
        sys.exit(1)

print("All versions successfully published to Modrinth!")
