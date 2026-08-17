# Retrofit and Gson inspect API contracts at runtime. The online layer contains
# DTOs in several provider packages, so a single stale package-specific rule can
# silently break release builds after R8 obfuscation.
-keepattributes Signature,*Annotation*
-keep class com.sergey.animevault.data.**Dto { *; }
-keep class com.sergey.animevault.data.**Envelope { *; }
