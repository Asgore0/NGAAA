package cn.ngapure.app;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;

/** Shared native glass prompts. Caller-owned submit listeners retain their own dismissal policy. */
public final class NativeGlassDialog extends Dialog {
    public static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE;
    public static final int BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE;
    public static final int BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;
    private final Activity activity;
    private final GlassPanel material;
    private final LinearLayout header, body, footer,shell;
    private final ScrollView outerScroll;
    private final TextView heading;
    private final IconView closeButton;
    private final HashMap<Integer, Button> buttons = new HashMap<>();
    private final boolean dark, motion;
    private final AppPalette palette;
    private final int ink, muted, accent, soft, border;
    private final ViewTreeObserver.OnGlobalLayoutListener geometry = this::fitWindow;
    private DialogInterface.OnDismissListener clientDismiss;
    private boolean released, autoClosing, cancelAllowed = true, observed;
    private int fittedWidth, fittedHeight;
    private final Application.ActivityLifecycleCallbacks owner = new Application.ActivityLifecycleCallbacks() {
        @Override public void onActivityCreated(Activity a, Bundle state) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityPaused(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle state) { }
        @Override public void onActivityDestroyed(Activity a) { if (a == activity) dismiss(); }
    };

    private NativeGlassDialog(Builder builder) {
        super(builder.activity); activity = builder.activity; dark = builder.dark; motion = builder.motion;
        palette = AppPalette.of(dark);
        ink = palette.ink; muted = palette.muted; accent = palette.accent;
        soft = palette.soft; border = palette.border;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        material = new GlassPanel(activity); material.setNightMode(dark);
        material.setSurfaceColor(palette.surface); material.setCornerRadiusDp(30);
        material.setTintOpacity(dark ? .94f : .91f); material.setLensingEnabled(false);
        material.setMotionEnabled(motion); material.setBlurSource(builder.backdrop); material.setGlassEnabled(builder.glass);
        shell = new NativeDialogGeometry.SheetShell(activity);
        header = column(); header.setPadding(dp(20), dp(12), dp(20), dp(14));
        View handle = new View(activity); handle.setBackground(shape(palette.handle, 2, 0));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(34), dp(4)); hp.gravity = Gravity.CENTER_HORIZONTAL; hp.bottomMargin = dp(12); header.addView(handle, hp);
        LinearLayout titleRow = row(); heading = text(builder.title == null ? "选择操作" : builder.title, 21, ink);
        heading.setTypeface(null, Typeface.BOLD); if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        closeButton = new IconView(activity, "close"); closeButton.setColor(muted); closeButton.setContentDescription("关闭弹窗");
        closeButton.setBackground(ripple(soft, 24)); closeButton.setOnClickListener(v -> { if (cancelAllowed) cancelAnimated(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(48), dp(48)); cp.leftMargin = dp(10); titleRow.addView(closeButton, cp);
        header.addView(titleRow); shell.addView(header, new LinearLayout.LayoutParams(-1, -2));
        body = column(); body.setPadding(dp(builder.contentInset), 0, dp(builder.contentInset), dp(4));
        if (builder.progress) {
            ProgressBar progress = new ProgressBar(activity); progress.setIndeterminateTintList(ColorStateList.valueOf(accent));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(36), dp(36)); pp.gravity = Gravity.CENTER_HORIZONTAL; pp.topMargin = dp(10); pp.bottomMargin = dp(16); body.addView(progress, pp);
        }
        if (builder.message != null && builder.message.length() > 0) {
            TextView message = text(builder.message, 15, muted); message.setLineSpacing(dp(4), 1f); message.setTextIsSelectable(true);
            if (builder.contentInset == 0) message.setPadding(dp(20), 0, dp(20), 0);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2); mp.bottomMargin = dp(builder.custom != null || builder.items != null ? 16 : 12); body.addView(message, mp);
        }
        if (builder.items != null) {
            for (int i = 0; i < builder.items.length; i++) {
                final int choice = i; LinearLayout item = row(); item.setPadding(dp(14), dp(4), dp(8), dp(4));
                item.setMinimumHeight(dp(54)); item.setBackground(ripple(soft, 17)); item.setContentDescription(builder.items[i]);
                TextView label = text(builder.items[i], 15, ink); label.setPadding(0, dp(10), dp(8), dp(10)); item.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                if (builder.singleChoice) {
                    RadioButton radio = new RadioButton(activity); radio.setChecked(i == builder.checked);
                    radio.setButtonTintList(ColorStateList.valueOf(accent)); radio.setClickable(false); radio.setFocusable(false); radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(radio, new LinearLayout.LayoutParams(dp(40), dp(48))); item.setSelected(i == builder.checked);
                    item.setOnClickListener(v -> {
                        if (autoClosing || released) return;
                        for (int n = 0; n < body.getChildCount(); n++) {
                            View candidate = body.getChildAt(n); if (!(candidate instanceof LinearLayout)) continue;
                            LinearLayout line = (LinearLayout) candidate;
                            for (int j = 0; j < line.getChildCount(); j++) if (line.getChildAt(j) instanceof RadioButton) ((RadioButton) line.getChildAt(j)).setChecked(line == item);
                            line.setSelected(line == item);
                        }
                        if (builder.itemAction != null) builder.itemAction.onClick(this, choice);
                    });
                } else {
                    IconView arrow = new IconView(activity, "arrowRight"); arrow.setColor(muted); arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(arrow, new LinearLayout.LayoutParams(dp(24), dp(24)));
                    item.setOnClickListener(v -> autoAction(builder.itemAction, choice));
                }
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, -2); if (i > 0) ip.topMargin = dp(8); body.addView(item, ip);
            }
        }
        if (builder.custom != null) {
            if (builder.custom.getParent() != null) throw new IllegalArgumentException("弹窗内容已被使用");
            styleInputs(builder.custom);
            body.addView(builder.custom, new LinearLayout.LayoutParams(-1, -2));
        }
        ScrollView scroll = new ScrollView(activity); scroll.setFillViewport(false); scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        footer = column(); footer.setPadding(dp(20), dp(12), dp(20), dp(20));
        LinearLayout actions = actionRow(activity);
        if (builder.negative != null) addAction(actions, builder.negative, BUTTON_NEGATIVE, builder.negativeAction, false);
        if (builder.neutral != null) addAction(actions, builder.neutral, BUTTON_NEUTRAL, builder.neutralAction, false);
        if (builder.positive != null) addAction(actions, builder.positive, BUTTON_POSITIVE, builder.positiveAction, true);
        if (actions.getChildCount() > 0) footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        if (buttons.isEmpty()) footer.setPadding(dp(20), 0, dp(20), dp(16));
        shell.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        outerScroll=new ScrollView(activity);outerScroll.setFillViewport(false);outerScroll.setVerticalScrollBarEnabled(false);outerScroll.addView(shell,new ScrollView.LayoutParams(-1,-2));
        material.addView(outerScroll, new ViewGroup.LayoutParams(-1, -1));
        setContentView(material); clientDismiss = builder.dismissAction;
        super.setOnDismissListener(ignored -> release());
        if (builder.showAction != null) super.setOnShowListener(builder.showAction);
        setCancelable(builder.cancelable); setCanceledOnTouchOutside(builder.cancelable);
    }

    public Button getButton(int which) { return buttons.get(which); }
    /** A visible exit animation has already accepted its action and must reject late updates. */
    public boolean isClosing() { return autoClosing || released; }
    @Override public void setTitle(CharSequence title) { super.setTitle(title); if (heading != null) heading.setText(title); }
    @Override public void setTitle(int title) { setTitle(activity.getText(title)); }
    @Override public void setOnDismissListener(DialogInterface.OnDismissListener listener) { clientDismiss = listener; }
    @Override public void setCancelable(boolean value) { super.setCancelable(value); cancelAllowed = value; if (closeButton != null) { closeButton.setEnabled(value); closeButton.setAlpha(value ? 1f : .25f); } }
    @SuppressWarnings("deprecation") // Floating dialog IME resize also supports API26; visible-frame sizing caps the sheet.
    @Override public void show() {
        if (released || activity.isFinishing() || activity.isDestroyed() || isShowing()) return;
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent); window.setGravity(Gravity.BOTTOM); window.setWindowAnimations(0);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            WindowManager.LayoutParams params = window.getAttributes(); params.dimAmount = dark ? .24f : .56f; params.y = dp(12); window.setAttributes(params);
        }
        super.show(); header.setFocusableInTouchMode(true); header.requestFocus(); fitWindow();
        if (window != null) window.getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(geometry);
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(geometry);
        activity.getApplication().registerActivityLifecycleCallbacks(owner); observed = true;
        material.post(() -> { if (!released) material.refreshBackdrop(); });
        if (motion && Motion.enabled(activity)) {
            material.setTranslationY(dp(28)); material.setAlpha(0);
            material.animate().translationY(0).alpha(1).setDuration(260).setInterpolator(new PathInterpolator(.18f, .8f, .2f, 1f)).start();
        }
    }
    @Override public void dismiss() {
        if (released) return;
        material.animate().cancel(); hideKeyboard();
        try { super.dismiss(); } finally { release(); }
    }
    private void release() {
        if (released) return; released = true; autoClosing = true; material.animate().cancel();
        Window window = getWindow(); if (window != null && window.getDecorView().getViewTreeObserver().isAlive()) window.getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(geometry);
        ViewTreeObserver ownerObserver=activity.getWindow().getDecorView().getViewTreeObserver();if(ownerObserver.isAlive())ownerObserver.removeOnGlobalLayoutListener(geometry);
        if (observed) { activity.getApplication().unregisterActivityLifecycleCallbacks(owner); observed = false; }
        material.setBlurSource(null); material.clearBackdrop();
        DialogInterface.OnDismissListener listener = clientDismiss; clientDismiss = null; if (listener != null) listener.onDismiss(this);
    }
    private void fitWindow() {
        Window window = getWindow(); if (released || window == null || !isShowing()) return;
        Rect visible = NativeDialogGeometry.available(activity,window); if (visible.width() <= 0 || visible.height() <= 0) return;
        Rect provisional=NativeDialogGeometry.sheet(visible,dp(480),Integer.MAX_VALUE,dp(12));int width=provisional.width();
        int exact = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), loose = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        header.measure(exact, loose); body.measure(exact, loose); footer.measure(exact, loose);
        int wanted = header.getMeasuredHeight() + body.getMeasuredHeight() + footer.getMeasuredHeight();
        Rect target=NativeDialogGeometry.sheet(visible,dp(480),wanted,dp(12));int height=target.height();
        boolean compact=header.getMeasuredHeight()+footer.getMeasuredHeight()+dp(80)>height;
        ViewGroup.LayoutParams shellParams=shell.getLayoutParams();int shellHeight=compact?ViewGroup.LayoutParams.WRAP_CONTENT:height;
        if(shellParams.height!=shellHeight){shellParams.height=shellHeight;shell.setLayoutParams(shellParams);}
        fittedWidth=width;fittedHeight=height;NativeDialogGeometry.place(activity,window,target);
    }
    private void autoAction(DialogInterface.OnClickListener listener, int which) {
        if (released || autoClosing) return; autoClosing = true; hideKeyboard(); material.animate().cancel();
        Runnable finish = () -> { if (released) return; dismiss(); if (listener != null && !activity.isFinishing() && !activity.isDestroyed()) listener.onClick(this, which); };
        if (motion && Motion.enabled(activity)) material.animate().translationY(dp(20)).alpha(0).setDuration(150).withEndAction(finish).start(); else finish.run();
    }
    private void cancelAnimated() {
        // Cancellation wins immediately over a pending action or a late verification result.
        if (cancelAllowed && !released) cancel();
    }
    private void hideKeyboard() { View focus = getCurrentFocus(); if (focus != null) { InputMethodManager keyboard = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE); if (keyboard != null) keyboard.hideSoftInputFromWindow(focus.getWindowToken(), 0); } }
    private void addAction(LinearLayout parent, CharSequence label, int which, DialogInterface.OnClickListener listener, boolean primary) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); if (parent.getChildCount() > 0) params.leftMargin = dp(8); parent.addView(action(label, which, listener, primary), params);
    }
    /** Keep complete action labels reachable when a narrow window or large text cannot fit a row. */
    static LinearLayout actionRow(Activity activity) { return new AdaptiveActionRow(activity); }
    private static final class AdaptiveActionRow extends LinearLayout {
        AdaptiveActionRow(Activity activity) { super(activity); setGravity(Gravity.CENTER_VERTICAL); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int gap=Math.round(8*getResources().getDisplayMetrics().density), needed=0, count=0;
            for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
                int width=child.getMinimumWidth();
                if(child instanceof TextView){TextView text=(TextView)child;width=Math.max(width,(int)Math.ceil(text.getPaint().measureText(text.getText().toString()))+text.getCompoundPaddingLeft()+text.getCompoundPaddingRight()+2);}
                needed=Math.max(needed,width);
                count++;
            }
            int available=View.MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight();
            boolean stacked=count>1&&needed*count+Math.max(0,count-1)*gap>available;
            setOrientation(stacked?VERTICAL:HORIZONTAL);int visible=0;
            for(int i=0;i<getChildCount();i++){
                View child=getChildAt(i);if(child.getVisibility()==GONE)continue;
                LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)child.getLayoutParams();
                p.width=stacked?ViewGroup.LayoutParams.MATCH_PARENT:0;p.height=ViewGroup.LayoutParams.WRAP_CONTENT;p.weight=stacked?0:1;
                p.leftMargin=!stacked&&visible>0?gap:0;p.topMargin=stacked&&visible>0?gap:0;p.rightMargin=0;p.bottomMargin=0;visible++;
            }
            super.onMeasure(widthSpec,heightSpec);
        }
    }
    /** Every replacement listener enters through the same live-window boundary. */
    private final class GuardedButton extends Button {
        GuardedButton() { super(NativeGlassDialog.this.activity); }
        @Override public void setOnClickListener(View.OnClickListener listener) {
            super.setOnClickListener(listener == null ? null : view -> {
                if (isEnabled() && NativeGlassDialog.this.isShowing() && !NativeGlassDialog.this.isClosing()
                        && !activity.isFinishing() && !activity.isDestroyed()) listener.onClick(view);
            });
        }
    }
    private Button action(CharSequence label, int which, DialogInterface.OnClickListener listener, boolean primary) {
        Button button = new GuardedButton(); button.setAllCaps(false); button.setText(label); button.setTextSize(15); button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[0]}, new int[]{muted, primary ? palette.onPrimary : ink}));
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48));
        button.setStateListAnimator(null); button.setElevation(0); button.setTranslationZ(0);
        button.setPadding(dp(12), dp(8), dp(12), dp(8));
        android.graphics.drawable.StateListDrawable fills = new android.graphics.drawable.StateListDrawable();
        fills.addState(new int[]{-android.R.attr.state_enabled}, shape(soft, 16, 0));
        fills.addState(new int[0], ripple(primary ? palette.primaryFill : soft, 16)); button.setBackground(fills);
        button.setOnClickListener(v -> autoAction(listener, which)); if (motion) Motion.press(button); buttons.put(which, button); return button;
    }
    private void styleInputs(View view) {
        if (view instanceof EditText) {
            EditText input = (EditText) view; input.setTextColor(ink); input.setHintTextColor(muted); input.setHighlightColor(palette.selectionFill); input.setMinHeight(dp(54)); input.setMinimumWidth(0); input.setPadding(dp(14), dp(12), dp(14), dp(12));
            android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
            states.addState(new int[]{android.R.attr.state_focused}, shape(soft, 16, palette.selectionStroke)); states.addState(new int[0], shape(soft, 16, border)); input.setBackground(states);
        } else if (view instanceof CheckBox) { ((CheckBox) view).setTextColor(ink); ((CheckBox) view).setButtonTintList(ColorStateList.valueOf(accent)); }
        if (view instanceof ViewGroup) { ViewGroup group = (ViewGroup) view; for (int i = 0; i < group.getChildCount(); i++) styleInputs(group.getChildAt(i)); }
    }
    private TextView text(CharSequence value, int sp, int color) { TextView v = new TextView(activity); v.setText(value); v.setTextSize(sp); v.setTextColor(color); return v; }
    private LinearLayout column() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout row() { LinearLayout v = new LinearLayout(activity); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    private GradientDrawable shape(int color, int radius, int stroke) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); if (stroke != 0) d.setStroke(dp(1), stroke); return d; }
    private RippleDrawable ripple(int color, int radius) { return new RippleDrawable(ColorStateList.valueOf(palette.ripple), shape(color, radius, 0), null); }
    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
    public static boolean darkMode(Activity activity, NgaStore store) { return "dark".equals(store.theme()) || "system".equals(store.theme()) && (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    public static final class Builder {
        private final Activity activity;
        private final boolean dark;
        private boolean glass, motion, cancelable = true, singleChoice, progress;
        private View backdrop, custom;
        private CharSequence title, message, positive, negative, neutral;
        private CharSequence[] items;
        private int checked = -1;
        private int contentInset = 20;
        private DialogInterface.OnClickListener positiveAction, negativeAction, neutralAction, itemAction;
        private DialogInterface.OnDismissListener dismissAction;
        private DialogInterface.OnShowListener showAction;
        public Builder(Activity activity, boolean dark) { this.activity = activity; this.dark = dark; NgaStore store = new NgaStore(activity); glass = store.glassEnabled(); motion = store.motionEnabled(); backdrop = activity.getWindow().getDecorView(); }
        public Builder setTitle(CharSequence value) { title = value; return this; }
        public Builder setTitle(int value) { return setTitle(activity.getText(value)); }
        public Builder setMessage(CharSequence value) { message = value; return this; }
        public Builder setMessage(int value) { return setMessage(activity.getText(value)); }
        public Builder setView(View value) { custom = value; return this; }
        public Builder setItems(CharSequence[] values, DialogInterface.OnClickListener listener) { items = values == null ? null : values.clone(); itemAction = listener; singleChoice = false; return this; }
        public Builder setItems(int values, DialogInterface.OnClickListener listener) { return setItems(activity.getResources().getTextArray(values), listener); }
        public Builder setSingleChoiceItems(CharSequence[] values, int selection, DialogInterface.OnClickListener listener) { setItems(values, listener); singleChoice = true; checked = selection; return this; }
        public Builder setSingleChoiceItems(int values, int selection, DialogInterface.OnClickListener listener) { return setSingleChoiceItems(activity.getResources().getTextArray(values), selection, listener); }
        public Builder setPositiveButton(CharSequence value, DialogInterface.OnClickListener listener) { positive = value; positiveAction = listener; return this; }
        public Builder setPositiveButton(int value, DialogInterface.OnClickListener listener) { return setPositiveButton(activity.getText(value), listener); }
        public Builder setNegativeButton(CharSequence value, DialogInterface.OnClickListener listener) { negative = value; negativeAction = listener; return this; }
        public Builder setNegativeButton(int value, DialogInterface.OnClickListener listener) { return setNegativeButton(activity.getText(value), listener); }
        public Builder setNeutralButton(CharSequence value, DialogInterface.OnClickListener listener) { neutral = value; neutralAction = listener; return this; }
        public Builder setNeutralButton(int value, DialogInterface.OnClickListener listener) { return setNeutralButton(activity.getText(value), listener); }
        public Builder setCancelable(boolean value) { cancelable = value; return this; }
        public Builder setProgress(boolean value) { progress = value; return this; }
        public Builder setContentPaddingDp(int value) { contentInset = Math.max(0, Math.min(32, value)); return this; }
        public Builder setBackdrop(View value) { backdrop = value; return this; }
        public Builder setGlassEnabled(boolean value) { glass = value; return this; }
        public Builder setMotionEnabled(boolean value) { motion = value; return this; }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) { dismissAction = listener; return this; }
        public Builder setOnShowListener(DialogInterface.OnShowListener listener) { showAction = listener; return this; }
        public NativeGlassDialog create() { return new NativeGlassDialog(this); }
        public NativeGlassDialog show() { NativeGlassDialog dialog = create(); dialog.show(); return dialog; }
    }
}
