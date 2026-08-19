package com.jitong.im.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jitong.im.android.auth.SessionState

@Composable
internal fun JitongApp(viewModel: AuthViewModel) {
    val state by viewModel.sessionState.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val current = state) {
            SessionState.SignedOut -> LoginScreen(viewModel)
            SessionState.Restoring -> RestoringScreen()
            is SessionState.ReplacementRequired -> ReplacementScreen(current, viewModel)
            is SessionState.SignedIn -> HomeScreen(current, viewModel)
            is SessionState.Error -> LoginScreen(viewModel, current.message)
        }
    }
}

@Composable
private fun LoginScreen(viewModel: AuthViewModel, error: String? = null) {
    var accountNo by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("即通", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("登录到独立加密账号空间", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(28.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = accountNo,
                    onValueChange = { accountNo = it.filter(Char::isDigit).take(11) },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { viewModel.login(accountNo, password) },
                    enabled = accountNo.length == 11 && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("登录") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "正常退出只删除令牌并保留本地加密数据；设备失信时会清除密钥、数据库和媒体缓存。",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ReplacementScreen(
    state: SessionState.ReplacementRequired,
    viewModel: AuthViewModel,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("需要替换旧 MOBILE", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("该账号已有一台受信任的 MOBILE。确认后旧设备会在下次联机时失信并清除本地数据。")
        Spacer(Modifier.height(18.dp))
        Card(Modifier.fillMaxWidth()) {
            Text(
                "一次性确认挑战已生成，仅在本次登录流程中使用。",
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { viewModel.confirmReplacement(state.challenge) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("确认替换") }
        TextButton(
            onClick = { viewModel.logout() },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text("取消")
        }
    }
}

@Composable
private fun RestoringScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(14.dp))
        Text("正在恢复登录会话")
    }
}

@Composable
private fun HomeScreen(state: SessionState.SignedIn, viewModel: AuthViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("已登录", style = MaterialTheme.typography.headlineMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("账号 ${state.session.accountNo}", style = MaterialTheme.typography.titleMedium)
                Text("设备类型 ${state.session.deviceClass}")
                Text("本地空间：独立 SQLCipher 数据库")
                Text("退出后仍保留加密历史，受保护页面仅在有效会话下可访问。")
            }
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { viewModel.logout() }, modifier = Modifier.weight(1f)) {
                Text("正常退出")
            }
            Button(onClick = { viewModel.clearData() }, modifier = Modifier.weight(1f)) {
                Text("清除本机数据")
            }
        }
    }
}
