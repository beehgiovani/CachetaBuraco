package com.brunogiovani.cachetaburaco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.brunogiovani.cachetaburaco.data.network.LocalNetworkRepositoryImpl
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.presentation.lobby.LobbyScreen
import com.brunogiovani.cachetaburaco.presentation.login.LoginScreen
import com.brunogiovani.cachetaburaco.presentation.main.MainMenuScreen
import com.brunogiovani.cachetaburaco.presentation.match.MatchScreen
import com.brunogiovani.cachetaburaco.presentation.match.MatchViewModel

enum class AppState { LOGIN, MAIN_MENU, LOBBY_HOST, LOBBY_CLIENT, MATCH }

class MainActivity : ComponentActivity() {

    private lateinit var networkRepository: LocalNetworkRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

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

                    when (currentScreen) {
                        AppState.LOGIN -> LoginScreen(
                            onLoginSuccess = { currentScreen = AppState.MAIN_MENU }
                        )

                        AppState.MAIN_MENU -> MainMenuScreen(
                            onLogout = { currentScreen = AppState.LOGIN },
                            onHostRoom = { currentScreen = AppState.LOBBY_HOST },
                            onJoinRoom = { currentScreen = AppState.LOBBY_CLIENT },
                            onResumeGame = {
                                val savedInfo = MatchViewModel.getSavedGameInfo(applicationContext)
                                if (savedInfo != null) {
                                    isHosting = savedInfo.first
                                    activeConfig = savedInfo.second
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
                                currentScreen = AppState.MATCH
                            }
                        )

                        AppState.MATCH -> MatchScreen(
                            networkRepository = networkRepository,
                            isHost = isHosting,
                            config = activeConfig,
                            onLeaveMatch = { currentScreen = AppState.MAIN_MENU }
                        )
                    }
                }
            }
        }
    }
}
