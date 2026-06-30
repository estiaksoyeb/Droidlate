import os

def get_deepl_api_key() -> str:
    """Retrieves DeepL API key from environment variables."""
    return os.environ.get("DEEPL_API_KEY", "")

def is_deepl_free_api() -> bool:
    """Returns True if the user is using the DeepL Free API (default) or False for Pro."""
    return os.environ.get("DEEPL_FREE_API", "true").lower() == "true"

def get_default_source_lang() -> str:
    """Returns default source language code."""
    return os.environ.get("DEFAULT_SOURCE_LANG", "en")
