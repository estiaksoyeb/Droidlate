import requests
from typing import Optional, Protocol
import urllib.parse

class TranslationProvider(Protocol):
    """Protocol defining a translation service provider."""
    @property
    def name(self) -> str:
        ...

    def translate(self, text: str, source_lang: str, target_lang: str) -> Optional[str]:
        ...

class GoogleTranslateProvider:
    """Free Google Translate API client (no API key required)."""
    @property
    def name(self) -> str:
        return "Google Translate"

    def translate(self, text: str, source_lang: str, target_lang: str) -> Optional[str]:
        if not text.strip():
            return ""
        try:
            # Map languages if needed (e.g. Google likes zh-CN or zh)
            url = (
                f"https://translate.googleapis.com/translate_a/single"
                f"?client=gtx&sl={source_lang}&tl={target_lang}&dt=t&q={urllib.parse.quote(text)}"
            )
            response = requests.get(url, timeout=5)
            if response.status_code == 200:
                data = response.json()
                # Parse standard google translate single structure: [[["translated_text", ...]]]
                if data and len(data) > 0 and data[0]:
                    parts = [part[0] for part in data[0] if part and part[0]]
                    return "".join(parts)
            return None
        except Exception:
            return None

class MyMemoryProvider:
    """Free MyMemory API client."""
    @property
    def name(self) -> str:
        return "MyMemory"

    def translate(self, text: str, source_lang: str, target_lang: str) -> Optional[str]:
        if not text.strip():
            return ""
        try:
            lang_pair = f"{source_lang}|{target_lang}"
            url = (
                f"https://api.mymemory.translated.net/get"
                f"?q={urllib.parse.quote(text)}&langpair={urllib.parse.quote(lang_pair)}"
            )
            response = requests.get(url, timeout=5)
            if response.status_code == 200:
                data = response.json()
                if "responseData" in data and "translatedText" in data["responseData"]:
                    return data["responseData"]["translatedText"]
            return None
        except Exception:
            return None

class DeepLProvider:
    """DeepL Translation API client (requires API key)."""
    def __init__(self, api_key: str, is_free_api: bool = True):
        self.api_key = api_key
        self.endpoint = (
            "https://api-free.deepl.com/v2/translate"
            if is_free_api
            else "https://api.deepl.com/v2/translate"
        )

    @property
    def name(self) -> str:
        return "DeepL"

    def translate(self, text: str, source_lang: str, target_lang: str) -> Optional[str]:
        if not self.api_key or not text.strip():
            return None
        try:
            # DeepL target lang must be 2 letters or format like EN-US, PT-BR, etc.
            # Convert target lang to uppercase.
            headers = {
                "Authorization": f"DeepL-Auth-Key {self.api_key}",
                "Content-Type": "application/json"
            }
            body = {
                "text": [text],
                "target_lang": target_lang.upper()
            }
            response = requests.post(self.endpoint, headers=headers, json=body, timeout=5)
            if response.status_code == 200:
                data = response.json()
                if "translations" in data and data["translations"]:
                    return data["translations"][0].get("text")
            return None
        except Exception:
            return None

class GeminiProvider:
    """Gemini API client for context-aware translations."""
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent"
        
    @property
    def name(self) -> str:
        return "Gemini"
        
    def translate(self, text: str, source_lang: str, target_lang: str) -> Optional[str]:
        if not self.api_key or not text.strip():
            return None
        try:
            url = f"{self.endpoint}?key={self.api_key}"
            prompt = (
                f"You are a professional Android application translator.\n"
                f"Translate the following Android resource string from '{source_lang}' to '{target_lang}'.\n"
                f"Rules:\n"
                f"1. Return ONLY the translated string value. Do NOT wrap in XML tags, quotes, or add any explanations.\n"
                f"2. Keep all Java/Android placeholders (e.g. %s, %1$d, %2$s) exactly intact.\n"
                f"3. Preserve HTML/styling tags (e.g. <b>, <i>, <a href=...>).\n\n"
                f"Source: {text}"
            )
            body = {
                "contents": [{
                    "parts": [{"text": prompt}]
                }],
                "generationConfig": {
                    "temperature": 0.1
                }
            }
            response = requests.post(url, json=body, timeout=8)
            if response.status_code == 200:
                data = response.json()
                candidates = data.get("candidates", [])
                if candidates:
                    content = candidates[0].get("content", {})
                    parts = content.get("parts", [])
                    if parts:
                        translation = parts[0].get("text", "").strip()
                        if translation.startswith('"') and translation.endswith('"'):
                            translation = translation[1:-1]
                        return translation
            return None
        except Exception:
            return None

class OpenAIProvider:
    """OpenAI API client for context-aware translations."""
    def __init__(self, api_key: str):
        self.api_key = api_key
        self.endpoint = "https://api.openai.com/v1/chat/completions"
        
    @property
    def name(self) -> str:
        return "GPT-4o Mini"
        
    def translate(self, text: str, source_lang: str, target_lang: str) -> Optional[str]:
        if not self.api_key or not text.strip():
            return None
        try:
            headers = {
                "Authorization": f"Bearer {self.api_key}",
                "Content-Type": "application/json"
            }
            body = {
                "model": "gpt-4o-mini",
                "messages": [
                    {"role": "system", "content": "You are a professional Android application translator. Translate the source text. Return ONLY the translated string value. Do NOT wrap in XML tags, quotes, or add any explanations. Keep all Java/Android format placeholders and HTML tags intact."},
                    {"role": "user", "content": f"Translate this string from '{source_lang}' to '{target_lang}':\n\n{text}"}
                ],
                "temperature": 0.1
            }
            response = requests.post(self.endpoint, headers=headers, json=body, timeout=8)
            if response.status_code == 200:
                data = response.json()
                choices = data.get("choices", [])
                if choices:
                    translation = choices[0].get("message", {}).get("content", "").strip()
                    if translation.startswith('"') and translation.endswith('"'):
                        translation = translation[1:-1]
                    return translation
            return None
        except Exception:
            return None
