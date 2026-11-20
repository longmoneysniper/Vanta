import json
import os
import urllib.request
import zipfile
import xml.etree.ElementTree as ET


def install_kodi_repo_zip(zip_url: str, target_dir: str) -> str:
    os.makedirs(target_dir, exist_ok=True)
    zip_path = os.path.join(target_dir, "repo_package.zip")
    urllib.request.urlretrieve(zip_url, zip_path)

    installed_addons = []
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(target_dir)
        for name in zf.namelist():
            if name.endswith("addon.xml"):
                xml_bytes = zf.read(name)
                root = ET.fromstring(xml_bytes)
                addon_id = root.attrib.get("id", "unknown.addon")
                addon_name = root.attrib.get("name", addon_id)
                version = root.attrib.get("version", "1.0.0")
                installed_addons.append({
                    "id": addon_id,
                    "name": addon_name,
                    "version": version
                })

    if os.path.exists(zip_path):
        os.remove(zip_path)

    return json.dumps({"status": "ok", "addons": installed_addons})


def run_kodi_resolver(addon_id: str, query: str) -> str:
    # Emulates Kodi xbmcplugin.setResolvedUrl output for Vanta's UnifiedMediaItem pipeline
    return json.dumps({
        "addon_id": addon_id,
        "query": query,
        "resolved_streams": [
            {
                "label": f"Kodi Bridge ({addon_id}) • 1080p Direct",
                "url": "",
                "headers": {"User-Agent": "Kodi/21.0 (Android; arm64-v8a)"}
            }
        ]
    })
