# NDK Setup on Android (ARM64)

> **IMPORTANT:** This guide is designed for **proot** and **chroot** environments (like Ubuntu on Android). Native Termux users should prefer using the NDK provided by the `tur-repo` or community-patched archives, which handle these linker and architecture issues natively.

> How to bypass "Exec format error" and "Unknown host CPU architecture" when building native code.

Standard Android NDK distributions from Google contain `x86_64` binaries for tools like `clang`, `make`, and `yasm`. Just like the SDK's AAPT2, these will not run on an ARM64 phone, even inside a Linux chroot or Termux.

This guide documents the "Symlink & Patch" method to make the NDK functional on an Android device.

---

## 1. The Core Problem: Binary Mismatch
When you run `ndk-build` or a Gradle native build on a phone, it fails because:
1. The host architecture (`aarch64`) is not recognized by NDK scripts.
2. The bundled `clang` and `make` binaries are compiled for `x86_64`.
3. Linker errors occur because `libgcc.a` and other runtime libraries are misplaced or missing for the custom environment.

---

## 2. Prerequisites: System Tools
You must install native ARM64 versions of the compilation tools into your Linux environment (Ubuntu chroot/Termux):

```bash
apt update
apt install -y clang-18 lld-18 llvm-18 make ninja-build patchelf file
```

---

## 3. Extracting and Patching the NDK

1. **Extract to the SDK directory:**
   ```bash
   mkdir -p $ANDROID_HOME/ndk
   tar -xf /path/to/android-ndk-r27-linux.tar.xz -C $ANDROID_HOME/ndk/
   ```

2. **Patch the Host Detection:**
   Edit `$ANDROID_HOME/ndk/<version>/build/tools/ndk_bin_common.sh` to allow `aarch64`.
   Find the `HOST_ARCH` case block and change it:
   ```bash
   # Change 'arm64)' to 'arm64|aarch64)'
   case $HOST_ARCH in
     arm64|aarch64) HOST_ARCH=x86_64;; # We map to x86_64 folder but use symlinks
     ...
   ```

3. **Mass Symlink Native Tools:**
   Navigate to the NDK toolchain directory and replace x86 binaries with your system's native ARM64 binaries:
   ```bash
   cd $ANDROID_HOME/ndk/<version>/toolchains/llvm/prebuilt/linux-x86_64/bin/
   
   # Example for clang
   mv clang clang.bak
   ln -s /usr/bin/clang-18 clang
   
   # Repeat for: clang++, lld, ld.lld, llvm-ar, llvm-as, llvm-nm, etc.
   ```
   *Also symlink `/usr/bin/make` into `$ANDROID_HOME/ndk/<version>/prebuilt/linux-x86_64/bin/make`.*

---

## 4. Automation: The Patcher Script

To avoid doing these steps manually every time you download a new NDK, you can use the `patch_ndk.sh` script included in this repository.

### How to use:
1.  **Download and Extract** your new NDK.
2.  **Run the script** pointing to that folder:
    ```bash
    chmod +x patch_ndk.sh
    ./patch_ndk.sh /opt/android-sdk-custom/android-sdk/ndk/your-new-version
    ```

The script will automatically patch the detection logic, symlink your system's `clang/make`, and fix the ARM64 library paths.

---

## 5. Fixing Linker "Library Not Found" Errors

Modern NDKs (r23+) often fail to find `libgcc` or `libatomic` during the `TryCompile` phase of CMake.

1. **Locate the libraries** in the NDK (usually under `lib/clang/<version>/lib/linux/aarch64/`).
2. **Symlink them to the sysroot** (e.g., `.../sysroot/usr/lib/aarch64-linux-android/24/`).
3. **Create a `libgcc.a` Linker Script:**
   Since `clang` looks for `libgcc.a`, create a text file at that path with these contents:
   ```text
   INPUT(libclang_rt.builtins-aarch64-android.a libunwind.a)
   ```

---

## 5. Multi-Architecture Support (armv7a, x86, etc.)

The compiler (`clang`) is a cross-compiler and can build for any architecture. However, you must repeat the **Step 4 (Library Fix)** for every ABI you want to support.

For **armeabi-v7a**:
1.  Locate the 32-bit ARM directory: `.../sysroot/usr/lib/arm-linux-androideabi/<api>/`
2.  Symlink the 32-bit versions of `libatomic.a` and `libunwind.a` into it.
3.  Create a `libgcc.a` script in that directory:
    ```text
    INPUT(libclang_rt.builtins-arm-android.a libunwind.a)
    ```

---

## 6. Gradle Configuration

In your `app/build.gradle`, explicitly pin the NDK version and restrict the ABI to avoid unnecessary (and failing) cross-compilation for x86:

```kotlin
android {
    ndkVersion "27.0.12077973" // Match your installed folder name

    defaultConfig {
        ndk {
            abiFilters "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                abiFilters "arm64-v8a"
            }
        }
    }
}
```

---

## 6. Summary Checklist
- [ ] Native `clang/make/ninja` installed via `apt`.
- [ ] `ndk_bin_common.sh` patched to recognize `aarch64`.
- [ ] All binaries in NDK `bin/` symlinked to `/usr/bin/` equivalents.
- [ ] `libgcc.a` dummy script created in sysroot.
- [ ] `abiFilters "arm64-v8a"` set in Gradle.

---

## 7. Portability & Future Versions

### Using Newer NDK Versions (r28, r29+)
You can apply these exact same steps to any official NDK downloaded from Google. The logic is always:
1.  **Map** the host (`aarch64` -> `x86_64`).
2.  **Swap** the dead binaries (`x86_64`) for live ones (`/usr/bin/clang`, etc.).
3.  **Redirect** the linker (`libgcc.a` script).

### Archiving Your Patched NDK
The NDK you just patched is now a "portable" ARM64-Ubuntu toolchain. You can archive it into a `.tar.xz` file to use on other devices or after a clean install.

**To create your own "pre-patched" archive:**
```bash
# Navigate to the ndk folder
cd /opt/android-sdk-custom/android-sdk/ndk/

# Create the archive (preserves symlinks)
tar -cJf my-portable-ndk-r27-arm64.tar.xz 27.0.12077973/
```

Next time, you simply extract this archive, and `ndk-build` will work immediately without any of the hassle.

---

**Next Step:** Refer to [build-with-termux.md](./build-with-termux.md) for general SDK and Environment setup.
