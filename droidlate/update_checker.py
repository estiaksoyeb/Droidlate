import os
import json
import time
import requests
from typing import Optional
from . import __version__

def get_update_cache_path() -> str:
    """Returns the path to the global update check cache JSON file."""
    import platform
    home = os.path.expanduser("~")
    if platform.system() == "Windows":
        local_appdata = os.environ.get("LOCALAPPDATA", os.path.join(home, "AppData", "Local"))
        return os.path.join(local_appdata, "droidlate", "update_check.json")
    elif platform.system() == "Darwin": # macOS
        return os.path.join(home, "Library", "Caches", "droidlate", "update_check.json")
    else: # Linux / Termux
        xdg_cache = os.environ.get("XDG_CACHE_HOME", os.path.join(home, ".cache"))
        return os.path.join(xdg_cache, "droidlate", "update_check.json")

def check_for_updates() -> Optional[dict]:
    """
    Checks PyPI for Droidlate updates. Caches the result for 24 hours.
    Returns update info dictionary if a newer version is available, else None.
    """
    cache_path = get_update_cache_path()
    os.makedirs(os.path.dirname(cache_path), exist_ok=True)
    
    current_time = time.time()
    cached_data = {}
    
    # 1. Try to load cached check
    if os.path.exists(cache_path):
        try:
            with open(cache_path, 'r', encoding='utf-8') as f:
                cached_data = json.load(f)
        except Exception:
            pass
            
    last_check = cached_data.get("last_check", 0)
    # Check if 24 hours (86400 seconds) have passed
    if current_time - last_check < 86400:
        if cached_data.get("update_available"):
            return cached_data
        return None
        
    # 2. Query PyPI JSON API
    try:
        url = "https://pypi.org/pypi/droidlate/json"
        response = requests.get(url, timeout=4)
        if response.status_code == 200:
            data = response.json()
            latest_version = data.get("info", {}).get("version")
            if latest_version:
                def parse_ver(v):
                    return tuple(int(x) for x in v.split(".") if x.isdigit())
                
                curr_parsed = parse_ver(__version__)
                late_parsed = parse_ver(latest_version)
                
                update_available = late_parsed > curr_parsed
                
                result = {
                    "last_check": current_time,
                    "current_version": __version__,
                    "latest_version": latest_version,
                    "update_available": update_available
                }
                
                with open(cache_path, 'w', encoding='utf-8') as f:
                    json.dump(result, f, ensure_ascii=False, indent=2)
                    
                if update_available:
                    return result
            else:
                cached_data["last_check"] = current_time
                with open(cache_path, 'w', encoding='utf-8') as f:
                    json.dump(cached_data, f, ensure_ascii=False, indent=2)
        else:
            cached_data["last_check"] = current_time
            with open(cache_path, 'w', encoding='utf-8') as f:
                json.dump(cached_data, f, ensure_ascii=False, indent=2)
    except Exception:
        try:
            cached_data["last_check"] = current_time
            with open(cache_path, 'w', encoding='utf-8') as f:
                json.dump(cached_data, f, ensure_ascii=False, indent=2)
        except Exception:
            pass
            
    return None
