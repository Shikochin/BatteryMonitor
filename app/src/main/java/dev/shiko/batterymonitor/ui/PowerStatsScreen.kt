package dev.shiko.batterymonitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
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
 *  …plus a one-line data-source footer (Shizuku-only build: it doubles as the grant prompt).
 *
 * The top bar collapses from the large title into the small centred title while the content
 * scrolls ([MiuixScrollBehavior]); there is deliberately no back affordance.
 * The "在寻找其它功能吗?" footer from the reference is intentionally **not** part of this app.
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
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = modifier,
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = "耗电统计",
                largeTitle = "耗电统计",
                scrollBehavior = scrollBehavior,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
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
                onRefresh = onRefreshScenarios,
                modifier = Modifier.fillMaxWidth(),
            )
            DataSourceRow(
                shizukuGranted = state.shizukuGranted,
                rootAvailable = state.rootAvailable,
                shizukuAvailable = state.shizukuAvailable,
                onRequestShizuku = onRequestShizuku,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
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
 * The data-source footer: a single grey line explaining where the numbers come from.
 *
 * The breakdown is Shizuku-only, so this line doubles as a grant affordance: it is clickable
 * while the permission is missing, and turns into a plain status line once it is granted.
 */
@Composable
private fun DataSourceRow(
    shizukuGranted: Boolean,
    rootAvailable: Boolean,
    shizukuAvailable: Boolean,
    onRequestShizuku: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    when {
        shizukuGranted -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (rootAvailable) {
                    "数据来源：Shizuku (root) · 系统电池统计"
                } else {
                    "数据来源：Shizuku (shell) · 系统电池统计"
                },
                color = color,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }

        shizukuAvailable -> Row(
            modifier = modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRequestShizuku),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "需要 Shizuku 权限，点击授权",
                color = color,
                fontSize = 12.sp,
                maxLines = 1,
            )
            Icon(
                imageVector = MiuixIcons.Light.ChevronForward,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(14.dp),
                tint = color,
            )
        }

        else -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "未检测到 Shizuku / Sui 服务",
                color = color,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
    }
}
