package com.ayesha.player.ui.vault

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.ayesha.player.core.theme.AppTheme
import com.ayesha.player.data.vault.VaultSecurity
import com.ayesha.player.ui.navigation.Route
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val PIN_LENGTH = 6

@HiltViewModel
class VaultPinViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val security: VaultSecurity,
) : ViewModel() {
    val args = savedState.toRoute<Route.VaultPin>()
    val creating = args.mode == "create"

    var entered by mutableStateOf("")
        private set
    var firstEntry by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set

    private val _success = Channel<Unit>(Channel.CONFLATED)
    val success = _success.receiveAsFlow()

    val confirming: Boolean get() = creating && firstEntry != null

    fun digit(d: Char) {
        if (busy || entered.length >= PIN_LENGTH) return
        error = null
        entered += d
        if (entered.length == PIN_LENGTH) complete()
    }

    fun backspace() {
        if (!busy && entered.isNotEmpty()) entered = entered.dropLast(1)
    }

    private fun complete() {
        if (creating) {
            if (firstEntry == null) {
                firstEntry = entered
                entered = ""
                return
            }
            if (entered != firstEntry) {
                error = "PINs didn't match — try again"
                firstEntry = null
                entered = ""
                return
            }
            busy = true
            viewModelScope.launch {
                security.setPin(entered)
                _success.send(Unit)
            }
            return
        }
        busy = true
        viewModelScope.launch {
            if (security.verifyPin(entered)) {
                _success.send(Unit)
            } else {
                busy = false
                error = "Incorrect PIN"
                entered = ""
            }
        }
    }

    fun biometricSucceeded() {
        viewModelScope.launch { _success.send(Unit) }
    }

    fun biometricFailed(message: String) {
        error = message
    }
}

/**
 * vault_pin_screen.dart. A 6-digit PIN just for the vault, never the device's own unlock.
 * Create = enter twice. Unlock may use the fingerprint/face instead, but only when a real
 * biometric is enrolled. Unlike the Flutter build, a phone without one never bypasses the PIN,
 * and the biometric prompt can't fall back to the device PIN.
 */
@Composable
fun VaultPinScreen(onSuccess: (Route.VaultPin) -> Unit, onCancel: () -> Unit, vm: VaultPinViewModel = hiltViewModel()) {
    SecureScreen()
    val c = AppTheme.colors
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.success.collect { onSuccess(vm.args) } }

    val biometricsAvailable = remember {
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    fun promptBiometric() {
        val activity = context as? FragmentActivity ?: return
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(context),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = vm.biometricSucceeded()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        vm.biometricFailed(errString.toString())
                    }
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Secure Vault")
                .setSubtitle("Please authenticate to access the Secure Vault")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or BIOMETRIC_WEAK)
                .setNegativeButtonText("Use PIN")
                .build(),
        )
    }

    val title = when {
        !vm.creating -> "Enter Vault PIN"
        vm.confirming -> "Confirm PIN"
        else -> "Create Vault PIN"
    }
    val subtitle = when {
        !vm.creating -> "This PIN is separate from your device unlock"
        vm.confirming -> "Enter the same PIN again"
        else -> "Choose a PIN just for the vault — different from your device unlock"
    }

    Column(Modifier.fillMaxSize().background(c.bg).safeDrawingPadding().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth()) {
            IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel", tint = c.textPrimary) }
        }
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.Lock, null, Modifier.size(40.dp), tint = c.accent)
        Spacer(Modifier.height(20.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, textAlign = TextAlign.Center, fontSize = 13.sp, color = c.textMuted)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            repeat(PIN_LENGTH) { i ->
                val filled = i < vm.entered.length
                Box(
                    Modifier
                        .padding(horizontal = 6.dp)
                        .size(14.dp)
                        .background(if (filled) c.accent else Color.Transparent, CircleShape)
                        .border(1.5.dp, if (filled) c.accent else c.border, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.height(20.dp)) {
            vm.error?.let { Text(it, color = c.errorRed, fontSize = 12.sp) }
        }
        Spacer(Modifier.weight(1f))
        Keypad(enabled = !vm.busy, onDigit = vm::digit, onBackspace = vm::backspace)
        if (!vm.creating && biometricsAvailable) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = ::promptBiometric, enabled = !vm.busy) {
                Icon(Icons.Rounded.Fingerprint, null, tint = c.accent)
                Spacer(Modifier.width(8.dp))
                Text("Use biometrics instead", color = c.accent)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** vault_keypad.dart. */
@Composable
private fun Keypad(enabled: Boolean, onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val c = AppTheme.colors
    @Composable
    fun RowScope.Key(content: @Composable () -> Unit, onClick: (() -> Unit)?) {
        Box(Modifier.weight(1f).aspectRatio(1.4f).padding(6.dp), contentAlignment = Alignment.Center) {
            if (onClick != null) {
                Box(
                    Modifier.fillMaxHeight().aspectRatio(1f).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) { content() }
            }
        }
    }
    Column {
        listOf("123", "456", "789").forEach { row ->
            Row { row.forEach { d -> Key({ Text("$d", fontSize = 22.sp, fontWeight = FontWeight.W500, color = c.textPrimary) }) { onDigit(d) } } }
        }
        Row {
            Key({}, null)
            Key({ Text("0", fontSize = 22.sp, fontWeight = FontWeight.W500, color = c.textPrimary) }) { onDigit('0') }
            Key({ Icon(Icons.AutoMirrored.Outlined.Backspace, "Delete", Modifier.size(20.dp), tint = c.textSecondary) }, onBackspace)
        }
    }
}
