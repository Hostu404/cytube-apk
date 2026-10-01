package com.cytube.mobile.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cytube.mobile.data.AuthRepository
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import com.cytube.mobile.ui.theme.CyTubePageTheme
import com.cytube.mobile.ui.theme.Ma
import com.cytube.mobile.ui.theme.MaTextField
import com.cytube.mobile.ui.theme.MaTopBar
import com.cytube.mobile.ui.theme.maFaint
import com.cytube.mobile.ui.theme.maPageBackground
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    // The first Graph.auth() call opens the encrypted store (a Keystore
    // round trip, slow on low-end TV sticks) and savedSession() decrypts
    // it, so both run off the main thread; the screen shows a spinner
    // until they're done rather than a login form that may not apply.
    var auth by remember { mutableStateOf<AuthRepository?>(null) }
    var session by remember { mutableStateOf<AuthRepository.Session?>(null) }
    LaunchedEffect(Unit) {
        val (repo, saved) = withContext(Dispatchers.IO) {
            val repo = Graph.auth(context)
            repo to repo.savedSession()
        }
        session = saved
        auth = repo
    }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember_ by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var guestName by remember { mutableStateOf("") }
    var guestNameLoaded by remember { mutableStateOf(false) }
    val guestPlaceholder = remember { "Guest" + (1000..9999).random() }
    LaunchedEffect(Unit) {
        guestName = settingsStore.settings.first().guestName
        guestNameLoaded = true
    }
    // Debounced save: write after typing settles rather than on every
    // keystroke, but skip the write that fires from the initial load above.
    LaunchedEffect(guestName, guestNameLoaded) {
        if (!guestNameLoaded) return@LaunchedEffect
        delay(500)
        settingsStore.setGuestName(guestName)
    }
    // Leaving within the debounce cancels the save above; finish it on the
    // way out instead, on a scope that outlives this screen.
    val latestGuestName by rememberUpdatedState(guestName)
    val latestGuestNameLoaded by rememberUpdatedState(guestNameLoaded)
    DisposableEffect(Unit) {
        onDispose {
            if (latestGuestNameLoaded) {
                val name = latestGuestName
                Graph.appScope.launch { settingsStore.setGuestName(name) }
            }
        }
    }

    // The same quiet page as home (see Ma.kt): paper and ink on light, with
    // its grain; pure black on dark.
    CyTubePageTheme {
    Scaffold(
        modifier = Modifier.fillMaxSize().then(maPageBackground()),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = { MaTopBar("Account", onBack) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(start = Ma.MarginStart, end = Ma.MarginEnd, top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val repo = auth
            val current = session
            if (repo == null) {
                Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = maFaint(), strokeWidth = 1.5.dp)
                }
            } else if (current != null) {
                Text("Signed in as ${current.name}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your password is not kept on this device. Only the session cookie CyTube " +
                        "gave the app is kept, encrypted if you chose to stay signed in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    // logout() forgets the session at once and never blocks.
                    onClick = {
                        session = null
                        repo.logout()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Log out") }
            } else {
                Text("Log in to CyTube", style = MaterialTheme.typography.titleLarge)

                MaTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Username",
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                MaTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                // The checkbox's touch area leaves 14dp around its box: pull
                // it left so the box sits on the page margin.
                Row(Modifier.offset(x = (-14).dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember_, onCheckedChange = { remember_ = it }, enabled = !busy)
                    Text("Stay signed in")
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                Button(
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            when (val r = repo.login(username.trim(), password, remember_)) {
                                is AuthRepository.LoginOutcome.Success -> {
                                    session = r.session
                                    password = ""
                                }
                                is AuthRepository.LoginOutcome.Failure -> error = r.message
                                is AuthRepository.LoginOutcome.WebFlowUnavailable ->
                                    error = "${r.reason}. You can still browse and chat as a guest."
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("Log in")
                }

                Text(
                    "Without an account you join channels as a guest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Space, not a divider line, before the guest section.
                Spacer(Modifier.height(32.dp))
                MaTextField(
                    value = guestName,
                    onValueChange = { guestName = it.take(20) },
                    label = "Guest name",
                    placeholder = guestPlaceholder,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Shown in chat when you're not signed in. Leave blank for a new " +
                        "random name every time you join a channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    }
}
