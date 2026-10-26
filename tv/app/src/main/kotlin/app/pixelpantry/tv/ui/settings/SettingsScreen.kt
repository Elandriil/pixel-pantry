package app.pixelpantry.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.pixelpantry.tv.R
import app.pixelpantry.tv.ui.components.FocusableButton
import app.pixelpantry.tv.ui.components.TvTextField

private val RowShape = RoundedCornerShape(12.dp)
private val RowMaxWidth = 960.dp

/**
 * Settings page content: one row per setting. Steppers change by [SettingsViewModel.STEP] seconds with
 * LEFT/RIGHT (UP/DOWN move between rows); OK on the autoplay row toggles it; the server row opens an inline
 * address field.
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val serverAddress by vm.serverAddress.collectAsStateWithLifecycle()
    val manualAddress by vm.manualAddress.collectAsStateWithLifecycle()
    val editing by vm.editingAddress.collectAsStateWithLifecycle()
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val firstRow = remember { FocusRequester() }
    val changeButton = remember { FocusRequester() }

    // BACK closes the address field before it leaves the page.
    BackHandler(enabled = editing) { vm.cancelEditingAddress() }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // Compose 1.7 can hand focus to the list itself (the shell's focus restorer); pass it on.
            .onFocusChanged { if (it.isFocused) runCatching { firstRow.requestFocus() } },
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        item {
            StepperRow(
                label = stringResource(R.string.settings_skip_back),
                value = stringResource(R.string.settings_seconds, settings.skipBackSeconds),
                atMin = settings.skipBackSeconds <= SettingsViewModel.MIN_SKIP,
                onStep = vm::adjustSkipBack,
                modifier = Modifier.focusRequester(firstRow),
            )
        }
        item {
            StepperRow(
                label = stringResource(R.string.settings_skip_forward),
                value = stringResource(R.string.settings_seconds, settings.skipForwardSeconds),
                atMin = settings.skipForwardSeconds <= SettingsViewModel.MIN_SKIP,
                onStep = vm::adjustSkipForward,
            )
        }
        item {
            SettingSurface(onClick = vm::toggleAutoplay) {
                RowLabel(stringResource(R.string.settings_autoplay))
                Text(
                    stringResource(if (settings.autoplayNext) R.string.settings_on else R.string.settings_off),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        item {
            ServerRow(
                address = serverAddress,
                manual = manualAddress != null,
                connecting = connecting,
                onChange = vm::startEditingAddress,
                onAutomatic = vm::useAutomaticDiscovery,
                changeFocus = changeButton,
            )
        }
        if (editing) {
            item {
                AddressEditor(
                    initial = manualAddress ?: serverAddress.removePrefix("http://"),
                    onSubmit = vm::setManualAddress,
                    onCancel = vm::cancelEditingAddress,
                )
            }
        }
        item {
            InfoRow(stringResource(R.string.settings_version), vm.version)
        }
    }

    LaunchedEffect(Unit) {
        // One frame first, so the list rows are composed and the requester is attached.
        withFrameNanos { }
        runCatching { firstRow.requestFocus() }
    }
    // The field (and the focus in it) goes away when editing ends; hand focus back to "Change…".
    var wasEditing by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        if (wasEditing && !editing) runCatching { changeButton.requestFocus() }
        wasEditing = editing
    }
}

/** A focusable settings row: label on the left, [content] after it. */
@Composable
private fun SettingSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.widthIn(max = RowMaxWidth).fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RowShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Row(Modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun RowScope.RowLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
}

/**
 * LEFT/RIGHT step the value; every other key (UP/DOWN, OK) behaves as usual. At the minimum ([atMin]) LEFT
 * is not consumed, so it reaches the sidebar.
 */
@Composable
private fun StepperRow(label: String, value: String, atMin: Boolean, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    SettingSurface(
        onClick = {},
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val delta = when (event.key) {
                    Key.DirectionLeft -> if (atMin) return@onPreviewKeyEvent false else -SettingsViewModel.STEP
                    Key.DirectionRight -> SettingsViewModel.STEP
                    else -> return@onPreviewKeyEvent false
                }
                // Each key-down (including repeats while held) is one step; key-ups are swallowed too.
                if (event.type == KeyEventType.KeyDown) onStep(delta)
                true
            },
    ) {
        RowLabel(label)
        val dim = LocalContentColor.current.copy(alpha = if (focused) 0.8f else 0f)
        Text("◀", style = MaterialTheme.typography.titleMedium, color = dim)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text("▶", style = MaterialTheme.typography.titleMedium, color = dim)
    }
}

/** Not focusable: the address with its mode, plus the buttons that change it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ServerRow(
    address: String,
    manual: Boolean,
    connecting: Boolean,
    onChange: () -> Unit,
    onAutomatic: () -> Unit,
    changeFocus: FocusRequester,
) {
    Column(
        Modifier
            .widthIn(max = RowMaxWidth)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RowShape)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_server),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(address, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    stringResource(
                        when {
                            connecting -> R.string.settings_connecting
                            manual -> R.string.settings_server_manual
                            else -> R.string.settings_server_auto
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Entering the buttons from above or below lands on "Change…", whichever button is nearer.
        Row(
            Modifier.focusProperties { enter = { changeFocus } }.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            FocusableButton(stringResource(R.string.settings_change), onChange, focusRequester = changeFocus)
            if (manual) {
                // The button goes away as soon as the manual address is cleared; move focus off it first.
                FocusableButton(stringResource(R.string.settings_use_discovery), {
                    runCatching { changeFocus.requestFocus() }
                    onAutomatic()
                })
            }
        }
    }
}

@Composable
private fun AddressEditor(initial: String, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    Row(
        Modifier.widthIn(max = RowMaxWidth).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvTextField(
            value = text,
            onValueChange = { text = it },
            onSubmit = { onSubmit(text) },
            label = stringResource(R.string.connect_address_label),
            placeholder = stringResource(R.string.connect_address_hint),
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            modifier = Modifier.focusRequester(fieldFocus),
        )
        FocusableButton(stringResource(R.string.connect_connect), { onSubmit(text) })
        FocusableButton(stringResource(R.string.dialog_cancel), onCancel)
        Spacer(Modifier.weight(1f))
    }
    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.widthIn(max = RowMaxWidth).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
