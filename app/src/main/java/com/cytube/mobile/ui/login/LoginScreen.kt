package com.cytube.mobile.ui.login

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cytube.mobile.data.AuthRepository
import com.cytube.mobile.data.SettingsStore
import com.cytube.mobile.di.Graph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val auth = remember { Graph.auth(context) }
    val settingsStore = remember { SettingsStore(context) }
    val scope = rememberCoroutineScope()

    var session by remember { mutableStateOf(auth.savedSession()) }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val current = session
            if (current != null) {
                Text("Signed in as ${current.name}", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your session is stored as an encrypted cookie. Your password is not kept on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    // Clear the local session immediately so the UI feels
                    // instant; the (best-effort) server-side revoke happens
                    // in the background and never blocks this.
                    onClick = {
                        session = null
                        scope.launch { auth.logout() }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Log out") }
            } else {
                Text("Log in to CyTube", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            when (val r = auth.login(username.trim(), password, remember_)) {
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

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("Guest name", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Shown in chat when you're not signed in. Leave blank for a new " +
                        "random name every time you join a channel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = guestName,
                    onValueChange = { guestName = it.take(20) },
                    singleLine = true,
                    placeholder = { Text(guestPlaceholder) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
