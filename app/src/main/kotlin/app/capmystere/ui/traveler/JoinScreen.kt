package app.capmystere.ui.traveler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.capmystere.R
import app.capmystere.core.model.TripCode
import app.capmystere.data.backend.BackendRejected
import app.capmystere.data.backend.TripBackend
import app.capmystere.theme.Cap
import app.capmystere.travel.SyncEngine
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CameraGate
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.QrScanner
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.buttonShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class JoinError { UNKNOWN_CODE, OFFLINE }

data class JoinState(val loading: Boolean = false, val error: JoinError? = null, val message: String? = null)

@HiltViewModel
class JoinViewModel @Inject constructor(private val backend: TripBackend, private val sync: SyncEngine) : ViewModel() {
    private val _state = MutableStateFlow(JoinState())
    val state = _state.asStateFlow()

    fun join(code: String, onJoined: (String) -> Unit) {
        if (_state.value.loading) return
        _state.value = JoinState(loading = true)
        viewModelScope.launch {
            try {
                val snapshot = backend.join(code)
                sync.cacheSnapshot(snapshot)
                _state.value = JoinState()
                onJoined(snapshot.sessionId)
            } catch (e: BackendRejected) {
                _state.value = JoinState(error = JoinError.UNKNOWN_CODE, message = e.message)
            } catch (e: Exception) {
                _state.value = JoinState(error = JoinError.OFFLINE)
            }
        }
    }

    fun clearError() { _state.value = _state.value.copy(error = null) }
}

@Composable
fun JoinScreen(initialCode: String, onBack: () -> Unit, onJoined: (String) -> Unit, vm: JoinViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf(TripCode.normalize(initialCode).take(TripCode.LENGTH)) }
    var scanning by remember { mutableStateOf(false) }

    LaunchedEffect(initialCode) { if (TripCode.isValid(code)) vm.join(code, onJoined) }

    ThemedBackground(Modifier.fillMaxSize()) {
        if (scanning) {
            Column(Modifier.fillMaxSize()) {
                CapTopBar(stringResource(R.string.join_scan), onBack = { scanning = false })
                CameraGate(stringResource(R.string.perm_camera_qr)) {
                    QrScanner(
                        onResult = { text ->
                            scanning = false
                            TripCode.fromLinkOrCode(text)?.let { code = it; vm.join(it, onJoined) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                CapTopBar(stringResource(R.string.join_title), onBack = onBack)
                JoinContent(
                    code = code, state = state,
                    onCode = { code = TripCode.normalize(it).take(TripCode.LENGTH); vm.clearError() },
                    onSubmit = { vm.join(code, onJoined) },
                    onScan = { scanning = true },
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
fun JoinContent(code: String, state: JoinState, onCode: (String) -> Unit, onSubmit: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(modifier) {
        Spacer(Modifier.height(12.dp))
        DisplayText(stringResource(R.string.join_headline))
        Spacer(Modifier.height(10.dp))
        Text(Cap.tone.narration("welcome", stringResource(R.string.join_sub)), style = Cap.type.quote, color = Cap.colors.textMuted)
        Spacer(Modifier.height(30.dp))
        BasicTextField(
            value = code,
            onValueChange = onCode,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            singleLine = true,
            cursorBrush = SolidColor(androidx.compose.ui.graphics.Color.Transparent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { if (TripCode.isValid(code)) onSubmit() }),
            decorationBox = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(TripCode.LENGTH) { i ->
                        val active = i == code.length
                        Box(
                            Modifier.weight(1f).aspectRatio(0.78f).clip(buttonShape())
                                .background(if (i < code.length) Cap.colors.surfaceHigh else Cap.colors.surface)
                                .border(if (active) 2.dp else 1.dp, if (active) Cap.colors.primary else Cap.colors.outline, buttonShape()),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(i)?.toString().orEmpty(), style = Cap.type.display, color = Cap.colors.text)
                        }
                    }
                }
            },
        )
        Spacer(Modifier.height(14.dp))
        when (state.error) {
            JoinError.UNKNOWN_CODE -> BodyText(state.message ?: stringResource(R.string.join_error_unknown), color = Cap.colors.danger)
            JoinError.OFFLINE -> BodyText(stringResource(R.string.join_error_offline), color = Cap.colors.danger)
            null -> BodyText(stringResource(R.string.join_hint), color = Cap.colors.textMuted)
        }
        Spacer(Modifier.height(26.dp))
        CapButton(
            stringResource(if (state.loading) R.string.join_loading else R.string.join_go), onSubmit, Modifier.fillMaxWidth(),
            enabled = TripCode.isValid(code) && !state.loading,
        )
        Spacer(Modifier.height(12.dp))
        CapButton(stringResource(R.string.join_scan), onScan, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST, leading = { GlyphIcon(Glyph.QR, size = 22.dp) })
        Spacer(Modifier.height(24.dp))
    }
}

