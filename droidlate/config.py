import os
import json

CONFIG_PATH = os.path.expanduser("~/.droidlate_config.json")

def load_settings() -> dict:
    """Loads application settings from the global config JSON file."""
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, 'r', encoding='utf-8') as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def save_settings(settings: dict) -> None:
    """Saves application settings to the global config JSON file."""
    try:
        with open(CONFIG_PATH, 'w', encoding='utf-8') as f:
            json.dump(settings, f, ensure_ascii=False, indent=2)
    except Exception:
        pass

def get_deepl_api_key() -> str:
    """Retrieves DeepL API key from settings or environment variables."""
    settings = load_settings()
    return settings.get("DEEPL_API_KEY", os.environ.get("DEEPL_API_KEY", ""))

def is_deepl_free_api() -> bool:
    """Returns True if the user is using the DeepL Free API (default) or False for Pro."""
    settings = load_settings()
    val = settings.get("DEEPL_FREE_API", os.environ.get("DEEPL_FREE_API", "true"))
    if isinstance(val, bool):
        return val
    return val.lower() == "true"

def get_gemini_api_key() -> str:
    """Retrieves Gemini API key from settings or environment variables."""
    settings = load_settings()
    return settings.get("GEMINI_API_KEY", os.environ.get("GEMINI_API_KEY", ""))

def get_openai_api_key() -> str:
    """Retrieves OpenAI API key from settings or environment variables."""
    settings = load_settings()
    return settings.get("OPENAI_API_KEY", os.environ.get("OPENAI_API_KEY", ""))

def get_default_source_lang() -> str:
    """Returns default source language code."""
    return os.environ.get("DEFAULT_SOURCE_LANG", "en")
