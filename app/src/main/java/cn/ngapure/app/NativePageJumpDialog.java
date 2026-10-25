package cn.ngapure.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

/** Native, bounded page selection. The sheet never performs navigation itself. */
public final class NativePageJumpDialog implements AutoCloseable {
    public interface Listener {
        void onPageSelected(int page);
        void onDismiss(NativePageJumpDialog source);
    }
    private final Activity activity;
    private final Dialog dialog;
    private final PageJumpState state;
    private final Listener listener;
    private final GlassPanel material;
    private final LinearLayout panel,shell;
    private final ScrollView outerScroll;
    private final LinearLayout footer;
    private final EditText input;
    private final float numberTextSize;
    private final TextView helper, confirm, previous, next;
    private final SeekBar slider;
    private final TextView[] chips;
    private final int[] choices;
    private final AppPalette palette;
    private final int ink, muted, accent, soft, border, error;
    private final boolean motion, dark;
    private boolean closing, dismissed, submitted, syncing;
    private int windowWidth, windowHeight;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::fitWindow;

    public NativePageJumpDialog(Activity activity, View backdrop, boolean dark, boolean glass,
                                boolean motion, int currentPage, int totalPages, Listener listener) {
        if (listener == null) throw new IllegalArgumentException("缺少跳页回调");
        this.activity = activity; this.listener = listener; this.motion = motion; this.dark = dark;
        state = new PageJumpState(currentPage, totalPages);
        palette = AppPalette.of(dark);
        ink = palette.ink; muted = palette.muted; accent = palette.accent;
        soft = palette.soft; border = palette.border; error = palette.error;
        dialog = new Dialog(activity); dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        material = new GlassPanel(activity); material.setNightMode(dark);
        material.setSurfaceColor(palette.surface); material.setCornerRadiusDp(30);
        material.setTintOpacity(dark ? .94f : .91f); material.setMotionEnabled(motion);
        material.setLensingEnabled(false); material.setBlurSource(backdrop); material.setGlassEnabled(glass);
        panel = new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(12), dp(20), dp(4)); panel.setFocusableInTouchMode(true);
        View handle = new View(activity); handle.setBackground(shape(palette.handle, 2, 0));
        LinearLayout.LayoutParams hp = margins(dp(34), dp(4), 0, 0, 0, 12); hp.gravity = Gravity.CENTER_HORIZONTAL; panel.addView(handle, hp);
        LinearLayout header = row();
        LinearLayout heading = column(); TextView title = text("跳转页码", 21, ink); title.setTypeface(null, Typeface.BOLD); heading.addView(title);
        TextView subtitle = text(state.totalPages > 0 ? "当前第 " + state.currentPage + " 页 · 共 " + state.totalPages + " 页"
                : "当前第 " + state.currentPage + " 页", 13, muted);
        subtitle.setPadding(0, dp(6), 0, 0); heading.addView(subtitle); header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        IconView close = new IconView(activity, "close"); close.setColor(muted); close.setContentDescription("关闭跳页面板");
        close.setBackground(ripple(soft, 24)); close.setOnClickListener(v -> dismissAnimated(null));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); panel.addView(header);

        LinearLayout hero = column(); hero.setPadding(dp(12), dp(8), dp(12), dp(12));
        hero.setBackground(shape(soft, 20, 0));
        PageStepper stepper = new PageStepper();
        previous = stepButton("−", "减少一页", () -> step(-1)); stepper.addView(previous, new ViewGroup.LayoutParams(dp(48), dp(48)));
        input = new EditText(activity); input.setInputType(InputType.TYPE_CLASS_NUMBER); input.setSingleLine();
        input.setTextSize(40); numberTextSize = input.getTextSize(); input.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        input.setTextColor(ink); input.setHintTextColor(muted); input.setHighlightColor(palette.selectionFill); input.setGravity(Gravity.CENTER);
        input.setPadding(dp(2), dp(4), dp(2), dp(4)); input.setBackgroundColor(Color.TRANSPARENT);
        input.setSelectAllOnFocus(true); input.setImeOptions(EditorInfo.IME_ACTION_GO); input.setContentDescription("目标页码");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)}); input.setSaveEnabled(false); input.setMinimumWidth(0);
        input.setMinHeight(dp(72)); input.setText(String.valueOf(state.currentPage));
        stepper.addView(input, new ViewGroup.LayoutParams(-1, -2));
        next = stepButton("+", "增加一页", () -> step(1)); stepper.addView(next, new ViewGroup.LayoutParams(dp(48), dp(48)));
        hero.addView(stepper);
        helper = text("", 12, muted); helper.setMinHeight(dp(20)); helper.setGravity(Gravity.CENTER);
        helper.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        hero.addView(helper, margins(-1, -2, 0, 4, 0, 0));
        panel.addView(hero, margins(-1, -2, 0, 16, 0, 12));

        QuickPages quick = new QuickPages(); choices = state.nearbyPages(); chips = new TextView[choices.length];
        for (int i = 0; i < choices.length; i++) {
            int target = choices[i]; TextView chip = text(target == 1 ? "首页" : target == state.totalPages && state.totalPages > 1 ? "末页" : String.valueOf(target), 14, ink);
            chips[i] = chip; chip.setGravity(Gravity.CENTER); chip.setTypeface(null, Typeface.BOLD);
            chip.setContentDescription("选择第 " + target + " 页"); chip.setOnClickListener(v -> choose(target));
            chip.setMinHeight(dp(48)); quick.addView(chip, new ViewGroup.LayoutParams(-1, -2));
        }
        quick.setVisibility(state.totalPages == 1 ? View.GONE : View.VISIBLE);
        panel.addView(quick, margins(-1, -2, 0, 0, 0, 0));
        slider = new SeekBar(activity); slider.setContentDescription("拖动选择页码");
        slider.setMax(Math.max(0, state.maximumPage - 1)); slider.setProgress(state.currentPage - 1);
        slider.setProgressTintList(ColorStateList.valueOf(accent)); slider.setThumbTintList(ColorStateList.valueOf(accent));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(border)); slider.setPadding(dp(8), 0, dp(8), 0);
        if (state.totalPages > 1) {
            panel.addView(slider, margins(-1, dp(48), 0, 8, 0, 0));
            LinearLayout limits = row(); TextView first = text("首页 · 1", 12, muted), last = text("末页 · " + state.totalPages, 12, muted);
            first.setGravity(Gravity.CENTER_VERTICAL); last.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
            first.setContentDescription("选择首页"); last.setContentDescription("选择末页");
            first.setOnClickListener(v -> choose(1)); last.setOnClickListener(v -> choose(state.totalPages));
            limits.setPadding(dp(8), 0, dp(8), 0);
            first.setMinHeight(dp(48)); last.setMinHeight(dp(48));
            limits.addView(first, new LinearLayout.LayoutParams(0, -2, 1)); limits.addView(last, new LinearLayout.LayoutParams(0, -2, 1)); panel.addView(limits);
        }
        footer = column(); footer.setPadding(dp(20), dp(12), dp(20), dp(20));
        confirm = text("", 15, ink); confirm.setGravity(Gravity.CENTER); confirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        confirm.setMinHeight(dp(48)); confirm.setPadding(dp(12), dp(8), dp(12), dp(8));
        confirm.setOnClickListener(v -> submit()); if (motion) Motion.press(confirm); footer.addView(confirm, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(panel, new ScrollView.LayoutParams(-1, -2));
        shell = new NativeDialogGeometry.SheetShell(activity); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        shell.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        outerScroll=new ScrollView(activity);outerScroll.setFillViewport(false);outerScroll.setVerticalScrollBarEnabled(false);outerScroll.addView(shell,new ScrollView.LayoutParams(-1,-2));
        material.addView(outerScroll, new ViewGroup.LayoutParams(-1, -1));
        dialog.setContentView(material); dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> release());
        dialog.setOnKeyListener((ignored, key, event) -> {
            if (key == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP && !input.hasFocus()) { dismissAnimated(null); return true; }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { update(); stepper.requestLayout(); }
            @Override public void afterTextChanged(Editable text) { }
        });
        input.setOnEditorActionListener((v, action, event) -> { if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) { submit(); return true; } return false; });
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { if (fromUser && !syncing) choose(progress + 1); }
            @Override public void onStartTrackingTouch(SeekBar bar) { hideKeyboard(); panel.requestFocus(); }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });
        update();
    }

    @SuppressWarnings("deprecation") // API26 floating-window IME resize; geometry also clamps to the actual visible frame.
    public void show() {
        if (dismissed || dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent); window.setGravity(Gravity.BOTTOM);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setWindowAnimations(0);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            WindowManager.LayoutParams params = window.getAttributes(); params.dimAmount = dark ? .24f : .56f; params.y = dp(12); window.setAttributes(params);
        }
        dialog.show(); panel.requestFocus(); fitWindow();
        if (window != null) window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        material.post(() -> { if (!dismissed) material.refreshBackdrop(); });
        if (motion && Motion.enabled(activity)) {
            material.setTranslationY(dp(28)); material.setAlpha(0f);
            material.animate().translationY(0).alpha(1).setDuration(260).setInterpolator(new PathInterpolator(.18f, .8f, .2f, 1f)).start();
        }
    }
    /** Unsubmitted input only. Closing or submitted sheets can never be restored or replayed. */
    public String[] draftState(){return closing||dismissed||submitted||!dialog.isShowing()?null:new String[]{input.getText().toString(),Integer.toString(input.getSelectionStart()),Integer.toString(input.getSelectionEnd())};}
    public int knownTotalPages(){return state.totalPages;}
    public void restoreDraft(String text,int start,int end){
        if(closing||dismissed||submitted||text==null||text.length()>8||!text.matches("[0-9]*")||start<0||end<0||start>text.length()||end>text.length())return;
        input.setText(text);input.setSelection(start,end);
    }
    private void fitWindow() {
        Window window = dialog.getWindow(); if (dismissed || window == null || !dialog.isShowing()) return;
        Rect visible = NativeDialogGeometry.available(activity,window);if (visible.width() <= 0 || visible.height() <= 0) return;
        Rect provisional=NativeDialogGeometry.sheet(visible,dp(480),Integer.MAX_VALUE,dp(12));int width=provisional.width();
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        footer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        Rect target=NativeDialogGeometry.sheet(visible,dp(480),panel.getMeasuredHeight()+footer.getMeasuredHeight(),dp(12));int height=target.height();
        boolean compact=footer.getMeasuredHeight()+dp(120)>height;ViewGroup.LayoutParams shellParams=shell.getLayoutParams();int shellHeight=compact?ViewGroup.LayoutParams.WRAP_CONTENT:height;
        if(shellParams.height!=shellHeight){shellParams.height=shellHeight;shell.setLayoutParams(shellParams);}
        windowWidth=width;windowHeight=height;NativeDialogGeometry.place(activity,window,target);
    }
    private void choose(int page) { if (!closing) { input.setText(String.valueOf(page)); input.setSelection(input.length()); } }
    private void step(int delta) { int value = state.parse(input.getText().toString()); if (value == 0) value = state.currentPage; choose(Math.max(1, Math.min(state.maximumPage, value + delta))); }
    private void update() {
        int page = state.parse(input.getText().toString()); boolean valid = page > 0;
        helper.setText(valid ? state.totalPages == 1 ? "这一页就是全部内容" : "点按数字输入页码"
                : input.length() == 0 ? "请输入想前往的页码" : state.totalPages > 0 ? "请输入 1–" + state.totalPages + " 之间的页码" : "请输入有效页码");
        helper.setTextColor(valid || input.length() == 0 ? muted : error);
        confirm.setText(valid ? page == state.currentPage ? "留在当前页" : "前往第 " + page + " 页  →" : "输入页码后跳转");
        boolean leavingCurrentPage = valid && page != state.currentPage;
        confirm.setTextColor(leavingCurrentPage ? palette.onPrimary : ink);
        confirm.setBackground(ripple(leavingCurrentPage ? palette.primaryFill : soft, 16));
        confirm.setEnabled(valid && !closing); confirm.setAlpha(valid ? 1f : .45f);
        previous.setEnabled(!closing && (!valid || page > 1)); previous.setAlpha(valid && page == 1 ? .35f : 1f);
        next.setEnabled(!closing && (!valid || page < state.maximumPage)); next.setAlpha(valid && page == state.maximumPage ? .35f : 1f);
        syncing = true; if (valid) slider.setProgress(page - 1); syncing = false;
        for (int i = 0; i < chips.length; i++) {
            boolean selected = valid && choices[i] == page; chips[i].setTextColor(selected ? accent : ink);
            chips[i].setBackground(ripple(selected ? palette.selectionFill : soft, 15, selected ? palette.selectionStroke : 0)); chips[i].setSelected(selected);
        }
    }
    private void submit() {
        if (closing || submitted || dismissed) return; int page = state.parse(input.getText().toString());
        if (page == 0) { update(); return; }
        submitted = true; dismissAnimated(() -> listener.onPageSelected(page));
    }
    private void dismissAnimated(Runnable after) {
        if (closing || dismissed) return; closing = true; hideKeyboard();
        material.animate().cancel();
        Runnable finish = () -> { if (dismissed) return; dialog.dismiss(); if (after != null && !activity.isFinishing() && !activity.isDestroyed()) after.run(); };
        if (motion && Motion.enabled(activity)) material.animate().translationY(dp(20)).alpha(0).setDuration(150).withEndAction(finish).start();
        else finish.run();
    }
    private void hideKeyboard() { InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE); if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0); }
    private void release() {
        if (dismissed) return; dismissed = true; closing = true; material.animate().cancel();
        Window window = dialog.getWindow(); if (window != null && window.getDecorView().getViewTreeObserver().isAlive()) window.getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        ViewTreeObserver ownerObserver=activity.getWindow().getDecorView().getViewTreeObserver();if(ownerObserver.isAlive())ownerObserver.removeOnGlobalLayoutListener(layoutListener);
        material.setBlurSource(null); material.clearBackdrop(); listener.onDismiss(this);
    }
    @Override public void close() { if (dismissed) return; closing = true; material.animate().cancel(); hideKeyboard(); if (dialog.isShowing()) dialog.dismiss(); else release(); }
    /** Give longer values the full pane before reducing only the oversized number. */
    private final class PageStepper extends ViewGroup {
        private boolean stacked;
        PageStepper() { super(activity); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), control = dp(48);
            TextPaint metrics = new TextPaint(input.getPaint()); metrics.setTextSize(numberTextSize);
            String value = input.getText().toString(); float wanted = metrics.measureText(value);
            int padding = input.getCompoundPaddingLeft() + input.getCompoundPaddingRight();
            stacked = wanted + padding > width - control * 2;
            int inputWidth = Math.max(0, stacked ? width : width - control * 2);
            float available = Math.max(1, inputWidth - padding - 1), textSize = numberTextSize;
            if (wanted > available) {
                // Hinted glyph advances are quantized, so a proportional size can still overflow.
                float low = 0, high = numberTextSize;
                for (int i = 0; i < 16; i++) {
                    float middle = (low + high) / 2; metrics.setTextSize(middle);
                    if (metrics.measureText(value) <= available) low = middle; else high = middle;
                }
                textSize = low;
            }
            if (input.getTextSize() != textSize) input.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);
            input.measure(MeasureSpec.makeMeasureSpec(inputWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            previous.measure(MeasureSpec.makeMeasureSpec(control, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(control, MeasureSpec.EXACTLY));
            next.measure(MeasureSpec.makeMeasureSpec(control, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(control, MeasureSpec.EXACTLY));
            int height = stacked ? input.getMeasuredHeight() + dp(6) + control : Math.max(input.getMeasuredHeight(), control);
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left, control = dp(48), height = bottom - top;
            if (stacked) {
                input.layout(0, 0, width, input.getMeasuredHeight());
                previous.layout(0, height - control, control, height);
                next.layout(width - control, height - control, width, height);
            } else {
                input.layout(control, (height - input.getMeasuredHeight()) / 2, width - control, (height + input.getMeasuredHeight()) / 2);
                previous.layout(0, (height - control) / 2, control, (height + control) / 2);
                next.layout(width - control, (height - control) / 2, width, (height + control) / 2);
            }
        }
    }
    /** Keep ordinary sheets in one row; narrow panes wrap whole labels before they clip. */
    private final class QuickPages extends ViewGroup {
        private int columns = 1;
        private int[] rowHeights = new int[0];
        QuickPages() { super(activity); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), count = getChildCount(), gap = dp(6), wanted = 1;
            for (int i = 0; i < count; i++) {
                TextView chip = (TextView) getChildAt(i);
                wanted = Math.max(wanted, (int) Math.ceil(chip.getPaint().measureText(chip.getText().toString())) + dp(8));
            }
            columns = Math.max(1, Math.min(count, (width + gap) / (wanted + gap)));
            rowHeights = new int[(count + columns - 1) / columns];
            for (int i = 0; i < count; i++) {
                int left = (i % columns) * (width + gap) / columns;
                int right = (i % columns + 1) * (width + gap) / columns - gap;
                View chip = getChildAt(i);
                chip.measure(MeasureSpec.makeMeasureSpec(Math.max(0, right - left), MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                rowHeights[i / columns] = Math.max(rowHeights[i / columns], chip.getMeasuredHeight());
            }
            int height = Math.max(0, rowHeights.length - 1) * gap;
            for (int rowHeight : rowHeights) height += rowHeight;
            setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(height, heightSpec));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left, gap = dp(6), y = 0;
            for (int i = 0; i < getChildCount(); i++) {
                int column = i % columns;
                if (i > 0 && column == 0) y += rowHeights[i / columns - 1] + gap;
                int x = column * (width + gap) / columns;
                getChildAt(i).layout(x, y, (column + 1) * (width + gap) / columns - gap, y + rowHeights[i / columns]);
            }
        }
    }
    private TextView stepButton(String label, String description, Runnable action) { TextView v = text(label, 24, accent); v.setGravity(Gravity.CENTER); v.setContentDescription(description); v.setBackground(ripple(soft, 17)); v.setOnClickListener(ignored -> action.run()); return v; }
    private TextView text(String value, int sp, int color) { TextView v = new TextView(activity); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setIncludeFontPadding(true); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private GradientDrawable shape(int fill, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d; }
    private RippleDrawable ripple(int fill, int radius) { return ripple(fill, radius, 0); }
    private RippleDrawable ripple(int fill, int radius, int stroke) { return new RippleDrawable(ColorStateList.valueOf(palette.ripple), shape(fill, radius, stroke), null); }
    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return p; }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}
