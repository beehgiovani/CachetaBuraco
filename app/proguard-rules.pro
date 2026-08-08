# Project-specific ProGuard/R8 rules.
#
# Keep this file even while it is empty: app/build.gradle.kts references it for
# release builds, and Play Store artifacts should be generated from release tasks.

# Login nativo com Google via Credential Manager -- regra recomendada pela
# propria documentacao do androidx.credentials pra manter a implementacao
# baseada em Play Services (credentials-play-services-auth) em release.
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** {
  *;
}
