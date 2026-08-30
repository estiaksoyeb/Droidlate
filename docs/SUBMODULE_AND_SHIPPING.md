# Embedded Python Engine & Shipping Architecture

## 1. Unified Monorepo Architecture

The Python engine ([`droidlate/`](file:///root/Projects/Droidlate/droidlate)) is the **Single Source of Truth** for all XML round-trip parsing, diffing, hashing, and translation logic.

In the unified monorepo architecture:
1. **Zero Logic Duplication:** The Python code is shared directly between the CLI, Web SPA, and Android mobile app.
2. **Zero Git Submodule Overhead:** No detached HEADs, submodule initialization, or double-commit synchronization.
3. **Instant Availability:** Any Python fix in `droidlate/` is immediately available to the Android app during build time.

---

## 2. Monorepo Structure

```text
Droidlate/
├── droidlate/              # 🐍 Python Core Package (CLI & Web Engine)
│   ├── parser/
│   ├── translator/
│   └── web/
├── app/                    # 📱 Native Android Application (Jetpack Compose)
│   ├── src/main/kotlin/
│   ├── src/main/res/
│   └── build.gradle.kts
├── docs/                   # 📖 Specifications & Guides
├── pyproject.toml          # 📦 Scoped packaging (publishes ONLY droidlate/ to PyPI)
└── gradlew                 # 🛠️ Android build tool
```

---

## 3. How Chaquopy Ships the Python Engine into the APK

Chaquopy embeds standard CPython (version 3.11) directly inside the Android APK.

### Automated Gradle Sync (`app/build.gradle.kts`):

An automated Gradle `Sync` task synchronizes the root `droidlate/` package into `app/build/generated/python_src/droidlate/` before Chaquopy processes sources:

```kotlin
chaquopy {
    defaultConfig {
        version = "3.11"
        
        sourceSets {
            getByName("main") {
                srcDir(layout.buildDirectory.dir("generated/python_src"))
            }
        }
        
        pip {
            install("flask>=3.0.0")
            install("requests>=2.31.0")
        }
    }
}

val syncPythonSources by tasks.registering(Sync::class) {
    from("${rootProject.projectDir}/droidlate")
    into(layout.buildDirectory.dir("generated/python_src/droidlate"))
}

tasks.matching { it.name.contains("Python") && it.name != "syncPythonSources" }.configureEach {
    dependsOn(syncPythonSources)
}
```

### What happens at Build Time:
1. `syncPythonSources` ensures `droidlate` is up-to-date in `generated/python_src/droidlate`.
2. Chaquopy packages the Python source files and pip wheels (`flask`, `requests`, `markupsafe`, etc.) into the APK asset bundle.
3. Android Gradle Plugin links native CPython runtime libraries (`libcrypto.so`, `libpython3.11.so`, etc.) for `arm64-v8a` and `x86_64`.

### What happens at Runtime on Android Device:
1. On app launch, Kotlin calls `Python.start(AndroidPlatform(context))`.
2. Chaquopy initializes the CPython runtime in memory and extracts assets to internal app storage.
3. Kotlin starts the background Flask server on `127.0.0.1` and communicates via Retrofit REST calls.

