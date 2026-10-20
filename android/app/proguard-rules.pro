# kotlinx.serialization porta già le sue regole; qui solo quello che R8 non può dedurre.

# Servizio e provider sono istanziati da Android per nome dal manifest:
# R8 li conserva da sé, ma teniamo esplicite le classi dei modelli Subsonic
# usate dal parser via serializer generati.
-keep class io.github.baldborne94.baldmusic.core.subsonic.** { *; }
-keep class io.github.baldborne94.baldmusic.core.update.** { *; }
-keep class io.github.baldborne94.baldmusic.core.acoustid.** { *; }

# OkHttp 5 fa riferimento opzionale a librerie TLS alternative non incluse.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
