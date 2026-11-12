# EasyCar release shrinking rules.

# Records, frames, results and Gleo payloads are stored and exchanged as Gson JSON built by
# reflection from field names. Renaming or removing any field would break saved records, so the
# serialized model packages are kept whole (names, fields, enum constants).
-keep class com.easycar.core.** { *; }
-keep class com.example.easycar.data.** { *; }
-keep class com.example.easycar.gleo.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Pleos SDKs talk to vehicle services over AIDL and not every AAR ships consumer rules
# (TextToSpeech 3.3.1 has none). Keep them as delivered.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**

# android.car is provided by the vehicle platform at runtime (useLibrary), not packaged.
-dontwarn android.car.**
