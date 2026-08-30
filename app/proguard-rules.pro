# Retain serialization models for Retrofit and Gson
-keepclassmembers class com.droidlate.app.core.model.** { *; }
-keepclassmembers class com.droidlate.app.core.network.** { *; }
-keep class com.droidlate.app.core.model.** { *; }
-keep class com.droidlate.app.core.network.** { *; }

# Retain Retrofit interface definitions
-keep interface com.droidlate.app.core.network.DroidlateApiService { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*

# Gson rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Chaquopy Python runtime
-keep class com.chaquo.python.** { *; }
-keep interface com.chaquo.python.** { *; }