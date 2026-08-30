# Android App Signing Guide

This guide describes how to configure automatic signing for your Android app, both locally in `build.gradle` and automatically in your GitHub Actions CI/CD workflows.

---

## 1. Generate a Keystore File

If you don't have a keystore file yet, you can generate one using the Java Development Kit's `keytool` utility. Run the following command in your terminal:

```bash
keytool -genkey -v -keystore release.keystore -alias releaseKey -keyalg RSA -keysize 2048 -validity 10000
```

This command will ask you for details such as password, name, and organization. It outputs a `release.keystore` (or `.jks`) file.

> [!CAUTION]
> **Never commit your Keystore file (`.keystore` or `.jks`) or its passwords directly to a public Git repository.** 
> Always add keystore files to your `.gitignore` to keep them private.

---

## 2. Configure Gradle for Automated Signing

You can configure Gradle to read signing credentials from environment variables or a local configuration file (like `local.properties` which is git-ignored). This keeps credentials out of the codebase while enabling automated local and CI builds.

Modify your `app/build.gradle` to include a `signingConfigs` block:

```groovy
android {
    ...
    signingConfigs {
        release {
            // Read properties from environment variables (CI/CD) or system properties (local)
            def keystoreFile = System.getenv("RELEASE_KEYSTORE_FILE") ?: project.findProperty("RELEASE_KEYSTORE_FILE")
            def keystorePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD")
            def keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: project.findProperty("RELEASE_KEY_ALIAS")
            def keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("RELEASE_KEY_PASSWORD")

            // Only configure signing if all values are present
            if (keystoreFile && keystorePassword && keyAlias && keyPassword) {
                storeFile file(keystoreFile)
                storePassword keystorePassword
                keyAlias keyAlias
                keyPassword keyPassword
            }
        }
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            
            // Apply signing configuration to release build
            signingConfig signingConfigs.release
        }
    }
}
```

### Local Development Setup
To build a signed release APK locally on your machine without hardcoding credentials in Gradle, add the following to your `local.properties` (or `~/.gradle/gradle.properties`):

```properties
RELEASE_KEYSTORE_FILE=/path/to/your/release.keystore
RELEASE_KEYSTORE_PASSWORD=your_keystore_password
RELEASE_KEY_ALIAS=releaseKey
RELEASE_KEY_PASSWORD=your_key_password
```

---

## 3. Configure Automated Signing in GitHub Actions

To sign your release builds in GitHub Actions automatically:

### Step 3.1: Encode Keystore to Base64
GitHub Secrets cannot store binary files directly. You must convert your keystore file into a Base64 string first:

```bash
# On macOS/Linux:
base64 -i release.keystore | pbcopy # Copies Base64 string to clipboard

# On Windows (PowerShell):
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.keystore")) | clip
```

### Step 3.2: Add Secrets to GitHub Repository
Go to your GitHub repository -> **Settings** -> **Secrets and variables** -> **Actions** and add the following **Repository Secrets**:

1. `RELEASE_KEYSTORE_BASE64`: The Base64 encoded string of your keystore file.
2. `RELEASE_KEYSTORE_PASSWORD`: The password for your keystore.
3. `RELEASE_KEY_ALIAS`: The alias of your key.
4. `RELEASE_KEY_PASSWORD`: The password for your key.

### Step 3.3: Reference Secrets in GitHub Actions Workflow
In your GitHub Actions workflow file (e.g. `.github/workflows/release.yml`), add steps to decode the keystore and pass the environment variables to Gradle:

```yaml
      # 1. Decode Keystore from Base64 secret
      - name: Decode Keystore
        env:
          RELEASE_KEYSTORE_BASE64: ${{ secrets.RELEASE_KEYSTORE_BASE64 }}
        run: |
          echo "$RELEASE_KEYSTORE_BASE64" | base64 --decode > app/release-keystore.jks

      # 2. Build Signed Release Bundle/APK
      - name: Build Signed Release APK & AAB
        env:
          RELEASE_KEYSTORE_FILE: release-keystore.jks # relative to the app module
          RELEASE_KEYSTORE_PASSWORD: ${{ secrets.RELEASE_KEYSTORE_PASSWORD }}
          RELEASE_KEY_ALIAS: ${{ secrets.RELEASE_KEY_ALIAS }}
          RELEASE_KEY_PASSWORD: ${{ secrets.RELEASE_KEY_PASSWORD }}
        run: ./gradlew assembleRelease bundleRelease

      # 3. Clean up the Keystore file (Security Best Practice)
      - name: Clean up Keystore
        if: always()
        run: rm -f app/release-keystore.jks
```

By following this approach, your repository remains clean and secure, while your CI/CD pipeline can safely produce fully-signed production APKs and App Bundles ready for publication to the Google Play Store!
