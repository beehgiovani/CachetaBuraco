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

# WorkManager (dependencia transitiva, nao usada direto pelo app -- entra via
# alguma lib como o AdMob) se auto-inicia num ContentProvider antes de
# qualquer codigo nosso rodar, e usa reflection pra instanciar a classe do
# banco Room gerada (WorkDatabase_Impl). Sem manter essa classe o app crasha
# na abertura, sempre, pra todo mundo -- achado testando o build de release
# de verdade (so debug tinha sido testado a sessao inteira ate aqui).
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.** { *; }
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.work.**
