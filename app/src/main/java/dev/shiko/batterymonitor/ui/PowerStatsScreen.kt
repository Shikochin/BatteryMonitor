package dev.shiko.batterymonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.DaemonState
import dev.shiko.batterymonitor.model.PowerUiState
import dev.shiko.batterymonitor.ui.component.SessionHistoryDialog
import dev.shiko.batterymonitor.ui.component.StatSummaryCard
import dev.shiko.batterymonitor.ui.component.UsageProcessCard
import dev.shiko.batterymonitor.ui.component.UsageScenarioCard
import androidx.compose.ui.res.stringResource
import dev.shiko.batterymonitor.R
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Root screen of the app: the stringResource(R.string.screen_title) (power consumption statistics) page.
 *
 * Structure mirrors the Scene reference:
 *  1. [UsageProcessCard] — curve + telemetry
 *  2. [StatSummaryCard] — average power / used / estimated runtime
 *  3. [UsageScenarioCard] — per-app breakdown
 *  …plus a single-line data-source footer ("数据来源：Shizuku (root)") and nothing else.
 * While recording is paused a [RecordingPausedCard] banner is prepended above all of them, so the
 * paused state is visible even when the curve is scrolled out of view.
 *
 * The top bar collapses from the large title into the small centred title while the content
 * scrolls ([MiuixScrollBehavior]); there is deliberately no back affordance. The bar itself is a
 * frosted-glass surface: the scrolling content is recorded into a [rememberLayerBackdrop] layer and
 * blurred behind the bar. `miuix-blur` needs `RuntimeShader` (Android 13+), so on older systems the
 * bar falls back to the plain opaque surface colour and the backdrop layer is never recorded.
 *
 * The content deliberately scrolls **under** the bar (the Scaffold's top inset is applied inside the
 * scroll container, not around it) — otherwise there would be nothing behind the glass to blur.
 *
 * @param onBack kept for call-site compatibility; the top bar no longer renders a back button.
 * @param onToggleRecordingPause pauses a running recording or resumes a paused one; both the
 *   top-bar button and the banner's button go through it.
 */
@Composable
fun PowerStatsScreen(
    state: PowerUiState,
    onBack: () -> Unit,
    onClearData: () -> Unit,
    onRefreshScenarios: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRestartDaemon: () -> Unit,
    onToggleRecordingPause: () -> Unit,
    onDeleteHistory: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showHistory by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val scrollState = rememberScrollState()

    val surfaceColor = MiuixTheme.colorScheme.surface
    // Backdrop blur is built on RuntimeShader, which only exists on Android 13+; below that the
    // top bar keeps the plain surface colour and no backdrop layer is recorded at all.
    val blurSupported = remember { isRuntimeShaderSupported() }
    // `layerBackdrop` records only what is drawn inside this composable, so the surface colour is
    // painted into the layer first — otherwise the blur would sample transparent pixels and smear.
    val backdrop = if (blurSupported) {
        rememberLayerBackdrop {
            drawRect(surfaceColor)
            drawContent()
        }
    } else {
        null
    }

    // The pull-to-refresh indicator stays up until the repository has re-read the stats.
    LaunchedEffect(state.loadingScenarios) {
        if (!state.loadingScenarios) refreshing = false
    }

    Scaffold(
        modifier = modifier,
        containerColor = surfaceColor,
        topBar = {
            TopAppBar(
                title = stringResource(R.string.screen_title),
                largeTitle = stringResource(R.string.screen_title),
                scrollBehavior = scrollBehavior,
                color = if (backdrop != null) Color.Transparent else surfaceColor,
                modifier = if (backdrop != null) {
                    Modifier.textureBlur(
                        backdrop = backdrop,
                        shape = RectangleShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = surfaceColor.copy(alpha = 0.8f)),
                            ),
                        ),
                    )
                } else {
                    Modifier
                },
                actions = {
                    // First action: pausing is the most frequently used control here. It stays
                    // disabled while a pause/resume round trip is in flight — each one execs over
                    // Shizuku, and a double tap would race two daemon commands against each other.
                    // Miuix's IconButton only drops the click when disabled, so the icon is dimmed
                    // explicitly: a stop + probe can take a few seconds and the button must look
                    // as busy as the banner's TextButton does.
                    IconButton(
                        onClick = onToggleRecordingPause,
                        enabled = !state.recordingPauseBusy,
                    ) {
                        Icon(
                            imageVector = if (state.recordingPaused) {
                                MiuixIcons.Light.Play
                            } else {
                                MiuixIcons.Light.Pause
                            },
                            contentDescription = stringResource(
                                if (state.recordingPaused) {
                                    R.string.action_resume_recording
                                } else {
                                    R.string.action_pause_recording
                                },
                            ),
                            modifier = Modifier
                                .size(22.dp)
                                .alpha(if (state.recordingPauseBusy) 0.4f else 1f),
                        )
                    }
                    IconButton(onClick = { showHistory = true }) {
                        Icon(
                            imageVector = MiuixIcons.Light.Recent,
                            contentDescription = stringResource(R.string.action_history),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = MiuixIcons.Light.Delete,
                            contentDescription = stringResource(R.string.action_clear_data),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    onRefreshScenarios()
                },
                modifier = Modifier.fillMaxSize(),
                // Only the refresh indicator needs the bar height; the content gets it as scroll
                // padding below so that cards can slide under the frosted bar.
                contentPadding = contentPadding,
                topAppBarScrollBehavior = scrollBehavior,
                refreshTexts = listOf(
                        stringResource(R.string.pull_to_refresh),
                        stringResource(R.string.release_to_refresh),
                        stringResource(R.string.refreshing),
                        stringResource(R.string.refresh_done),
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        // Applied *inside* the scroll container: the padding scrolls away with the
                        // content, which is what lets the cards pass behind the top bar.
                        .padding(contentPadding)
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // The banner is the first child because a paused stretch is drawn exactly like a
                    // missed record: without it the dashed line would read as a background-process bug.
                    if (state.recordingPaused) {
                        RecordingPausedCard(
                            busy = state.recordingPauseBusy,
                            onResume = onToggleRecordingPause,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    UsageProcessCard(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatSummaryCard(
                        summary = state.summary,
                        screenOn = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatSummaryCard(
                        summary = state.screenOffSummary,
                        screenOn = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    UsageScenarioCard(
                        scenarios = state.scenarios,
                        // A Shizuku or direct-root grant is required: without one the card asks for access.
                        needsShizuku = !state.shizukuGranted,
                        loading = state.loadingScenarios,
                        onRequestShizuku = onRequestShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DataSourceRow(
                        shizukuGranted = state.shizukuGranted,
                        rootAvailable = state.rootAvailable,
                        rootProviderLabel = state.rootProviderLabel,
                        shizukuAvailable = state.shizukuAvailable,
                        daemonState = state.daemonState,
                        daemonRestarting = state.daemonRestarting,
                        recordingPaused = state.recordingPaused,
                        onRestartDaemon = onRestartDaemon,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }
        }

        // Dialogs live inside the Scaffold content so they attach to the Scaffold's popup host.
        SessionHistoryDialog(
            show = showHistory,
            records = state.sessionHistory,
            onDismiss = { showHistory = false },
            onDeleteHistory = onDeleteHistory,
            onClearHistory = onClearHistory,
        )

        OverlayDialog(
            show = showClearConfirm,
            title = stringResource(R.string.clear_confirm_title),
            onDismissRequest = { showClearConfirm = false },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        showClearConfirm = false
                        onClearData()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/**
 * Banner shown above the cards while recording is paused.
 *
 * It carries the resume action as well as the explanation, because pausing is a state the user has
 * to be able to leave without hunting for the top-bar button again.
 */
@Composable
private fun RecordingPausedCard(
    busy: Boolean,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.recording_paused_title),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.recording_paused_hint),
                    fontSize = 12.sp,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.width(12.dp))
            TextButton(
                text = stringResource(R.string.action_resume_recording),
                onClick = onResume,
                enabled = !busy,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * The data-source footer: exactly one grey line, `数据来源：Shizuku (root|shell)`.
 *
 * Nothing else is shown here — the grant affordance lives in [UsageScenarioCard], so this line is
 * pure attribution and only names the mode the Shizuku shell is running in.
 */
@Composable
private fun DataSourceRow(
    shizukuGranted: Boolean,
    rootAvailable: Boolean,
    rootProviderLabel: String?,
    shizukuAvailable: Boolean,
    daemonState: DaemonState,
    daemonRestarting: Boolean,
    recordingPaused: Boolean,
    onRestartDaemon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = rootProviderLabel.takeIf { shizukuGranted && rootAvailable } ?: stringResource(
        when {
            // root reads continuous sysfs current; plain shell can only see `dumpsys battery`.
            shizukuGranted && rootAvailable -> R.string.data_source_root
            shizukuGranted -> R.string.data_source_shell
            shizukuAvailable -> R.string.data_source_unauthorized
            else -> R.string.data_source_disconnected
        },
    )
    // The daemon is what keeps collecting while Android freezes this process in the background.
    // Only a verified probe may say "running"; a failed probe is "unknown", never a stale guess.
    // A paused sampler is stopped on purpose rather than broken, so it gets its own label: reporting
    // it as "stopped" would read as a fault the user has to fix.
    val daemonStateText = if (recordingPaused) {
        stringResource(R.string.daemon_paused)
    } else {
        stringResource(
            when (daemonState) {
                DaemonState.RUNNING -> R.string.daemon_running
                DaemonState.STOPPED -> R.string.daemon_stopped
                DaemonState.UNKNOWN -> R.string.daemon_unknown
            },
        )
    }
    val text = stringResource(R.string.data_source_line, source, daemonStateText)
    val restartLabel = stringResource(
        if (daemonRestarting) R.string.action_restarting else R.string.action_restart_daemon,
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
            )
            // Without root there is no sampler to restart, so the affordance is not shown at all.
            // While paused there is deliberately no sampler running either: resuming is the only
            // meaningful action, and it lives in the banner and the top bar.
            if (shizukuGranted && rootAvailable && !recordingPaused) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = restartLabel,
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable(enabled = !daemonRestarting, onClickLabel = restartLabel) {
                            onRestartDaemon()
                        }
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                )
            }
        }
    }
}
