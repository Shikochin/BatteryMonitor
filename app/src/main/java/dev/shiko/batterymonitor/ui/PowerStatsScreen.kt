package dev.shiko.batterymonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.shiko.batterymonitor.model.PowerUiState
import dev.shiko.batterymonitor.ui.component.SessionHistoryDialog
import dev.shiko.batterymonitor.ui.component.StatSummaryCard
import dev.shiko.batterymonitor.ui.component.UsageProcessCard
import dev.shiko.batterymonitor.ui.component.UsageScenarioCard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
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
import top.yukonga.miuix.kmp.icon.extended.Recent
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Root screen of the app: the "耗电统计" (power consumption statistics) page.
 *
 * Structure mirrors the Scene reference:
 *  1. [UsageProcessCard] — curve + telemetry
 *  2. [StatSummaryCard] — average power / used / estimated runtime
 *  3. [UsageScenarioCard] — per-app breakdown
 *  …plus a single-line data-source footer ("数据来源：Shizuku (root)") and nothing else.
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
 */
@Composable
fun PowerStatsScreen(
    state: PowerUiState,
    onBack: () -> Unit,
    onClearData: () -> Unit,
    onRefreshScenarios: () -> Unit,
    onRequestShizuku: () -> Unit,
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
                title = "耗电统计",
                largeTitle = "耗电统计",
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
                    IconButton(onClick = { showHistory = true }) {
                        Icon(
                            imageVector = MiuixIcons.Light.Recent,
                            contentDescription = "历史记录",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(
                            imageVector = MiuixIcons.Light.Delete,
                            contentDescription = "清除数据",
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
                refreshTexts = listOf("下拉刷新", "松开刷新", "正在刷新…", "刷新完成"),
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
                    UsageProcessCard(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    StatSummaryCard(
                        summary = state.summary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    UsageScenarioCard(
                        scenarios = state.scenarios,
                        // Shizuku is the only data source: without the grant the card shows "去授权"
                        // instead of an (empty) list.
                        needsShizuku = !state.shizukuGranted,
                        loading = state.loadingScenarios,
                        onRequestShizuku = onRequestShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DataSourceRow(
                        shizukuGranted = state.shizukuGranted,
                        rootAvailable = state.rootAvailable,
                        shizukuAvailable = state.shizukuAvailable,
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
            title = "清除本次统计？",
            onDismissRequest = { showClearConfirm = false },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "确定",
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
 * The data-source footer: exactly one grey line, `数据来源：Shizuku (root|shell)`.
 *
 * Nothing else is shown here — the grant affordance lives in [UsageScenarioCard], so this line is
 * pure attribution and only names the mode the Shizuku shell is running in.
 */
@Composable
private fun DataSourceRow(
    shizukuGranted: Boolean,
    rootAvailable: Boolean,
    shizukuAvailable: Boolean,
    modifier: Modifier = Modifier,
) {
    val text = when {
        // root reads continuous sysfs current; plain shell can only see `dumpsys battery`.
        shizukuGranted && rootAvailable -> "数据来源：Shizuku (root)"
        shizukuGranted -> "数据来源：Shizuku (shell)"
        shizukuAvailable -> "数据来源：Shizuku (未授权)"
        else -> "数据来源：Shizuku (未连接)"
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}
