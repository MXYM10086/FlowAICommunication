package com.flowai.communication.system

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.flowai.communication.ai.AiStyle
import com.flowai.communication.ai.EngineSettingsStore
import com.flowai.communication.ai.deepseek.DeepSeekClient
import com.flowai.communication.ai.deepseek.DeepSeekResult
import com.flowai.communication.ai.deepseek.StyleReplyGenerator
import com.flowai.communication.domain.CaptureFailure
import com.flowai.communication.domain.CaptureResult
import com.flowai.communication.ui.card.CardDragMath
import com.flowai.communication.ui.card.ReplyBlockStatus
import com.flowai.communication.ui.card.ReplyCard
import com.flowai.communication.ui.card.ReplyCardState
import com.flowai.communication.ui.components.FlowTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 三风格回复卡片的悬浮窗宿主（仿 [AssistantPanelWindow] 的挂载基建）。
 *
 * 与底部面板的分工：面板留给粘贴 / 手动分析 / 追问聊天；**桌宠一键流程的结果由本卡片
 * 承载**——可拖拽、可最小化缩回桌宠、关闭即销毁。
 *
 * 关键纪律（都是项目里踩过的坑）：
 * - ComposeView 挂进 WindowManager 前必须接好 `setViewTree*Owner` 三件套，否则组合直接崩溃；
 * - **恢复 / 解挂一律重建 View 层级**：re-attach 已 detach 的 View 会失去触摸响应
 *   （`AssistantPanelWindow.resumePanel` 的真机教训），状态因此全部存在 [ReplyCardState]；
 * - LayoutParams 每次 attach 前**重建**：removeView 后复用旧 params 会以错误几何重新挂载；
 * - 窗口**不加** `FLAG_LAYOUT_NO_LIMITS`：系统自动把窗口收进状态栏 / 导航栏 / 挖孔安全区，
 *   天然满足"避开状态栏、挖孔屏"，比桌宠的手动 clamp 更稳；
 * - `FLAG_NOT_FOCUSABLE`（无输入框，不抢焦点不弹 IME）+ `FLAG_NOT_TOUCH_MODAL`
 *   （卡片外触摸穿透给下层应用）；点卡片外部**不**关闭——误触丢结果代价太高。
 *
 * 生命周期语义：
 * - **最小化**：淡出并移除 View，但对象与状态（阶段 4 起还有生成 Job）存活；点桌宠恢复；
 * - **关闭 / 服务销毁**：淡出 → 移除 View → `Lifecycle` 置 DESTROYED
 *   （阶段 4 起这会取消 `lifecycleScope` 内所有 Job，进而取消在途 OkHttp 请求）→ 回调宿主置空；
 * - **截屏挂起**（阶段 2 用）：卡片先离场避免被截进画面，截完重建恢复，位置不变。
 */
class ReplyCardWindow(
    private val context: Context,
    /** 卡片销毁（关闭按钮 / 服务 onDestroy）时回调，宿主借此把引用置空。 */
    private val onClose: () -> Unit,
    /** 阶段 2 接「重新截屏」：卡片已自行挂起后回调宿主发起截屏。 */
    private val onStartCapture: (() -> Unit)? = null
) : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** 卡片全部状态。活在 View 重建之外：最小化 / 恢复 / 挂起都不丢。 */
    val cardState = ReplyCardState()

    /** 阶段 4 网络客户端与串行生成器：设置每次调用重读，设置页改完立即生效。 */
    private val client = DeepSeekClient { EngineSettingsStore(context).load() }
    private val generator = StyleReplyGenerator(client::generate)

    /** 批量串行三风格的 Job；新一轮截屏 / 关闭 / 销毁时取消。 */
    private var generationJob: Job? = null

    /** 「重试这一条」的 Job，按区块下标存；新一轮截屏 / 销毁时全部取消。 */
    private val retryJobs = mutableMapOf<Int, Job>()

    private var view: View? = null
    private var params: WindowManager.LayoutParams = buildLayoutParams()

    /** SavedStateRegistry 只允许 restore 一次，重建 View 时不重复调用。 */
    private var stateRestored = false
    private var minimized = false
    private var suspended = false
    private var destroyed = false

    /** 淡入 / 淡出期间防重入（连点关闭、关闭中途再点最小化等）。 */
    private var animating = false

    /** 松手吸边动画；再次拖拽或销毁时取消。 */
    private var snapAnimator: ValueAnimator? = null

    /** 跨 View 重建记住卡片位置：恢复 / 解挂后出现在用户放下的地方。 */
    private var lastX: Int? = null
    private var lastY: Int? = null

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    val isMinimized: Boolean get() = minimized
    val isSuspended: Boolean get() = suspended
    /** View 当前是否挂在窗口上（最小化 / 挂起 / 未显示均为 false）。 */
    val isAttached: Boolean get() = view != null

    // ---- 显示 / 关闭 --------------------------------------------------------

    /** 首次显示卡片（已显示或已销毁则无操作），带淡入。 */
    fun show() {
        if (destroyed || view != null) return
        attach(fadeIn = true)
    }

    /** 关闭：淡出 → 移除 → 销毁（取消阶段 4 的生成 Job）→ 通知宿主。 */
    fun close() {
        if (destroyed || animating) return
        val v = view
        if (v == null) {
            destroy()
            return
        }
        animating = true
        v.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction {
                animating = false
                destroy()
            }
            .start()
    }

    /** 最小化：淡出并移除 View，但对象 / 状态 / Job 存活，等桌宠点击恢复。 */
    fun minimize() {
        if (destroyed || animating || view == null) return
        animating = true
        view?.animate()
            ?.alpha(0f)
            ?.setDuration(FADE_DURATION_MS)
            ?.withEndAction {
                animating = false
                if (!destroyed) {
                    minimized = true
                    detachView()
                    Log.i(TAG, "reply card minimized")
                }
            }
            ?.start()
    }

    /** 从最小化恢复：重建 View 层级再挂载（不 re-attach 旧 View），位置不变，带淡入。 */
    fun restore() {
        if (destroyed || !minimized || animating) return
        minimized = false
        if (view == null) attach(fadeIn = true)
        Log.i(TAG, "reply card restored")
    }

    /**
     * 终态销毁：移除 View、清 ViewModelStore、Lifecycle 置 DESTROYED
     * （阶段 4 起在此取消所有生成 Job → OkHttp 在途请求随之取消），最后回调宿主。
     */
    fun destroy() {
        if (destroyed) return
        destroyed = true
        // 显式取消生成（lifecycleScope 随 DESTROYED 也会取消，这里是双保险 + 语义自明）。
        cancelGeneration()
        snapAnimator?.cancel()
        snapAnimator = null
        val v = view
        if (v != null) {
            v.animate().cancel()
            runCatching { windowManager.removeViewImmediate(v) }
                .onFailure { Log.e(TAG, "removeView failed on destroy", it) }
        }
        view = null
        minimized = false
        suspended = false
        store.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        onClose()
        Log.i(TAG, "reply card destroyed")
    }

    // ---- 截屏挂起 / 恢复（阶段 2 起使用） ------------------------------------

    /** 「重新截屏」入口：卡片先离场（避免被截进画面），再回调宿主发起截屏。 */
    fun beginCapture() {
        val start = onStartCapture ?: return
        suspendForCapture()
        start()
    }

    /** 挂起：移除 View 但保留对象与状态（同 [minimize]，但不算"最小化"）。 */
    fun suspendForCapture() {
        if (destroyed || view == null) return
        suspended = true
        detachView()
        Log.i(TAG, "reply card suspended for capture")
    }

    /** 截屏结束后恢复卡片（重建 View），位置不变。 */
    fun resumeAfterCapture() {
        if (destroyed || !suspended) return
        suspended = false
        if (view == null) attach(fadeIn = true)
        Log.i(TAG, "reply card resumed after capture")
    }

    // ---- 截屏结果：只本机 OCR，不走分析流水线 --------------------------------

    /** 新一轮截屏前清空：旧原文 / 旧错误 / 旧回复不混进新的一帧。 */
    fun prepareForCapture() {
        // 上一轮生成若还在飞：先取消，否则它会往已清空的区块里写旧结果。
        cancelGeneration()
        cardState.ocrText = ""
        cardState.cardError = null
        cardState.progress = null
        cardState.sourceCollapsed = true
        cardState.replies.forEach { it.status = ReplyBlockStatus.Idle }
    }

    /**
     * 截屏帧回来（缓存文件路径）：**本机** decode → 删缓存 → OCR → 原文上卡片。
     *
     * 只有识别出的文字留在卡片上（阶段 4 也只把这份文字发给 API）；原图解码后即删，
     * 不离开手机。不调用 [FrameAnalysis.analyze]——那是面板的分析流水线，卡片只要 OCR 原文。
     * 识别失败不崩溃：映射为与面板同一套引导语（[FrameAnalysis.guidanceFor]）。
     */
    fun deliverCaptureImage(path: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { readFrame(path) }
            when (result) {
                is CaptureResult.Success -> {
                    Log.i(TAG, "card ocr ok chars=${result.text.length}")
                    cardState.ocrText = result.text.take(MAX_OCR_CHARS)
                    cardState.cardError = null
                    // 自动展开：让用户一眼看到这次识别到了什么，便于判断 OCR 是否读对。
                    cardState.sourceCollapsed = false
                    // 阶段 4：原文上卡片的同时发起串行三风格生成（只有这份文字离开手机）。
                    startGeneration(cardState.ocrText)
                }
                is CaptureResult.Failure -> {
                    Log.w(TAG, "card ocr failed: ${result.reason}")
                    cardState.cardError = FrameAnalysis.guidanceFor(result.reason)
                }
            }
            showAfterCapture()
        }
    }

    /** 文本路径兜底（当前链路 activity 只交图片）：同样只上卡片，不崩溃。 */
    fun deliverCapture(text: String?, failure: String?) {
        if (!failure.isNullOrBlank()) {
            cardState.cardError = failure
        } else if (!text.isNullOrBlank()) {
            cardState.ocrText = text.take(MAX_OCR_CHARS)
            cardState.cardError = null
            cardState.sourceCollapsed = false
            startGeneration(cardState.ocrText)
        }
        showAfterCapture()
    }

    /**
     * 结果回到卡片后的出场方式：
     * - 挂起中（截屏前正显示）→ 重建恢复；
     * - 最小化中 → 保持最小化，等用户点桌宠（结果已在，不抢注意力）；
     * - 新建 yet 未挂上（桌宠一键直接发起、从没显示过）→ 淡入显示，否则结果无处可去。
     */
    private fun showAfterCapture() {
        if (destroyed) return
        if (suspended) {
            resumeAfterCapture()
            return
        }
        if (minimized) return
        if (view == null) attach(fadeIn = true)
    }

    /** decode + 删缓存 + OCR；任何异常都变成 Failure，不往外抛。调用方已在 IO 线程。 */
    private suspend fun readFrame(path: String): CaptureResult = runCatching {
        val file = File(path)
        try {
            val bitmap = BitmapFactory.decodeFile(path)
                ?: return@runCatching CaptureResult.Failure(CaptureFailure.UNKNOWN)
            try {
                OcrPipeline.recognize(bitmap)
            } finally {
                bitmap.recycle()
            }
        } finally {
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "could not delete capture cache", it) }
        }
    }.getOrElse { e ->
        Log.w(TAG, "card frame read crashed", e)
        CaptureResult.Failure(CaptureFailure.UNKNOWN)
    }

    // ---- 阶段 1 样例内容 -----------------------------------------------------

    /**
     * 写入写死的三风格样例并显示卡片：**不截屏、不联网**。
     *
     * 首页测试入口与桌宠单击（阶段 1 临时行为）都走这里，用于验收卡片交互
     * （拖拽 / 吸边 / 最小化 / 复制 Toast / 打字机动画）。阶段 2 起桌宠单击改为
     * 真实"截屏 → OCR"流程，本方法保留给首页测试按钮。
     */
    fun showTestContent() {
        // 写死样例会覆盖区块状态：在飞的真生成必须先停，否则两者互相写区块。
        cancelGeneration()
        cardState.ocrText = SAMPLE_OCR_TEXT
        cardState.cardError = null
        cardState.progress = null
        cardState.sourceCollapsed = true
        cardState.replies.forEachIndexed { index, block ->
            block.status = ReplyBlockStatus.Done(SAMPLE_REPLIES[index])
        }
        if (minimized) restore() else show()
    }

    // ---- 阶段 4：串行三风格生成与取消 ----------------------------------------

    /**
     * 发起串行三风格生成：温暖 → 毒舌 → 冷静科学，一次一路，单路失败不拖死后续。
     *
     * Job 挂在 [lifecycleScope] 上：卡片关闭或服务销毁会把 Lifecycle 置 DESTROYED、
     * 整个 scope 取消 → 本 Job 取消 → Retrofit suspend 传播取消 → OkHttp 在途 call
     * 随之 cancel；**最小化不取消**（对象与 Job 存活，恢复时结果已在或继续生成）。
     * 状态写回都在主线程（lifecycleScope 即主线程 dispatcher），UI 直接读 [cardState]。
     */
    private fun startGeneration(ocrText: String) {
        if (destroyed || ocrText.isBlank()) return
        cancelGeneration()
        generationJob = lifecycleScope.launch {
            generator.generateAll(
                ocrText = ocrText,
                onStarted = { style ->
                    val index = AiStyle.ALL.indexOf(style)
                    cardState.progress = "正在生成 ${index + 1}/${AiStyle.ALL.size}…"
                    cardState.replies.getOrNull(index)?.status = ReplyBlockStatus.Loading
                    Log.i(TAG, "card progress -> ${cardState.progress} block=${style.id}=Loading")
                },
                onFinished = { style, result ->
                    val index = AiStyle.ALL.indexOf(style)
                    cardState.replies.getOrNull(index)?.status = result.toBlockStatus()
                    Log.i(TAG, "card block ${style.id} -> ${result::class.java.simpleName}")
                }
            )
            cardState.progress = null
            Log.i(TAG, "reply card generation finished")
        }
    }

    /**
     * 「重试这一条」：只重发该风格，不动其它区块。
     *
     * 仅当批量生成不在进行时可用（UI 在生成中隐藏重试按钮，这里再双保险），
     * 守住规格"串行"的约定——不与批量串行队列并发出第二路请求。
     */
    fun retryStyle(index: Int) {
        if (destroyed || cardState.progress != null) return
        val style = AiStyle.ALL.getOrNull(index) ?: return
        val block = cardState.replies.getOrNull(index) ?: return
        retryJobs[index]?.cancel()
        block.status = ReplyBlockStatus.Loading
        Log.i(TAG, "reply card retry style=${style.id}")
        retryJobs[index] = lifecycleScope.launch {
            val result = generator.generateOne(style, cardState.ocrText)
            block.status = result.toBlockStatus()
            retryJobs.remove(index)
        }
    }

    /** 取消批量生成与全部重试：新一轮截屏前、样例覆盖前、销毁时调用。 */
    private fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        cardState.progress = null
    }

    /** 网络结果 → 区块状态；失败只带友好文案，异常对象不越过 UI 边界。 */
    private fun DeepSeekResult.toBlockStatus(): ReplyBlockStatus = when (this) {
        is DeepSeekResult.Success -> ReplyBlockStatus.Done(text)
        is DeepSeekResult.Failed -> ReplyBlockStatus.Error(failure.friendlyMessage)
    }

    // ---- View 构建 / 窗口参数 ------------------------------------------------

    private fun attach(fadeIn: Boolean) {
        // 生命周期引导放在 attach 内：show / restore / 截屏恢复 / 截屏结果首显 四条路径
        // 都可能第一次挂 View。缺了 performRestore 或 Lifecycle 停在 INITIALIZED，
        // Compose 不会建立组合——窗口挂上但整块透明（真机/模拟器踩过的坑）。
        if (!stateRestored) {
            savedStateController.performRestore(null)
            stateRestored = true
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }
        params = buildLayoutParams().also { fresh ->
            // 跨重建保留位置：恢复 / 解挂后出现在用户上次放下的地方。
            lastX?.let { fresh.x = it }
            lastY?.let { fresh.y = it }
        }
        val maxHeight = maxHeightDp()
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@ReplyCardWindow)
            setViewTreeViewModelStoreOwner(this@ReplyCardWindow)
            setViewTreeSavedStateRegistryOwner(this@ReplyCardWindow)
            setContent {
                FlowTheme {
                    ReplyCard(
                        state = cardState,
                        maxHeight = maxHeight,
                        onDragBy = ::moveBy,
                        onDragEnd = ::snapToEdge,
                        onMinimize = ::minimize,
                        onClose = ::close,
                        onRetryStyle = ::retryStyle,
                        onRecapture = if (onStartCapture != null) { { beginCapture() } } else null
                    )
                }
            }
        }
        runCatching { windowManager.addView(composeView, params) }
            .onFailure {
                Log.e(TAG, "could not add reply card window", it)
                return
            }
        view = composeView
        if (fadeIn) {
            composeView.alpha = 0f
            composeView.animate().alpha(1f).setDuration(FADE_DURATION_MS).start()
        }
    }

    private fun detachView() {
        snapAnimator?.cancel()
        snapAnimator = null
        val v = view ?: return
        lastX = params.x
        lastY = params.y
        v.animate().cancel()
        runCatching { windowManager.removeViewImmediate(v) }
            .onFailure { Log.e(TAG, "removeView failed", it) }
        view = null
    }

    /**
     * 卡片几何：宽 = 屏宽 80%（给拖拽留出可感知的水平空间），高 WRAP_CONTENT
     * （Compose 内部再限 [MAX_HEIGHT_FRACTION]），初始位置水平居中、纵向 1/4 屏高。
     * **每次调用都返回全新实例**——挂载前必须重建，不复用旧 params。
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val margin = (CARD_MARGIN_DP * metrics.density).toInt()
        val width = ((metrics.widthPixels * CARD_WIDTH_FRACTION).toInt())
            .coerceAtMost(metrics.widthPixels - margin * 2)
            .coerceAtLeast(1)
        return WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - width) / 2
            y = metrics.heightPixels / 4
        }
    }

    private fun maxHeightDp(): Dp {
        val metrics = context.resources.displayMetrics
        return (metrics.heightPixels / metrics.density * MAX_HEIGHT_FRACTION).dp
    }

    // ---- 拖拽 / 吸边 ---------------------------------------------------------

    /**
     * 拖动中：按手指位移更新窗口坐标，实时用 [CardDragMath] 夹在屏幕内
     * （规格："不能拖出屏幕外"）。取消进行中的吸边动画，避免两个来源打架。
     */
    private fun moveBy(dx: Float, dy: Float) {
        val v = view ?: return
        snapAnimator?.cancel()
        snapAnimator = null
        val metrics = context.resources.displayMetrics
        val margin = (CARD_MARGIN_DP * metrics.density).toInt()
        params.x = CardDragMath.clampX(params.x + dx.toInt(), v.width, metrics.widthPixels, margin)
        params.y = if (v.height > 0) {
            CardDragMath.clampY(params.y + dy.toInt(), v.height, metrics.heightPixels, margin)
        } else {
            // WRAP_CONTENT 首帧还没量出高度：只做下限保护。
            (params.y + dy.toInt()).coerceAtLeast(0)
        }
        runCatching { windowManager.updateViewLayout(v, params) }
            .onFailure { Log.w(TAG, "updateViewLayout during drag failed", it) }
    }

    /**
     * 松手吸边回弹：按卡片中心在屏幕哪半边，把 x 动画到左 / 右边缘
     * （[ValueAnimator] + 减速插值，逐帧 `updateViewLayout`）。目标即当前位置时不动画。
     */
    private fun snapToEdge() {
        val v = view ?: return
        if (destroyed) return
        val metrics = context.resources.displayMetrics
        val margin = (CARD_MARGIN_DP * metrics.density).toInt()
        val target = CardDragMath.snapTargetX(params.x, v.width, metrics.widthPixels, margin)
        if (target == params.x) return
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(params.x, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val current = view
                if (current == null || destroyed) {
                    cancel()
                    return@addUpdateListener
                }
                params.x = animation.animatedValue as Int
                runCatching { windowManager.updateViewLayout(current, params) }
            }
            start()
        }
    }

    companion object {
        private const val TAG = "FlowAI"

        /** 卡片与屏幕左右缘的最小间距。 */
        private const val CARD_MARGIN_DP = 12

        /** 卡片宽度占屏宽比例：留 20% 让水平拖拽 + 吸边可感知。 */
        private const val CARD_WIDTH_FRACTION = 0.80f

        /** 卡片最大高度占屏高比例（Compose `heightIn` 上限），超出卡片内滚动。 */
        private const val MAX_HEIGHT_FRACTION = 0.62f

        private const val FADE_DURATION_MS = 200L
        private const val SNAP_DURATION_MS = 220L

        /** 卡片保留的 OCR 原文上限，与全局输入上限同一口径。 */
        private const val MAX_OCR_CHARS = 20_000

        // 阶段 1 的写死样例（阶段 2 起被真实 OCR / 生成结果替代）。
        private val SAMPLE_OCR_TEXT =
            "我：会议纪要可以发我一份吗？\n对方：好，晚点发你，数据部分我还要再核对一下。"
        private val SAMPLE_REPLIES = listOf(
            "好呀，不着急～核对完再发就行，辛苦啦！",
            "「晚点发」，这个晚点是下个会开始前那种晚点吗？快点吧，我等着呢。",
            "收到。请在核对完数据部分后发送会议纪要；建议今天 18:00 前完成，便于归档。"
        )
    }
}
