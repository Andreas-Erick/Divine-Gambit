# Add project specific ProGuard rules here.

# LiteRT-LM (on-device language models) calls back into its Kotlin classes from native code.
-keep class com.google.ai.edge.litertlm.** { *; }
