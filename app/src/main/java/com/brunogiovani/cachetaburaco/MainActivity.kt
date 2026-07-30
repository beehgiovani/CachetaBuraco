package com.brunogiovani.cachetaburaco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.brunogiovani.cachetaburaco.data.network.LocalNetworkRepositoryImpl
import com.brunogiovani.cachetaburaco.data.network.SoloBotNetworkRepository
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.presentation.lobby.LobbyScreen
import com.brunogiovani.cachetaburaco.presentation.login.LoginScreen
import com.brunogiovani.cachetaburaco.presentation.main.MainMenuScreen
import com.brunogiovani.cachetaburaco.presentation.match.MatchScreen
import com.brunogiovani.cachetaburaco.presentation.match.MatchViewModel

enum class AppState { LOGIN, MAIN_MENU, LOBBY_HOST, LOBBY_CLIENT, LOBBY_BOT, MATCH }

/**
 * Entrada principal e roteador simples de telas.
 *
 * Eu deixo a Activity escolhendo qual LocalNetworkRepository a partida usa:
 * - LocalNetworkRepositoryImpl para Wi-Fi/local;
 * - SoloBotNetworkRepository para jogar contra a maquina;
 * - futuramente OnlineNetworkRepository para servidor online.
 *
 * A regra importante e nao colocar logica de jogo aqui. A Activity so escolhe fluxo,
 * config ativa e transporte; quem manda na partida continua sendo MatchViewModel.
 */
class MainActivity : ComponentActivity() {

    private lateinit var networkRepository: LocalNetworkRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        hideSystemBars()

        // ── Inicializa o repositório de autenticação com o contexto ───────────
        // Carrega automaticamente o perfil salvo nas SharedPreferences.
        FakeAuthRepository.init(applicationContext)

        networkRepository = LocalNetworkRepositoryImpl(applicationContext)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember {
                        mutableStateOf(
                            if (FakeAuthRepository.hasSavedProfile()) AppState.MAIN_MENU
                            else AppState.LOGIN
                        )
                    }

                    var isHosting by remember { mutableStateOf(false) }
                    var activeConfig by remember { mutableStateOf(MatchConfig()) }
                    val botRepository = remember { SoloBotNetworkRepository() }
                    // Este repositorio ativo e a "tomada" da mesa. Trocando ele, eu troco
                    // local/bot/online sem duplicar MatchScreen nem MatchViewModel.
                    var activeRepository by remember { mutableStateOf<LocalNetworkRepository>(networkRepository) }

                    when (currentScreen) {
                        AppState.LOGIN -> LoginScreen(
                            onLoginSuccess = { currentScreen = AppState.MAIN_MENU }
                        )

                        AppState.MAIN_MENU -> MainMenuScreen(
                            onLogout = { currentScreen = AppState.LOGIN },
                            onHostRoom = { currentScreen = AppState.LOBBY_HOST },
                            onJoinRoom = { currentScreen = AppState.LOBBY_CLIENT },
                            onPlayBot = { currentScreen = AppState.LOBBY_BOT },
                            onResumeGame = {
                                val savedInfo = MatchViewModel.getSavedGameInfo(applicationContext)
                                if (savedInfo != null) {
                                    isHosting = savedInfo.first
                                    activeConfig = savedInfo.second
                                    activeRepository = networkRepository
                                    currentScreen = AppState.MATCH
                                }
                            }
                        )

                        AppState.LOBBY_HOST -> LobbyScreen(
                            isHosting = true,
                            networkRepository = networkRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU },
                            onGameStarted = { config ->
                                MatchViewModel.clearSavedGame(applicationContext)
                                activeConfig = config
                                isHosting = true
                                activeRepository = networkRepository
                                currentScreen = AppState.MATCH
                            }
                        )

                        AppState.LOBBY_CLIENT -> LobbyScreen(
                            isHosting = false,
                            networkRepository = networkRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU },
                            onGameStarted = { config ->
                                MatchViewModel.clearSavedGame(applicationContext)
                                activeConfig = config
                                isHosting = false
                                activeRepository = networkRepository
                                currentScreen = AppState.MATCH
                            }
                        )

                        AppState.LOBBY_BOT -> LobbyScreen(
                            isHosting = true,
                            singlePlayerMode = true,
                            networkRepository = botRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU },
                            onGameStarted = { config ->
                                MatchViewModel.clearSavedGame(applicationContext)
                                activeConfig = config.copy(maxPlayers = 2)
                                isHosting = true
                                activeRepository = botRepository
                                currentScreen = AppState.MATCH
                            }
                        )

                        AppState.MATCH -> MatchScreen(
                            networkRepository = activeRepository,
                            isHost = isHosting,
                            config = activeConfig,
                            onLeaveMatch = { currentScreen = AppState.MAIN_MENU }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
