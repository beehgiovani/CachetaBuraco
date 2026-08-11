package com.brunogiovani.cachetaburaco.data.online

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import java.security.MessageDigest
import java.util.UUID

/** Resultado do vinculo de conta Google, distinguindo cancelamento de falha de verdade. */
sealed interface GoogleLinkResult {
    // displayName vem do proprio token do Google -- usado pra ja sugerir o
    // apelido do perfil sem pedir de novo na tela de login.
    data class Success(val displayName: String?) : GoogleLinkResult
    data object Cancelled : GoogleLinkResult
    data object NoGoogleAccountOnDevice : GoogleLinkResult
    data class Failed(val message: String) : GoogleLinkResult
}

/**
 * Vincula a conta Google do aparelho na sessao anonima existente do Supabase
 * (Credential Manager, sem navegador/deep link), preservando o perfil e o
 * ranking que ja existem para o usuario anonimo -- linkIdentityWithIdToken
 * atualiza a mesma identidade em vez de criar uma nova.
 *
 * Precisa de dois cadastros externos previos, sem os quais o pedido falha:
 * 1. Um OAuth Client Android no Google Cloud Console (pacote do app + SHA-1
 *    de assinatura), pra o Google confiar no app que pede o token.
 * 2. "Manual linking" habilitado no Supabase Auth, sem o qual linkIdentity
 *    e linkIdentityWithIdToken sempre recusam o pedido.
 */
class GoogleAccountLinker(
    private val client: SupabaseClient = SupabaseClientProvider.client,
    private val webClientId: String = SupabaseProjectConfig.GOOGLE_WEB_CLIENT_ID
) {
    suspend fun link(context: Context): GoogleLinkResult {
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = MessageDigest.getInstance("SHA-256")
            .digest(rawNonce.toByteArray())
            .joinToString("") { "%02x".format(it) }

        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId)
            .setNonce(hashedNonce)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(option)
            .build()

        val credential = try {
            CredentialManager.create(context).getCredential(context, request).credential
        } catch (e: GetCredentialCancellationException) {
            return GoogleLinkResult.Cancelled
        } catch (e: NoCredentialException) {
            return GoogleLinkResult.NoGoogleAccountOnDevice
        } catch (e: GetCredentialException) {
            return GoogleLinkResult.Failed(e.message ?: "Não foi possível obter a credencial do Google.")
        }

        val googleCredential = try {
            GoogleIdTokenCredential.createFrom(credential.data)
        } catch (e: GoogleIdTokenParsingException) {
            return GoogleLinkResult.Failed("Credencial do Google inválida.")
        }

        return try {
            client.auth.linkIdentityWithIdToken(provider = Google, idToken = googleCredential.idToken) {
                nonce = rawNonce
            }
            GoogleLinkResult.Success(displayName = googleCredential.displayName)
        } catch (e: Exception) {
            GoogleLinkResult.Failed(e.message ?: "Não foi possível vincular a conta Google.")
        }
    }
}
