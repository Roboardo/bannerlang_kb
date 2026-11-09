package net.bannerlang.kb

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

@SuppressLint("ViewConstructor")
class KeyboardPanel(ctx: Context, private val host: Host, embedPreview: Boolean = true) : LinearLayout(ctx) {
    interface Host {
        val isIme: Boolean
        fun send() {}
        fun sendHold() {}
        fun switchKeyboard() {}
        fun imePicker() {}
        fun openApp()
    }

    val msg = Message()
    private val composing = mutableListOf<Grapheme>()
    var lang: Language? = null
        private set
    private var seenVersion = -1

    private val kbPanel: LinearLayout
    private val editorHolder: FrameLayout
    val messageView: MessageView
    private val suggestionRow: LinearLayout
    private val suggestionScroll: HorizontalScrollView
    private val keyGrid: KeyGridView
    private val tabs: GroupTabsView
    private var pages = emptyList<KeyPage>()
    private var pageGroup: String? = null
    private lateinit var langKey: BannerKeyView
    private lateinit var arrowKey: View
    private lateinit var backspaceKey: View
    private lateinit var editKey: ImageButton

    var onChanged: () -> Unit = {}

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.BG)
        messageView = MessageView(ctx).apply {
            message = msg
            onTap = { g -> commitComposing(); msg.setCursor(g); refresh() }
            onSelect = { a, b -> commitComposing(); msg.select(a, b); refresh() }
            onHold = { x, y -> showSelectionMenu(RectF(x, y, x, y)) }
            onSelectEnd = { showSelectionMenu() }
            onDragStart = { selectionMenu?.dismiss() }
            onMove = { g -> msg.moveSelection(g); refresh() }
        }
        suggestionRow = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4), 0, dpi(4), 0)
        }
        suggestionScroll = HorizontalScrollView(ctx).apply {
            setBackgroundColor(Ui.BG)
            isHorizontalScrollBarEnabled = false
            addView(suggestionRow, FrameLayout.LayoutParams(WRAP, MATCH))
        }
        keyGrid = KeyGridView(ctx) { g -> onGrapheme(g) }.apply {
            onEmptyTap = { host.openApp() }
        }
        tabs = GroupTabsView(ctx) { i -> pageGroup = pages[i].group?.id; showKeys() }
        kbPanel = LinearLayout(ctx).apply {
            orientation = VERTICAL
            if (embedPreview) addView(messageView, lp(MATCH, WRAP))
            addView(suggestionScroll, lp(MATCH, dpi(46)))
            addView(tabs, lp(MATCH, dpi(GROUP_TABS_HEIGHT)).apply { setMargins(dpi(KEY_GRID_MARGIN), 0, dpi(KEY_GRID_MARGIN), 0) })
            addView(keyGrid, lp(MATCH, ctx.keyGridHeight()).apply { setMargins(dpi(KEY_GRID_MARGIN), 0, dpi(KEY_GRID_MARGIN), 0) })
            addView(bottomRow(), lp(MATCH, dpi(52)))
        }
        editorHolder = FrameLayout(ctx).apply {
            visibility = View.GONE
            setBackgroundColor(Ui.KEY)
            setPadding(0, dpi(1), 0, 0)
        }
        addView(kbPanel, lp(MATCH, WRAP))
        addView(editorHolder, lp(MATCH, WRAP))
        reloadLanguage()
    }

    fun reloadLanguage() {
        if (seenVersion == Repo.version) return
        seenVersion = Repo.version
        val l = Repo.selected()
        if (l?.id != lang?.id) { composing.clear(); pageGroup = null }
        lang = l
        val fresh = composing.mapNotNull { c -> l?.grapheme(c.id) }
        composing.clear(); composing.addAll(fresh)
        keyGrid.emptyText = if (l == null) "No languages yet.\nTap here to create one in the app."
                            else "This language has no banners.\nTap here to add some in the app."
        langKey.banner = l?.icon
        val rtl = l?.rtl == true
        messageView.rtl = rtl
        messageView.keepWords = l?.keepWords == true
        keyGrid.rtl = rtl
        arrowKey.scaleX = if (rtl) -1f else 1f
        backspaceKey.scaleX = if (rtl) -1f else 1f
        showKeys()
        refresh()
    }

    private fun showKeys() {
        val l = lang
        pages = l?.pages() ?: emptyList()
        val tabbed = pages.size > 1
        val i = pages.indexOfFirst { it.group?.id == pageGroup }.coerceAtLeast(0)
        keyGrid.keys = if (tabbed) pages[i].keys else l?.graphemes?.toList() ?: emptyList()
        tabs.visibility = if (tabbed) View.VISIBLE else View.GONE
        if (tabbed) tabs.set(pages.map { it.icon }, i, l?.rtl == true)
        val h = context.keyGridHeight() - if (tabbed) dpi(GROUP_TABS_HEIGHT) else 0
        if (keyGrid.layoutParams.height != h) { keyGrid.layoutParams.height = h; keyGrid.requestLayout() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bottomRow(): LinearLayout {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(2), dpi(3), dpi(2), dpi(4))
        }
        fun add(v: View, weight: Float) = row.addView(v, lp(0, MATCH, weight).apply { setMargins(dpi(2), 0, dpi(2), 0) })
        val ctx = context

        langKey = BannerKeyView(ctx).apply {
            contentDescription = "Next language"
            setOnClickListener { tap(it); nextLanguage() }
            setOnLongClickListener { host.openApp(); true }
        }
        add(langKey, 1f)
        if (host.isIme) add(ctx.iconButton(R.drawable.ic_keyboard, "Switch keyboard") {
            tap(it); host.switchKeyboard()
        }.apply { setOnLongClickListener { host.imePicker(); true } }, 1f)
        arrowKey = ctx.iconButton(R.drawable.ic_arrow, "Insert as typed") { tap(it); onArrow() }
        add(arrowKey, 1f)
        add(ctx.iconButton(R.drawable.ic_space, "Space") { tap(it); insert(Token.Space) }, 2.4f)
        add(ctx.iconButton(R.drawable.ic_newline, "New line") { tap(it); insert(Token.Newline) }, 1f)
        editKey = ctx.iconButton(R.drawable.ic_new_word, "New word") { tap(it); onEdit() }
        add(editKey, 1f)
        backspaceKey = repeatingButton(ctx.iconButton(R.drawable.ic_backspace, "Backspace") {}) { onBackspace() }
        add(backspaceKey, 1.2f)
        if (host.isIme) add(ctx.iconButton(R.drawable.ic_return, "Send", Ui.ACCENT) { tap(it); host.send() }.apply {
            setOnLongClickListener { host.sendHold(); true }
        }, 1.4f)
        return row
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatingButton(b: ImageButton, action: () -> Unit): ImageButton {
        val repeat = object : Runnable {
            override fun run() { action(); b.postDelayed(this, 60) }
        }
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true; tap(v); action()
                    v.postDelayed(repeat, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false; v.removeCallbacks(repeat)
                }
            }
            true
        }
        return b
    }

    private fun tap(v: View) = v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

    fun refresh() {
        if (!msg.hasSelection) selectionMenu?.dismiss()
        messageView.composing = composed()
        messageView.update()
        val editing = selectedWord() != null
        editKey.setImageResource(if (editing) R.drawable.ic_edit else R.drawable.ic_new_word)
        editKey.contentDescription = if (editing) "Edit word" else "New word"
        refreshSuggestions()
        onChanged()
    }

    private fun refreshSuggestions() {
        suggestionRow.removeAllViews()
        val l = lang ?: return
        if (composing.isEmpty()) return
        val words = l.suggestions(composing.map { it.id }, Repo.frequentFirst)
        if (words.isEmpty()) {
            suggestionRow.addView(context.label("No matching banners", 13f).apply { maxLines = 1; setPadding(dpi(6), 0, dpi(6), 0) })
        }
        for (w in words) {
            suggestionRow.addView(BannerStripView(context, w.banners, dp(34f)).apply {
                rtl = l.rtl
                background = Ui.bg(Ui.KEY, dp(8f))
                contentDescription = "Word suggestion"
                setOnClickListener { tap(it); applyWord(w) }
            }, lp(WRAP, WRAP).apply { setMargins(dpi(3), 0, dpi(3), 0) })
        }
        suggestionScroll.scrollTo(0, 0)
    }

    private fun composed() = lang?.compose(composing) ?: composing.map { it.banner }

    private fun onGrapheme(g: Grapheme) {
        if (msg.hasSelection) msg.deleteSelection()
        composing.add(g)
        refresh()
    }

    fun commitComposing() {
        if (composing.isEmpty()) return
        val tokens = composed().map { Token.B(it) }
        composing.clear()
        msg.insert(tokens)
    }

    private fun applyWord(w: Word) {
        composing.clear()
        msg.insert(w.banners.map { Token.B(it) })
        w.uses++
        Repo.save()
        refresh()
    }

    private fun onArrow() {
        if (composing.isNotEmpty()) commitComposing()
        else if (msg.hasSelection) msg.setCursor(msg.selEnd)
        else msg.setCursor(msg.cursor + 1)
        refresh()
    }

    private fun insert(t: Token) {
        commitComposing()
        msg.insert(listOf(t))
        refresh()
    }

    private fun onBackspace() {
        if (composing.isNotEmpty()) composing.removeAt(composing.size - 1)
        else msg.backspace()
        refresh()
    }

    private fun nextLanguage() {
        val all = Repo.languages
        if (all.isEmpty()) { host.openApp(); return }
        commitComposing()
        val i = all.indexOf(lang)
        Repo.select(all[(i + 1) % all.size].id)
        reloadLanguage()
    }

    private fun selectedBanners() =
        if (msg.hasSelection) msg.tokens.subList(msg.selStart, msg.selEnd).mapNotNull { (it as? Token.B)?.banner }
        else emptyList()

    private fun selectedWord(): Word? {
        if (composing.isNotEmpty()) return null
        val banners = selectedBanners().ifEmpty { return null }
        return lang?.words?.firstOrNull { it.banners == banners }
    }

    private fun onEdit() {
        val l = lang ?: run { host.openApp(); return }
        val spelling = composing.map { it.id }
        val w = selectedWord()
        val copy = if (w == null) Word("", selectedBanners(), listOf(spelling)) else null
        val h = maxOf(kbPanel.height, (resources.displayMetrics.heightPixels * 0.6f).toInt())
        editorHolder.removeAllViews()
        val insert = { banners: List<Banner> ->
            closeEditor()
            composing.clear()
            msg.insert(banners.map { Token.B(it) })
            refresh()
        }
        editorHolder.addView(WordEditorView(context, l, w, copy, onInsert = insert) { changed ->
            closeEditor()
            if (changed) { composing.clear(); reloadLanguage() } else refresh()
        }, FrameLayout.LayoutParams(MATCH, h))
        kbPanel.visibility = View.GONE
        editorHolder.visibility = View.VISIBLE
    }

    val editorOpen get() = editorHolder.visibility == View.VISIBLE

    fun closeEditor() {
        selectionMenu?.dismiss()
        editorHolder.removeAllViews()
        editorHolder.visibility = View.GONE
        kbPanel.visibility = View.VISIBLE
    }

    private var selectionMenu: PopupWindow? = null

    private fun showSelectionMenu(spot: RectF? = null) {
        selectionMenu?.dismiss()
        val r = spot ?: messageView.selectionBounds() ?: return
        val sel = spot == null
        val row = LinearLayout(context).apply { setPadding(dpi(4), dpi(4), dpi(4), dpi(4)) }
        val popup = PopupWindow(row, WRAP, WRAP, false).apply {
            setBackgroundDrawable(GradientDrawable().apply { setColor(Ui.KEY); cornerRadius = dp(8f) })
            elevation = dp(8f)
            isOutsideTouchable = true
            setOnDismissListener { if (selectionMenu == this) selectionMenu = null }
        }
        fun item(text: String, action: () -> Unit) = row.addView(TextView(context).apply {
            this.text = text
            textSize = 14f
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            minWidth = dpi(44)
            setPadding(dpi(12), 0, dpi(12), 0)
            background = Ui.bg(Ui.KEY, dp(8f))
            setOnClickListener { popup.dismiss(); action() }
        }, lp(WRAP, dpi(44)))
        if (sel) {
            item("Cut") { copySelection(); msg.deleteSelection(); refresh() }
            item("Copy") { copySelection(); msg.setCursor(msg.selEnd); refresh() }
        }
        item("Paste") { paste() }
        if (composing.isNotEmpty() || msg.selStart > 0 || msg.selEnd < msg.tokens.size) item("Select all") {
            commitComposing()
            msg.select(0, msg.tokens.size); refresh()
            messageView.post { showSelectionMenu() }
        }
        if (sel && selectedBanners().isNotEmpty()) item("Edit") { onEdit() }
        row.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val x = (r.centerX() - row.measuredWidth / 2f).toInt().coerceIn(0, maxOf(0, messageView.width - row.measuredWidth))
        val y = (r.top - dp(8f) - row.measuredHeight - messageView.height).toInt()
        selectionMenu = popup
        popup.showAsDropDown(messageView, x, y, Gravity.TOP or Gravity.START)
    }

    private fun copySelection() {
        val w = BannerWriter.Writing(messageView.rtl, msg.lines(msg.selStart, msg.selEnd))
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Banners", BannerWriter.imageLink(w)))
    }

    private fun paste() {
        val texts = context.clipboardTexts()
        val w = texts.firstNotNullOfOrNull { BannerWriter.findAny(it) }
        if (w == null) { context.toast(if (texts.isEmpty()) "Clipboard is empty" else "No banners in the clipboard"); return }
        commitComposing()
        msg.insert(Message.tokensOf(w.lines))
        refresh()
    }
}
