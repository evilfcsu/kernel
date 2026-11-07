package me.weishu.kernelsu.ui.screen.cardkey

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.weishu.kernelsu.R
import kotlin.math.roundToInt

/**
 * 卡密页的配色。取自 PigeonSu 图标本身：一枚暖灰/近黑的鸽子剪影，
 * 自适应图标底色 `#E2E1DE`，前景最亮 `#E3E0DF`、最暗 `#373435`。
 * 所以整页走"暖白 → 暖灰"的中性色，不用 Material 默认那套紫。
 */
private class GatePalette(
    val bgTop: Color,
    val bgBottom: Color,
    val glow: Color,
    val emblemBg: Color,
    val emblemRing: Color,
    val card: Color,
    val cardBorder: Color,
    val title: Color,
    val subtitle: Color,
    val fieldBg: Color,
    val fieldBorder: Color,
    val fieldBorderFocused: Color,
    val cursor: Color,
    val error: Color,
    val accentStart: Color,
    val accentEnd: Color,
    val onAccent: Color,
    val accentDisabled: Color,
    val onAccentDisabled: Color,
)

private fun gatePalette(dark: Boolean) = if (dark) GatePalette(
    bgTop = Color(0xFF171614),
    bgBottom = Color(0xFF09090A),
    glow = Color(0x1FFFFFFF),
    emblemBg = Color(0xFF242221),
    emblemRing = Color(0x24FFFFFF),
    card = Color(0xFF1C1B1A),
    cardBorder = Color(0x1FFFFFFF),
    title = Color(0xFFF3F1EE),
    subtitle = Color(0xFF9C958E),
    fieldBg = Color(0xFF262422),
    fieldBorder = Color(0x1FFFFFFF),
    fieldBorderFocused = Color(0xFFE7E3DE),
    cursor = Color(0xFFE7E3DE),
    error = Color(0xFFF2B8B5),
    accentStart = Color(0xFFEDEAE5),
    accentEnd = Color(0xFFC6C1BA),
    onAccent = Color(0xFF17150F),
    accentDisabled = Color(0xFF2A2826),
    onAccentDisabled = Color(0xFF6B6660),
) else GatePalette(
    bgTop = Color(0xFFFDFCFB),
    bgBottom = Color(0xFFE9E6E2),
    glow = Color(0x14807970),
    emblemBg = Color(0xFFFFFFFF),
    emblemRing = Color(0x14000000),
    card = Color(0xFFFFFFFF),
    cardBorder = Color(0x0F000000),
    title = Color(0xFF1B1917),
    subtitle = Color(0xFF736D67),
    fieldBg = Color(0xFFF4F2EF),
    fieldBorder = Color(0x14000000),
    fieldBorderFocused = Color(0xFF2A2724),
    cursor = Color(0xFF2A2724),
    error = Color(0xFFB3261E),
    accentStart = Color(0xFF38342F),
    accentEnd = Color(0xFF14120E),
    onAccent = Color(0xFFF7F5F2),
    accentDisabled = Color(0xFFE4E1DD),
    onAccentDisabled = Color(0xFFA9A39D),
)

/**
 * PigeonSU 的卡密闸门页。
 *
 * 这一页**每次启动都会出现**（"已激活"不落盘，状态只活在当前 Activity 里）。为了方便，
 * 它会把上次验证通过、且用户勾了"记住卡密"的那张卡密预填进输入框，用户按一下即可进入。
 *
 * 配色**自带**，不依赖外面的 uiMode —— 管理器有 Material 和 Miuix 两套主题，Miuix 模式下
 * 没有 MaterialTheme，直接取 `MaterialTheme.colorScheme` 会拿到默认亮色，深色模式下很突兀。
 *
 * 另外刻意**不碰模糊/背景折射**那套（`layerBackdrop` / `rememberBlurBackdrop`）——
 * 同一节点叠两个 backdrop 会让 hwui 的 RenderNode 自引用递归到栈溢出，项目历史上栽过。
 * 这里只用渐变、圆角、阴影和位移动画。
 */
@Composable
fun CardKeyScreen(
    darkMode: Boolean,
    onActivated: () -> Unit,
) {
    val p = remember(darkMode) { gatePalette(darkMode) }
    MaterialTheme(colorScheme = if (darkMode) darkColorScheme() else lightColorScheme()) {
        val focus = LocalFocusManager.current
        // 预填上次记住的卡密；输入框内容本身也随 Activity 存档，旋转不丢
        var input by rememberSaveable { mutableStateOf(CardKeyStore.rememberedKey().orEmpty()) }
        var remember by rememberSaveable { mutableStateOf(CardKeyStore.isRememberEnabled()) }
        var error by rememberSaveable { mutableStateOf<String?>(null) }
        var succeeded by rememberSaveable { mutableStateOf(false) }
        var focused by remember { mutableStateOf(false) }
        // 正在请求服务端；成功后展示的有效期
        var busy by remember { mutableStateOf(false) }
        var expireAt by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        val emptyMsg = stringResource(R.string.cardkey_empty)
        val invalidMsg = stringResource(R.string.cardkey_invalid)
        val networkMsg = stringResource(R.string.cardkey_network_error)

        // 入场：整体淡入 + 轻轻上浮
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            enter.animateTo(1f, tween(durationMillis = 560, easing = FastOutSlowInEasing))
        }

        // 失败时横向抖一下
        val shake = remember { Animatable(0f) }
        LaunchedEffect(error) {
            if (error != null) {
                shake.snapTo(0f)
                shake.animateTo(
                    0f,
                    keyframes {
                        durationMillis = 340
                        0f at 0
                        -11f at 60
                        11f at 130
                        -7f at 200
                        4f at 270
                        0f at 340
                    },
                )
            }
        }

        // 成功后亮一下对勾再进管理器
        LaunchedEffect(succeeded) {
            if (succeeded) {
                delay(460)
                onActivated()
            }
        }

        fun submit() {
            val key = input.trim()
            if (key.isEmpty()) {
                error = emptyMsg
                return
            }
            if (busy) return
            busy = true
            error = null
            scope.launch {
                // verify → 失败再 activate（首次绑定这台设备），都在 CardKeyApi 里。
                // 兜底：网络卡住或异常时也要把转圈停掉，否则界面会一直转。
                val result = try {
                    withTimeoutOrNull(25_000) { CardKeyApi.authenticate(key) }
                } catch (t: Throwable) {
                    null
                } finally {
                    busy = false
                }
                if (result == null) {
                    error = networkMsg
                    return@launch
                }
                if (result.ok) {
                    focus.clearFocus()
                    CardKeyStore.setRememberEnabled(remember)
                    if (remember) CardKeyStore.remember(key) else CardKeyStore.forget()
                    expireAt = result.expireAt
                    result.heartbeat?.let { CardKeyApi.startHeartbeat(it) }
                    succeeded = true
                } else {
                    error = when {
                        result.networkError -> networkMsg
                        // 这台服务端对"卡密不存在"回的是 400 参数不完整，直接透出去会让人以为是我们的参数错了
                        result.msg.contains("参数不完整") -> invalidMsg
                        else -> result.msg.ifBlank { invalidMsg }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(p.bgTop, p.bgBottom))),
        ) {
            // 背后一团很淡的径向光，避免整页太平
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(p.glow, Color.Transparent))),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = enter.value
                            translationY = (1f - enter.value) * 44f
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // ── 徽标 ────────────────────────────────────────────
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .shadow(elevation = 18.dp, shape = CircleShape, clip = false)
                            .clip(CircleShape)
                            .background(p.emblemBg)
                            .border(1.dp, p.emblemRing, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_sad_fg),
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                        )
                    }

                    Spacer(Modifier.height(22.dp))

                    Text(
                        text = stringResource(R.string.app_name),
                        color = p.title,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 3.2.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.cardkey_subtitle),
                        color = p.subtitle,
                        fontSize = 13.sp,
                        letterSpacing = 0.4.sp,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(30.dp))

                    // ── 卡片 ────────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(elevation = 22.dp, shape = RoundedCornerShape(28.dp), clip = false)
                            .clip(RoundedCornerShape(28.dp))
                            .background(p.card)
                            .border(1.dp, p.cardBorder, RoundedCornerShape(28.dp))
                            .padding(horizontal = 20.dp, vertical = 22.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.cardkey_label),
                            color = p.subtitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 1.6.sp,
                        )
                        Spacer(Modifier.height(10.dp))

                        // 自绘输入框：完全可控，不受 Material 默认配色影响
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset(shake.value.roundToInt(), 0) }
                                .height(56.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(p.fieldBg)
                                .border(
                                    width = if (focused) 1.5.dp else 1.dp,
                                    color = when {
                                        error != null -> p.error
                                        focused -> p.fieldBorderFocused
                                        else -> p.fieldBorder
                                    },
                                    shape = RoundedCornerShape(16.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            BasicTextField(
                                value = input,
                                onValueChange = { raw ->
                                    input = raw.uppercase().take(32)
                                    error = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { focused = it.isFocused }
                                    .padding(horizontal = 16.dp),
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = p.title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Medium,
                                    letterSpacing = 4.sp,
                                    textAlign = TextAlign.Center,
                                ),
                                cursorBrush = SolidColor(p.cursor),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                            )
                            if (input.isEmpty()) {
                                Text(
                                    text = "•  •  •  •  •  •",
                                    color = p.subtitle.copy(alpha = 0.55f),
                                    fontSize = 15.sp,
                                    letterSpacing = 2.sp,
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = error != null,
                            enter = fadeIn(tween(180)) + expandVertically(tween(180)),
                            exit = fadeOut(tween(140)) + shrinkVertically(tween(140)),
                        ) {
                            Text(
                                text = error.orEmpty(),
                                color = p.error,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 10.dp, start = 4.dp),
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        RememberToggle(
                            checked = remember,
                            onCheckedChange = { remember = it },
                            label = stringResource(R.string.cardkey_remember),
                            p = p,
                        )

                        Spacer(Modifier.height(12.dp))

                        // 主按钮：渐变底 + 按下微缩；请求期间转圈并锁住
                        val enabled = input.isNotBlank() && !succeeded && !busy
                        val interaction = remember { MutableInteractionSource() }
                        val pressed by interaction.collectIsPressedAsState()
                        val pressScale by animateFloatAsState(
                            targetValue = if (pressed) 0.975f else 1f,
                            animationSpec = tween(120),
                            label = "pressScale",
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp)
                                .graphicsLayer {
                                    scaleX = pressScale
                                    scaleY = pressScale
                                }
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (enabled) {
                                        Brush.linearGradient(listOf(p.accentStart, p.accentEnd))
                                    } else {
                                        Brush.linearGradient(listOf(p.accentDisabled, p.accentDisabled))
                                    },
                                )
                                .clickable(
                                    enabled = enabled,
                                    interactionSource = interaction,
                                    indication = null,
                                ) { submit() },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (busy) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = p.onAccentDisabled,
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.cardkey_verifying),
                                        color = p.onAccentDisabled,
                                        fontSize = 15.sp,
                                        letterSpacing = 1.4.sp,
                                    )
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.cardkey_action),
                                    color = if (enabled) p.onAccent else p.onAccentDisabled,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.4.sp,
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    Text(
                        text = stringResource(R.string.cardkey_footer),
                        color = p.subtitle.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        letterSpacing = 0.8.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // ── 通过后的对勾 ────────────────────────────────────────
            AnimatedVisibility(
                visible = succeeded,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160)),
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(p.bgTop.copy(alpha = if (darkMode) 0.86f else 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val appear = remember { Animatable(0f) }
                    LaunchedEffect(Unit) {
                        appear.animateTo(1f, tween(durationMillis = 300, easing = FastOutSlowInEasing))
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.graphicsLayer { alpha = appear.value },
                    ) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .graphicsLayer {
                                    scaleX = 0.6f + appear.value * 0.4f
                                    scaleY = 0.6f + appear.value * 0.4f
                                    alpha = appear.value
                                }
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(p.accentStart, p.accentEnd))),
                            contentAlignment = Alignment.Center,
                        ) {
                            Canvas(modifier = Modifier.size(42.dp)) {
                                val w = size.width
                                val h = size.height
                                val path = Path().apply {
                                    moveTo(w * 0.16f, h * 0.52f)
                                    lineTo(w * 0.41f, h * 0.76f)
                                    lineTo(w * 0.85f, h * 0.25f)
                                }
                                drawPath(
                                    path = path,
                                    color = p.onAccent,
                                    style = Stroke(
                                        width = w * 0.095f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            }
                        }
                        // 服务端算出来的有效期
                        expireAt?.let { expire ->
                            Spacer(Modifier.height(18.dp))
                            Text(
                                text = stringResource(R.string.cardkey_expire, expire),
                                color = p.title.copy(alpha = 0.9f),
                                fontSize = 13.sp,
                                letterSpacing = 0.6.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** "记住卡密"开关：自绘的小胶囊，配色跟着本页走，不受 Material 默认色影响。 */
@Composable
private fun RememberToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    p: GatePalette,
) {
    val knob by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(180),
        label = "knob",
    )
    val track by animateColorAsState(
        targetValue = if (checked) p.accentEnd else p.fieldBorder,
        animationSpec = tween(180),
        label = "track",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = p.subtitle,
            fontSize = 12.sp,
            letterSpacing = 0.6.sp,
        )
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 24.dp)
                .clip(CircleShape)
                .background(track),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(start = 2.dp)
                    .offset(x = (knob * 18f).dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(if (checked) p.bgTop else p.subtitle.copy(alpha = 0.75f)),
            )
        }
    }
}

/**
 * 把管理器内容整块挡在卡密后面。
 *
 * **每次启动都要过这道门**：这里只用 `rememberSaveable`（Activity 的存档状态），
 * 不往磁盘写任何"已通过"标记 —— 冷启动一定是 false，所以闸门一定出现；旋转屏幕之类
 * 的重建则不会重复拦人。
 *
 * 只包一层，不把 MainActivity 的正文拆开：没通过验证时 [content] 根本不进入组合，
 * 管理器那套 ViewModel / NavDisplay 都不会被创建。
 *
 * [onContentReady] 两条分支都会调 —— 它是 Activity 用来收起启动图的，只在管理器分支里调
 * 的话卡密页会被启动图一直盖着。
 */
@Composable
fun CardKeyGate(
    darkMode: Boolean,
    onContentReady: () -> Unit,
    content: @Composable () -> Unit,
) {
    var passed by rememberSaveable { mutableStateOf(false) }

    SideEffect { onContentReady() }

    if (passed) {
        content()
    } else {
        CardKeyScreen(darkMode = darkMode, onActivated = { passed = true })
    }
}
