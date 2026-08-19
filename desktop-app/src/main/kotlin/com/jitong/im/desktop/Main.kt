package com.jitong.im.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.jitong.im.desktop.auth.AuthApiException
import com.jitong.im.desktop.auth.AuthClient
import com.jitong.im.desktop.auth.DesktopAuthStore
import com.jitong.im.desktop.auth.LoginOutcome
import com.jitong.im.desktop.local.LocalDatabaseManager
import com.jitong.im.desktop.local.MacOsKeychain
import java.nio.file.Path
import java.util.UUID

fun main() = application {
    val authStore = rememberDesktopAuthStore()
    Window(
        onCloseRequest = {
            authStore.close()
            exitApplication()
        },
        title = "Jitong") {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                DesktopApp(authStore)
            }
        }
    }
}

@Composable
private fun rememberDesktopAuthStore(): DesktopAuthStore {
    val store = remember {
        val accountDirectory = Path.of(
            System.getProperty("user.home"),
            "Library",
            "Application Support",
            "Jitong",
            "accounts")
        DesktopAuthStore(
            authClient = AuthClient(System.getenv("JITONG_SERVER_URL") ?: "http://127.0.0.1:8080"),
            databaseManager = LocalDatabaseManager(accountDirectory, MacOsKeychain()),
            installationId = installationId(accountDirectory))
    }
    return store
}

@Composable
private fun DesktopApp(authStore: DesktopAuthStore) {
    var accountNo by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var session by remember { mutableStateOf(authStore.session) }

    if (session == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(56.dp),
            verticalArrangement = Arrangement.Center) {
            Text("Jitong", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("Sign in to your PC device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(28.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = accountNo,
                onValueChange = { accountNo = it.filter(Char::isDigit).take(11) },
                label = { Text("Account number") },
                singleLine = true)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true)
            Spacer(Modifier.height(20.dp))
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = accountNo.length == 11 && password.isNotBlank(),
                onClick = {
                    error = null
                    runCatching { authStore.login(accountNo, password) }
                        .onSuccess { result ->
                            when (result) {
                                is LoginOutcome.Authenticated -> session = result.session
                                is LoginOutcome.ReplacementRequired -> challenge = result.challenge
                            }
                        }
                        .onFailure { throwable -> error = messageFor(throwable) }
                }) {
                Text("Sign in")
            }
            challenge?.let { replacementChallenge ->
                Spacer(Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("A PC is already active", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Confirm replacement to sign in here. Your active MOBILE device is not affected.")
                        Spacer(Modifier.height(16.dp))
                        Row {
                            Button(onClick = {
                                runCatching { authStore.confirmReplacement(replacementChallenge) }
                                    .onSuccess {
                                        session = it
                                        challenge = null
                                    }
                                    .onFailure { error = messageFor(it) }
                            }) { Text("Replace PC") }
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { challenge = null }) { Text("Cancel") }
                        }
                    }
                }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(56.dp)) {
            Text("Welcome back", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("PC device connected", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            Text("Account ${session!!.accountNo}")
            Text("Local history is protected by H2 AES.")
            Spacer(Modifier.height(28.dp))
            Row {
                Button(onClick = {
                    authStore.logout()
                    session = null
                }) { Text("Log out") }
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = {
                    authStore.clearUntrustedLocalData()
                    session = null
                }) { Text("Clear local data") }
            }
            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun installationId(directory: Path): String {
    val file = directory.resolve("installation-id")
    return if (file.toFile().exists()) {
        file.toFile().readText().trim()
    } else {
        directory.toFile().mkdirs()
        UUID.randomUUID().toString().also { file.toFile().writeText(it) }
    }
}

private fun messageFor(throwable: Throwable): String = when (throwable) {
    is AuthApiException -> throwable.error.message
    else -> throwable.message ?: "The request could not be completed"
}
