package com.brunogiovani.cachetaburaco

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.brunogiovani.cachetaburaco.data.network.LocalNetworkRepositoryImpl
import com.brunogiovani.cachetaburaco.data.network.OnlineNetworkRepository
import com.brunogiovani.cachetaburaco.data.network.SoloBotNetworkRepository
import com.brunogiovani.cachetaburaco.data.online.SupabaseOnlineProfileRepository
import com.brunogiovani.cachetaburaco.data.online.SupabaseOnlineRankingRepository
import com.brunogiovani.cachetaburaco.data.repositories.FakeAuthRepository
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.presentation.lobby.LobbyScreen
import com.brunogiovani.cachetaburaco.presentation.login.LoginScreen
import com.brunogiovani.cachetaburaco.presentation.main.MainMenuScreen
import com.brunogiovani.cachetaburaco.presentation.match.MatchScreen
import com.brunogiovani.cachetaburaco.presentation.match.MatchViewModel
import com.brunogiovani.cachetaburaco.presentation.profile.OnlineProfileScreen
import com.brunogiovani.cachetaburaco.presentation.ranking.OnlineRankingScreen
import com.google.android.gms.ads.MobileAds

enum class AppState {
    LOGIN,
    MAIN_MENU,
    LOBBY_HOST,
    LOBBY_CLIENT,
    LOBBY_CLIENT_RESUME,
    LOBBY_ONLINE_HOST,
    LOBBY_ONLINE_CLIENT,
    LOBBY_BOT,
    ONLINE_PROFILE,
    ONLINE_RANKING,
    MATCH
}

/**
 * Entrada principal e roteador simples de telas.
 *
 * A Activity so escolhe qual transporte a partida vai usar:
 * - LocalNetworkRepositoryImpl para Wi-Fi/local;
 * - SoloBotNetworkRepository para jogar contra a maquina;
 * - OnlineNetworkRepository para salas persistidas no Supabase.
 *
 * Regra de jogo nao entra aqui. A Activity escolhe fluxo, configuracao ativa
 * e transporte; quem manda na partida continua sendo o MatchViewModel.
 */
class MainActivity : ComponentActivity() {

    private lateinit var networkRepository: LocalNetworkRepositoryImpl

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        hideSystemBars()

        // Carrega o perfil salvo antes de decidir qual tela abrir.
        FakeAuthRepository.init(applicationContext)
        MobileAds.initialize(applicationContext) {}

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
                    val onlineRepository = remember {
                        OnlineNetworkRepository(
                            playerNameProvider = {
                                FakeAuthRepository.getCurrentPlayer()?.name ?: "Jogador"
                            }
                        )
                    }
                    val onlineProfileRepository = remember { SupabaseOnlineProfileRepository() }
                    val onlineRankingRepository = remember { SupabaseOnlineRankingRepository() }
                    // Este repositorio ativo e a tomada da mesa: local, maquina
                    // e online podem trocar por aqui sem duplicar tela ou regra.
                    var activeRepository by remember { mutableStateOf<LocalNetworkRepository>(networkRepository) }

                    when (currentScreen) {
                        AppState.LOGIN -> LoginScreen(
                            onLoginSuccess = { currentScreen = AppState.MAIN_MENU }
                        )

                        AppState.MAIN_MENU -> MainMenuScreen(
                            onLogout = {
                                onlineRepository.clearAuthenticatedSession()
                                currentScreen = AppState.LOGIN
                            },
                            onHostRoom = { currentScreen = AppState.LOBBY_HOST },
                            onJoinRoom = { currentScreen = AppState.LOBBY_CLIENT },
                            onHostOnlineRoom = { currentScreen = AppState.LOBBY_ONLINE_HOST },
                            onJoinOnlineRoom = { currentScreen = AppState.LOBBY_ONLINE_CLIENT },
                            onOpenOnlineProfile = { currentScreen = AppState.ONLINE_PROFILE },
                            onOpenOnlineRanking = { currentScreen = AppState.ONLINE_RANKING },
                            onPlayBot = { currentScreen = AppState.LOBBY_BOT },
                            onResumeGame = {
                                val savedInfo = MatchViewModel.getSavedGameInfo(applicationContext)
                                if (savedInfo != null) {
                                    val (savedIsHost, savedConfig) = savedInfo
                                    activeConfig = savedConfig
                                    activeRepository = networkRepository
                                    if (savedIsHost) {
                                        // O host e quem tem estado de verdade pra retomar sozinho:
                                        // reabre a sala com a mesma config e ja segue pra mesa. O
                                        // parceiro/cliente reconecta puxando o REQ_RECONNECT (ver
                                        // MatchScreen) assim que reencontrar a sala pelo Wi-Fi.
                                        isHosting = true
                                        networkRepository.startHosting(
                                            FakeAuthRepository.getCurrentPlayer()?.name ?: "Jogador",
                                            config = savedConfig
                                        )
                                        networkRepository.presetRememberedSeats(
                                            MatchViewModel.getSavedPlayerSeats(applicationContext)
                                        )
                                        currentScreen = AppState.MATCH
                                    } else {
                                        // Cliente nao tem o endereco do host guardado -- sem isso
                                        // nao da pra reconectar sozinho, entao ele precisa procurar
                                        // a sala de novo. A diferenca pro "Procurar sala" comum e que
                                        // aqui o snapshot local NAO e apagado, entao a mesa restaurada
                                        // (mao, jogos, placar) continua valendo ao entrar.
                                        isHosting = false
                                        currentScreen = AppState.LOBBY_CLIENT_RESUME
                                    }
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

                        AppState.LOBBY_CLIENT_RESUME -> LobbyScreen(
                            isHosting = false,
                            networkRepository = networkRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU },
                            onGameStarted = {
                                // Ao contrario do join normal, aqui NAO chamo
                                // MatchViewModel.clearSavedGame: a mesa que essa tela
                                // reconecta e a mesma do snapshot local, entao o proprio
                                // MatchViewModel restaura mao/jogos/placar sozinho ao abrir.
                                isHosting = false
                                activeRepository = networkRepository
                                currentScreen = AppState.MATCH
                            }
                        )

                        AppState.LOBBY_ONLINE_HOST -> LobbyScreen(
                            isHosting = true,
                            networkRepository = onlineRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU },
                            onGameStarted = { config ->
                                MatchViewModel.clearSavedGame(applicationContext)
                                activeConfig = config
                                isHosting = true
                                activeRepository = onlineRepository
                                currentScreen = AppState.MATCH
                            }
                        )

                        AppState.LOBBY_ONLINE_CLIENT -> LobbyScreen(
                            isHosting = false,
                            networkRepository = onlineRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU },
                            onGameStarted = { config ->
                                MatchViewModel.clearSavedGame(applicationContext)
                                activeConfig = config
                                isHosting = false
                                activeRepository = onlineRepository
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

                        AppState.ONLINE_PROFILE -> OnlineProfileScreen(
                            playerName = FakeAuthRepository.getCurrentPlayer()?.name ?: "Jogador",
                            repository = onlineProfileRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU }
                        )

                        AppState.ONLINE_RANKING -> OnlineRankingScreen(
                            playerName = FakeAuthRepository.getCurrentPlayer()?.name ?: "Jogador",
                            repository = onlineRankingRepository,
                            onBack = { currentScreen = AppState.MAIN_MENU }
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
