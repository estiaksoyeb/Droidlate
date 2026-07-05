import os
import re
from typing import List, Tuple
from .apis import TranslationProvider, GoogleTranslateProvider, MyMemoryProvider, DeepLProvider, GeminiProvider, OpenAIProvider
from ..config import get_deepl_api_key, is_deepl_free_api, get_gemini_api_key, get_openai_api_key

def android_locale_to_iso(folder_name: str) -> str:
    """
    Maps an Android locale resource folder name (e.g. values-es, values-zh-rCN)
    to its corresponding ISO 639 standard language code.
    """
    folder = os.path.basename(folder_name)
    if folder == "values":
        return "en"

    # Android locale format: values-XX-rXX or values-XX
    # XX can be 2-3 letters, rXX is region code
    match = re.match(r"^values-([a-z]{2,3})(?:-r([a-zA-Z]{2,4}))?$", folder)
    if match:
        lang = match.group(1)
        region = match.group(2)
        if region:
            # Map common regions or keep as lang-REGION
            return f"{lang}-{region.upper()}"
        return lang
    return "en"

class TranslationOrchestrator:
    """Orchestrates suggestions from multiple TranslationProvider instances."""
    def __init__(self):
        self.reload_providers()
        
    def reload_providers(self):
        """Reloads providers based on the latest global config settings."""
        self.providers: List[TranslationProvider] = [
            GoogleTranslateProvider(),
            MyMemoryProvider()
        ]
        
        # Load DeepL if API key is provided
        deepl_key = get_deepl_api_key()
        if deepl_key:
            self.providers.append(DeepLProvider(deepl_key, is_deepl_free_api()))

        # Load Gemini if API key is provided
        gemini_key = get_gemini_api_key()
        if gemini_key:
            self.providers.append(GeminiProvider(gemini_key))

        # Load OpenAI if API key is provided
        openai_key = get_openai_api_key()
        if openai_key:
            self.providers.append(OpenAIProvider(openai_key))

    def get_suggestions(self, text: str, source_lang: str, target_lang: str) -> List[Tuple[str, str]]:
        """
        Fetches translation suggestions from all configured translation services.
        Catches any issues inside individual providers to fail gracefully.
        """
        suggestions = []
        # Convert languages to standard forms that translation providers like
        src_iso = android_locale_to_iso(source_lang)
        tgt_iso = android_locale_to_iso(target_lang)
        
        for provider in self.providers:
            try:
                res = provider.translate(text, src_iso, tgt_iso)
                if res:
                    suggestions.append((provider.name, res))
            except Exception:
                # Silently catch exceptions to ensure UI doesn't crash on individual provider failure
                pass
        return suggestions
