import os
import json
import time
import requests
from typing import Optional
from . import __version__

def get_installed_version() -> str:
    """Returns the installed package version from metadata, falling back to hardcoded __version__."""
    try:
        from importlib.metadata import version
        return version("droidlate")
    except Exception:
        return __version__

def parse_ver(v: str) -> tuple:
    return tuple(int(x) for x in v.split(".") if x.isdigit())

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
            
    current_version = get_installed_version()
    last_check = cached_data.get("last_check", 0)
    latest_version = cached_data.get("latest_version")
    
    # Check if 24 hours (86400 seconds) have passed
    if current_time - last_check < 86400 and latest_version:
        curr_parsed = parse_ver(current_version)
        late_parsed = parse_ver(latest_version)
        if late_parsed > curr_parsed:
            return {
                "current_version": current_version,
                "latest_version": latest_version,
                "update_available": True
            }
        return None
        
    # 2. Query PyPI JSON API
    try:
        url = "https://pypi.org/pypi/droidlate/json"
        response = requests.get(url, timeout=4)
        if response.status_code == 200:
            data = response.json()
            latest_version = data.get("info", {}).get("version")
            if latest_version:
                result = {
                    "last_check": current_time,
                    "latest_version": latest_version
                }
                
                try:
                    with open(cache_path, 'w', encoding='utf-8') as f:
                        json.dump(result, f, ensure_ascii=False, indent=2)
                except Exception:
                    pass
                    
                curr_parsed = parse_ver(current_version)
                late_parsed = parse_ver(latest_version)
                
                if late_parsed > curr_parsed:
                    return {
                        "current_version": current_version,
                        "latest_version": latest_version,
                        "update_available": True
                    }
                return None
                
        # If status code is not 200 or version not found in info
        # Update last_check to avoid spamming the API on every run
        save_fallback_cache(cache_path, current_time, latest_version)
    except Exception:
        save_fallback_cache(cache_path, current_time, latest_version)
            
    return None

def save_fallback_cache(cache_path: str, current_time: float, latest_version: Optional[str]):
    try:
        cache_data = {"last_check": current_time}
        if latest_version:
            cache_data["latest_version"] = latest_version
        with open(cache_path, 'w', encoding='utf-8') as f:
            json.dump(cache_data, f, ensure_ascii=False, indent=2)
    except Exception:
        pass
