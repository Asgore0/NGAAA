package cn.ngapure.app;

import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.view.ViewGroup;
import android.text.InputType;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Html;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.method.LinkMovementMethod;
import android.text.style.ImageSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allowlisted BBCode as native text, lazy spoilers and lifecycle-aware inline media. */
public final class NativeRichText extends LinearLayout {
    private static final java.util.WeakHashMap<ScrollView,ScrollTouch> SCROLL_TOUCHES=new java.util.WeakHashMap<>();
    private static final class ScrollTouch { long epoch; boolean pressed; }
    /** Observe dispatch before any child consumes a gesture; values never retain the weak scroll key. */
    public static void onScrollTouch(ScrollView scroll,android.view.MotionEvent event) {
        if(scroll==null||event==null)return;
        ScrollTouch state=SCROLL_TOUCHES.get(scroll);
        if(state==null){state=new ScrollTouch();SCROLL_TOUCHES.put(scroll,state);}
        state.epoch++;
        int action=event.getActionMasked();
        if(action==android.view.MotionEvent.ACTION_DOWN)state.pressed=true;
        else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL)state.pressed=false;
    }
    private static int topInScroll(View child,ScrollView scroll) {
        int top=child.getTop();android.view.ViewParent parent=child.getParent();
        while(parent instanceof View&&parent!=scroll){View view=(View)parent;top+=view.getTop()-view.getScrollY();parent=view.getParent();}
        return top;
    }
    private static boolean belongsToScroll(View child,ScrollView scroll) {
        android.view.ViewParent parent=child.getParent();
        while(parent instanceof View){if(parent==scroll)return true;parent=parent.getParent();}
        return false;
    }
    /** Choose text already being read, including another post's independent rich-text owner. */
    private static TextView visibleReadingText(ScrollView scroll) {
        ArrayList<View> pending=new ArrayList<>();pending.add(scroll);Rect visible=new Rect();
        while(!pending.isEmpty()){
            View child=pending.remove(pending.size()-1);
            if(!child.isShown()||!child.getLocalVisibleRect(visible))continue;
            if(child instanceof NativeRichText.RichTextView){
                TextView text=(TextView)child;android.text.Layout layout=text.getLayout();
                if(layout!=null&&text.length()>0){int vertical=scroll.getScrollY()+scroll.getPaddingTop()-topInScroll(text,scroll)-text.getTotalPaddingTop()+text.getScrollY();if(vertical<layout.getHeight())return text;}
            }
            if(child instanceof ViewGroup){ViewGroup group=(ViewGroup)child;for(int i=group.getChildCount()-1;i>=0;i--)pending.add(group.getChildAt(i));}
        }
        return null;
    }
    public interface Links { void open(String url); }
    private final NativeImages images;
    private final Links links;
    private final boolean dark;
    private final int font;
    private final List<InlineImage> inlineImages = new ArrayList<>();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::syncImageVisibility;
    private long visibilitySequence, activeVisibilityPass;

    public NativeRichText(Context context, NativeImages images, String content,
                          boolean dark, int font, Links links) { this(context, images, content, dark, font, links, "", "", ""); }
    public NativeRichText(Context context, NativeImages images, String content, boolean dark, int font,
                          Links links, String authorId, String topicId, String postId) {
        super(context);
        this.images=images; this.links=links; this.dark=dark;
        this.font=Math.max(12,Math.min(28,font));
        setOrientation(VERTICAL);
        String html=PostRenderer.html(content,dark,this.font,authorId,topicId,postId);
        int start=html.indexOf("<body>"),end=html.lastIndexOf("</body>");
        render(start>=0 && end>=start+6 ? html.substring(start+6,end) : html,this,0);
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    private void render(String html,LinearLayout parent,int depth) {
        if(depth>32)return;
        for(NativeContentParser.Block block:NativeContentParser.parse(html)) {
            if(block.kind==NativeContentParser.CODE)code(block.body,parent);
            else if(block.kind==NativeContentParser.COLLAPSE)collapse(block,parent,depth);
            else if(block.kind==NativeContentParser.QUOTE)quote(block,parent,depth);
            else if(block.kind==NativeContentParser.TABLE)table(block,parent,depth);
            else if(block.kind==NativeContentParser.MEDIA)media(block,parent);
            else if(block.kind==NativeContentParser.CRYPT)crypt(block,parent,depth);
            else text(block.body,parent);
        }
    }
    private GradientDrawable panelBackground() {
        GradientDrawable background=new GradientDrawable();
        background.setColor(AppPalette.of(dark).soft); background.setCornerRadius(dp(12));
        background.setStroke(dp(1),AppPalette.of(dark).line);
        return background;
    }
    private void quote(NativeContentParser.Block block,LinearLayout parent,int depth) {
        // Additional nested quotes keep their content inside the current quote surface
        // instead of consuming the cover display with another pair of side gutters.
        if(depth>=3) { render(block.body,parent,depth+1); return; }
        LinearLayout panel=new LinearLayout(getContext()) {
            private final Paint rail=new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                rail.setColor(AppPalette.of(dark).selectionStroke);
                canvas.drawRoundRect(dp(1),dp(11),dp(4),Math.max(dp(11),getHeight()-dp(11)),dp(2),dp(2),rail);
            }
        };
        panel.setOrientation(VERTICAL); panel.setWillNotDraw(false); panel.setBackground(panelBackground());
        panel.setPadding(dp(14),dp(8),dp(12),dp(8));
        render(block.body,panel,depth+1);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,dp(8),0,dp(10)); parent.addView(panel,params);
    }
    private void code(String value,LinearLayout parent) {
        LinearLayout panel=new LinearLayout(getContext()); panel.setOrientation(VERTICAL);
        panel.setBackground(panelBackground());
        TextView label=new TextView(getContext());
        label.setText("代码 · 左右滑动查看"); label.setTextSize(11);
        label.setTextColor(AppPalette.of(dark).muted); label.setIncludeFontPadding(false);
        label.setPadding(dp(12),dp(11),dp(12),dp(5));
        panel.addView(label,new LinearLayout.LayoutParams(-1,-2));
        HorizontalScrollView horizontal=new HorizontalScrollView(getContext());
        horizontal.setFillViewport(true); horizontal.setHorizontalScrollBarEnabled(true);
        horizontal.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        TextView view=new TextView(getContext());
        view.setText(value); // Plain text retains original whitespace and literal BBCode.
        view.setTypeface(Typeface.MONOSPACE); view.setTextSize(Math.max(12,font-1));
        view.setTextColor(AppPalette.of(dark).ink); view.setTextIsSelectable(true);
        view.setIncludeFontPadding(false); view.setHorizontallyScrolling(true);
        view.setLineSpacing(dp(3),1.05f); view.setPadding(dp(12),dp(4),dp(12),dp(12));
        horizontal.addView(view,new android.widget.FrameLayout.LayoutParams(-2,-2));
        panel.addView(horizontal,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,dp(8),0,dp(10)); parent.addView(panel,params);
    }
    private void collapse(NativeContentParser.Block block,LinearLayout parent,int depth) {
        LinearLayout panel=new LinearLayout(getContext()); panel.setOrientation(VERTICAL);
        panel.setPadding(dp(12),0,dp(12),0); panel.setBackground(panelBackground());
        TextView header=new TextView(getContext()); header.setTextSize(font);
        header.setTextColor(AppPalette.of(dark).accent); header.setPadding(0,dp(8),0,dp(8));
        header.setMinHeight(dp(48)); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setFocusable(true);
        LinearLayout inside=new LinearLayout(getContext()); inside.setOrientation(VERTICAL);
        inside.setPadding(0,0,0,dp(8)); inside.setVisibility(GONE);
        boolean[] expanded={false},rendered={false};
        String title=block.title.isEmpty()?"展开内容":block.title;
        Runnable updateHeader=()->{
            header.setText((expanded[0]?"▾  ":"▸  ")+title);
            header.setContentDescription(title+(expanded[0]?"，已展开，点按收起":"，已收起，点按展开"));
        };
        header.setOnClickListener(view->{
            boolean show=!expanded[0];
            if(show&&!rendered[0]) { rendered[0]=true; render(block.body,inside,depth+1); }
            expanded[0]=show; inside.setVisibility(show?VISIBLE:GONE); updateHeader.run();
            syncImageVisibility(); header.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        });
        header.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host,info);
                info.setClassName("android.widget.Button"); info.setCheckable(true); info.setChecked(expanded[0]);
                info.addAction(expanded[0]?AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE:
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
            @Override public boolean performAccessibilityAction(View host,int action,Bundle args) {
                if(action==AccessibilityNodeInfo.ACTION_EXPAND||action==AccessibilityNodeInfo.ACTION_COLLAPSE) {
                    boolean wanted=action==AccessibilityNodeInfo.ACTION_EXPAND;
                    if(expanded[0]!=wanted)header.performClick();
                    return true;
                }
                return super.performAccessibilityAction(host,action,args);
            }
        });
        updateHeader.run(); panel.addView(header,new LinearLayout.LayoutParams(-1,-2));
        panel.addView(inside,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.setMargins(0,dp(8),0,dp(8)); parent.addView(panel,params);
    }
    private void media(NativeContentParser.Block block, LinearLayout parent) {
        String source = NativeMediaPolicy.source(block.body); if (source == null) { text("媒体地址不可用", parent); return; }
        LinearLayout card = new LinearLayout(getContext()); card.setOrientation(VERTICAL); card.setPadding(dp(14), dp(14), dp(14), dp(14)); card.setBackground(panelBackground());
        boolean audio = block.title.equals("audio");
        TextView title = new TextView(getContext()); title.setText(audio ? "♫  音频" : "▷  视频"); title.setTextSize(font + 1); title.setTextColor(AppPalette.of(dark).ink); card.addView(title);
        TextView host = new TextView(getContext()); host.setText(NativeMediaPolicy.originLabel(source)); host.setTextSize(12); host.setTextColor(AppPalette.of(dark).muted); host.setPadding(0, dp(5), 0, dp(8)); card.addView(host);
        Button play = new Button(getContext()); play.setText(audio ? "播放音频" : "播放视频"); play.setAllCaps(false); play.setMinHeight(dp(48)); play.setContentDescription(audio ? "在原生播放器播放音频" : "在原生播放器播放视频");
        play.setOnClickListener(v -> { if (!NativeMediaPlayer.open(getContext(), source, audio)) host.setText("当前页面无法打开播放器"); }); card.addView(play, new LinearLayout.LayoutParams(-1, dp(50)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(10), 0, dp(10)); parent.addView(card, p);
    }
    private void crypt(NativeContentParser.Block block, LinearLayout parent, int depth) {
        LinearLayout panel = new LinearLayout(getContext()); panel.setOrientation(VERTICAL); panel.setPadding(dp(12), dp(10), dp(12), dp(10)); panel.setBackground(panelBackground());
        TextView title = new TextView(getContext()); title.setText("加密内容"); title.setTextSize(font); title.setTextColor(AppPalette.of(dark).ink); panel.addView(title);
        EditText password = new EditText(getContext()); password.setSingleLine(true); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setHint("输入作者设置的密码"); password.setTextColor(AppPalette.of(dark).ink); password.setHintTextColor(AppPalette.of(dark).muted); password.setMinHeight(dp(52)); password.setSaveEnabled(false); password.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS); panel.addView(password, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = new TextView(getContext()); hint.setText("仅在本机解密；此旧格式不校验密码，乱码时请重试。"); hint.setTextSize(12); hint.setTextColor(AppPalette.of(dark).muted); panel.addView(hint);
        LinearLayout decrypted = new LinearLayout(getContext()); decrypted.setOrientation(VERTICAL); panel.addView(decrypted, new LinearLayout.LayoutParams(-1, -2));
        Button unlock = new Button(getContext()); unlock.setText("解密"); unlock.setAllCaps(false); unlock.setMinHeight(dp(48));
        unlock.setOnClickListener(v -> { try { String plain = NativeEncryptedContent.decrypt(password.getText().toString(), block.body); password.getText().clear(); discardInlineImages(decrypted); decrypted.removeAllViews(); String html = PostRenderer.html(plain, dark, font); int start = html.indexOf("<body>"), end = html.lastIndexOf("</body>"); render(html.substring(start + 6, end), decrypted, depth + 1); hint.setText("已在本机解密；如出现乱码，请重新输入密码。"); } catch (IllegalArgumentException invalid) { password.setError(invalid.getMessage()); } });
        panel.addView(unlock, new LinearLayout.LayoutParams(-1, dp(50))); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(8)); parent.addView(panel, p);
    }
    /** A decrypted subtree is replaced in place; discard its old image registrations first. */
    private void discardInlineImages(ViewGroup subtree) {
        for(int i=inlineImages.size()-1;i>=0;i--) {
            InlineImage image=inlineImages.get(i);
            View current=image.owner;
            while(current!=null&&current!=subtree) {
                ViewParent parent=current.getParent(); current=parent instanceof View?(View)parent:null;
            }
            if(current==subtree) {
                image.cancelRequest(); image.stopAnimation(); image.actual=null;
                inlineImages.remove(i);
            }
        }
    }
    private void table(NativeContentParser.Block block, LinearLayout parent, int depth) {
        NativeTableModel model = NativeTableModel.parse(block.body);
        if (model == null) { text(block.body.replace("</td>", "　│　").replace("</tr>", "<br>"), parent); return; }
        LinearLayout panel = new LinearLayout(getContext()); panel.setOrientation(VERTICAL); panel.setBackground(panelBackground());
        TextView label = new TextView(getContext()); label.setText("表格 · 左右滑动查看"); label.setTextSize(11); label.setTextColor(AppPalette.of(dark).muted); label.setPadding(dp(12), dp(10), dp(12), dp(8)); panel.addView(label);
        TableGrid grid = new TableGrid(getContext(), model);
        HorizontalScrollView scroll = new HorizontalScrollView(getContext()) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                // The outer rich text still has its previous laid-out width during a fold/resize.
                grid.availableWidth=Math.max(0,MeasureSpec.getSize(widthSpec)-getPaddingLeft()-getPaddingRight());
                super.onMeasure(widthSpec,heightSpec);
            }
        }; scroll.setFillViewport(true); scroll.setHorizontalScrollBarEnabled(true); scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS); scroll.setContentDescription("表格，可左右滚动查看全部列");
        for (NativeTableModel.Cell cell : model.cells) { LinearLayout content = new LinearLayout(getContext()); content.setOrientation(VERTICAL); content.setPadding(dp(10), dp(8), dp(10), dp(8)); GradientDrawable background = new GradientDrawable(); background.setColor(cell.header ? AppPalette.of(dark).soft : AppPalette.of(dark).surface); background.setStroke(dp(1), AppPalette.of(dark).line); content.setBackground(background); render(block.prefix+cell.html+block.suffix, content, depth + 1); grid.addView(content); }
        scroll.addView(grid, new FrameLayout.LayoutParams(-2, -2)); panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2)); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(8), 0, dp(10)); parent.addView(panel, p);
    }
    private final class TableGrid extends ViewGroup {
        private final NativeTableModel model; private final int[] heights; private int columnWidth,availableWidth;
        TableGrid(Context context, NativeTableModel value) { super(context); model = value; heights = new int[value.rows]; }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int available = availableWidth; if (available <= 0) available = Math.max(dp(220), getResources().getDisplayMetrics().widthPixels - dp(64));
            columnWidth = Math.max(dp(112), available / Math.max(1, model.columns)); java.util.Arrays.fill(heights, dp(48));
            for (int i = 0; i < model.cells.size(); i++) { NativeTableModel.Cell cell = model.cells.get(i); View child = getChildAt(i); child.measure(MeasureSpec.makeMeasureSpec(columnWidth * cell.columnSpan, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); if (cell.rowSpan == 1) heights[cell.row] = Math.max(heights[cell.row], child.getMeasuredHeight()); }
            for (int i = 0; i < model.cells.size(); i++) { NativeTableModel.Cell cell = model.cells.get(i); int current = 0; for (int r = cell.row; r < cell.row + cell.rowSpan; r++) current += heights[r]; int missing = getChildAt(i).getMeasuredHeight() - current; if (missing > 0) heights[cell.row + cell.rowSpan - 1] += missing; }
            int total = 0; for (int value : heights) total += value; setMeasuredDimension(columnWidth * model.columns, total);
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { int[] tops = new int[heights.length + 1]; for (int i = 0; i < heights.length; i++) tops[i + 1] = tops[i] + heights[i]; for (int i = 0; i < model.cells.size(); i++) { NativeTableModel.Cell cell = model.cells.get(i); getChildAt(i).layout(cell.column * columnWidth, tops[cell.row], (cell.column + cell.columnSpan) * columnWidth, tops[cell.row + cell.rowSpan]); } }
    }
    private void text(String html,LinearLayout parent) {
        if(html.trim().isEmpty())return;
        RichTextView view=new RichTextView(getContext()); view.setTextSize(font);
        view.setTextColor(AppPalette.of(dark).ink); view.setLinkTextColor(AppPalette.of(dark).accent);
        view.setIncludeFontPadding(false);
        // An attachment contributes its full height to the text line. Applying a
        // multiplier to that line also multiplies the space before a tall image.
        // Keep the reading rhythm tied to the scaled text metrics instead.
        float textLeading=Math.max(0,view.getPaint().getFontMetricsInt(null))*.12f;
        view.setLineSpacing(dp(3)+textLeading,1f);
        view.setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        view.setHighlightColor((AppPalette.of(dark).accent & 0x00ffffff) | 0x33000000);
        view.setPadding(0,dp(3),0,dp(3));
        view.setMovementMethod(LinkMovementMethod.getInstance());
        SpannableStringBuilder value=new SpannableStringBuilder(Html.fromHtml(NativeContentParser.nativeMarkup(html),Html.FROM_HTML_MODE_COMPACT,url->{
            InlineImage image=new InlineImage(view,url,PostRenderer.isEmoticonImage(url));
            view.media.add(image); inlineImages.add(image); image.reflow();
            return image;
        },null));
        applyNativeSizes(value,view.getTextSize()/font);
        // Html.fromHtml treats every image as inline; the web attachment's block CSS
        // must also survive native rendering, including after a narrow image decodes.
        ImageSpan[] attachments=value.getSpans(0,value.length(),ImageSpan.class);
        for(int i=attachments.length-1;i>=0;i--) {
            ImageSpan span=attachments[i];
            if(!(span.getDrawable() instanceof InlineImage)||((InlineImage)span.getDrawable()).emoticon)continue;
            int end=value.getSpanEnd(span);
            if(end<value.length()&&value.charAt(end)!='\n')value.insert(end,"\n");
            int start=value.getSpanStart(span);
            if(start>0&&value.charAt(start-1)!='\n')value.insert(start,"\n");
        }
        int[][] blanks=FlowTextWhitespace.removals(value);
        for(int i=blanks.length-1;i>=0;i--)value.delete(blanks[i][0],blanks[i][1]);
        if(value.length()==0)return;
        for(ImageSpan span:value.getSpans(0,value.length(),ImageSpan.class)) {
            Drawable drawable=span.getDrawable();
            if(drawable instanceof InlineImage)((InlineImage)drawable).textOffset=value.getSpanStart(span);
        }
        for(URLSpan span:value.getSpans(0,value.length(),URLSpan.class)) {
            int start=value.getSpanStart(span),end=value.getSpanEnd(span); value.removeSpan(span);
            value.setSpan(new URLSpan(span.getURL()) {
                @Override public void onClick(View widget) { if(links!=null)links.open(span.getURL()); }
            },start,end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        view.setText(value); view.textReady=true;
        parent.addView(view,new LinearLayout.LayoutParams(-1,-2));
    }
    private static final class ReaderSizeSpan extends MetricAffectingSpan {
        private final float pixels;
        ReaderSizeSpan(float value) { pixels=value; }
        @Override public void updateMeasureState(android.text.TextPaint paint) { paint.setTextSize(pixels); }
        @Override public void updateDrawState(android.text.TextPaint paint) { paint.setTextSize(pixels); }
    }
    private static final class SizeRange {
        final int start,end,depth; final float pixels;
        SizeRange(int start,int end,int depth,float pixels) { this.start=start;this.end=end;this.depth=depth;this.pixels=pixels; }
    }
    /** Resolve BBCode sizes against the reader base, so nested tags do not multiply.
     * The fixed font-family markers are consumed before text reaches a TextView;
     * allowed user fonts cannot emit these markers. Add the final sizes after HTML
     * heading/font spans, and let inner ranges own their characters exactly once.
     */
    private static void applyNativeSizes(SpannableStringBuilder value,float pixelsPerSp) {
        List<SizeRange> ranges=new ArrayList<>();
        for(TypefaceSpan span:value.getSpans(0,value.length(),TypefaceSpan.class)) {
            String family=span.getFamily();
            if(family==null||!family.startsWith(NativeContentParser.SIZE_FACE))continue;
            String[] parts=family.substring(NativeContentParser.SIZE_FACE.length()).split(":",-1);
            if(parts.length!=2)continue;
            try {
                float size=Float.parseFloat(parts[0]);int depth=Integer.parseInt(parts[1]);
                ranges.add(new SizeRange(value.getSpanStart(span),value.getSpanEnd(span),depth,size*pixelsPerSp));
                value.removeSpan(span);
            } catch(NumberFormatException ignored) { /* Only fixed renderer markers are accepted. */ }
        }
        java.util.Collections.sort(ranges,(left,right)->Integer.compare(right.depth,left.depth));
        for(SizeRange range:ranges) {
            ReaderSizeSpan[] inner=value.getSpans(range.start,range.end,ReaderSizeSpan.class);
            java.util.Arrays.sort(inner,(left,right)->Integer.compare(value.getSpanStart(left),value.getSpanStart(right)));
            int cursor=range.start;
            for(ReaderSizeSpan span:inner) {
                int start=Math.max(range.start,value.getSpanStart(span)),end=Math.min(range.end,value.getSpanEnd(span));
                if(start>cursor)value.setSpan(new ReaderSizeSpan(range.pixels),cursor,start,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                cursor=Math.max(cursor,end);
            }
            if(cursor<range.end)value.setSpan(new ReaderSizeSpan(range.pixels),cursor,range.end,Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }
    private void syncImageVisibility() {
        if(inlineImages!=null)syncImageVisibility(inlineImages);
    }
    private void syncImageVisibility(List<InlineImage> media) {
        long previous=activeVisibilityPass;
        activeVisibilityPass=++visibilitySequence;
        try { for(InlineImage image:media)image.syncVisibility(); }
        finally { activeVisibilityPass=previous; }
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        syncImageVisibility();
    }
    @Override protected void onDetachedFromWindow() {
        ViewTreeObserver observer=getViewTreeObserver();
        if(observer.isAlive())observer.removeOnScrollChangedListener(scrollListener);
        for(InlineImage image:inlineImages) { image.cancelRequest(); image.stopAnimation(); }
        super.onDetachedFromWindow();
    }
    @Override protected void onLayout(boolean changed,int left,int top,int right,int bottom) {
        super.onLayout(changed,left,top,right,bottom); syncImageVisibility();
    }
    @Override protected void onVisibilityChanged(View changedView,int visibility) {
        super.onVisibilityChanged(changedView,visibility); syncImageVisibility();
    }
    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility); syncImageVisibility();
    }

    private final class RichTextView extends TextView {
        final List<InlineImage> media=new ArrayList<>();
        final VisibilityGeometry visibility=new VisibilityGeometry();
        long geometryPass;
        boolean textReady,relayoutPosted;
        ViewTreeObserver.OnGlobalLayoutListener pendingAnchor;
        RichTextView(Context context) { super(context); }
        VisibilityGeometry visibilityGeometry() {
            // A scroll/layout pass shares only owner geometry, never a span's position.
            // Calls from drawable animation callbacks have no pass and sample afresh.
            if(activeVisibilityPass==0||geometryPass!=activeVisibilityPass) {
                visibility.capture(this);geometryPass=activeVisibilityPass;
            }
            return visibility;
        }
        void relayoutSpans() {
            if(!textReady||relayoutPosted)return;
            relayoutPosted=true;
            post(()->{
                relayoutPosted=false;
                if(!isAttachedToWindow())return;
                keepVisibleTextPosition();
                CharSequence current=getText();
                if(textReady&&current!=null)setText(new SpannableString(current));
                requestLayout(); invalidate();
            });
        }
        private void clearAnchor() {
            if(pendingAnchor==null)return;
            ViewTreeObserver observer=getViewTreeObserver();
            if(observer.isAlive())observer.removeOnGlobalLayoutListener(pendingAnchor);
            pendingAnchor=null;
        }
        private ScrollView scrollContainer() {
            ViewParent parent=getParent();
            while(parent instanceof View) {
                if(parent instanceof ScrollView)return (ScrollView)parent;
                parent=parent.getParent();
            }
            return null;
        }
        private int topIn(ScrollView scroll) {
            int top=getTop(); ViewParent parent=getParent();
            while(parent instanceof View&&parent!=scroll) {
                View view=(View)parent; top+=view.getTop()-view.getScrollY(); parent=view.getParent();
            }
            return top;
        }
        /** Preserve the current reading line when this image or an earlier post gains its real size. */
        private void keepVisibleTextPosition() {
            clearAnchor();
            final ScrollView scroll=scrollContainer();
            if(scroll==null)return;
            ScrollTouch input=SCROLL_TOUCHES.get(scroll);
            if(input!=null&&input.pressed)return;
            final long touchEpoch=input==null?0:input.epoch;
            TextView reading=this;Rect visible=new Rect();
            if(getLayout()==null)return;
            int ownVertical=scroll.getScrollY()+scroll.getPaddingTop()-topIn(scroll)-getTotalPaddingTop()+getScrollY();
            boolean ownTextAbove=ownVertical>=getLayout().getHeight();
            if(!getLocalVisibleRect(visible)||ownTextAbove){
                // Bottom padding can remain visible after the actual text has left the viewport.
                if(!ownTextAbove&&topIn(scroll)+getHeight()>scroll.getScrollY()+scroll.getPaddingTop())return;
                reading=visibleReadingText(scroll);
            }
            if(reading==null||reading.getLayout()==null)return;
            final TextView anchor=reading;
            final android.text.Layout before=anchor.getLayout();
            int top=topInScroll(anchor,scroll),vertical=scroll.getScrollY()+scroll.getPaddingTop()-top-anchor.getTotalPaddingTop()+anchor.getScrollY();
            if(anchor==this&&(vertical<0||vertical>=before.getHeight()))return;
            int line=before.getLineForVertical(Math.max(0,vertical));
            final int offset=before.getLineStart(line),oldScroll=scroll.getScrollY();
            final int width=scroll.getWidth(),height=scroll.getHeight();
            final int gap=top+anchor.getTotalPaddingTop()+before.getLineTop(line)-anchor.getScrollY()-oldScroll;
            pendingAnchor=new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override public void onGlobalLayout() {
                    clearAnchor();ScrollTouch current=SCROLL_TOUCHES.get(scroll);
                    if((current!=null&&(current.pressed||current.epoch!=touchEpoch))
                            ||!isAttachedToWindow()||!scroll.isAttachedToWindow()||!anchor.isAttachedToWindow()
                            ||!belongsToScroll(anchor,scroll)||scroll.getScrollY()!=oldScroll
                            ||scroll.getWidth()!=width||scroll.getHeight()!=height||anchor.getLayout()==null)return;
                    android.text.Layout after=anchor.getLayout();
                    int position=topInScroll(anchor,scroll)+anchor.getTotalPaddingTop()
                            +after.getLineTop(after.getLineForOffset(Math.min(offset,anchor.length())))-anchor.getScrollY();
                    scroll.scrollTo(scroll.getScrollX(),Math.max(0,position-gap));
                }
            };
            getViewTreeObserver().addOnGlobalLayoutListener(pendingAnchor);
        }
        @Override protected void onSizeChanged(int width,int height,int oldWidth,int oldHeight) {
            super.onSizeChanged(width,height,oldWidth,oldHeight);
            if(width!=oldWidth&&media!=null)for(InlineImage image:media)image.reflow();
        }
        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow(); syncImageVisibility(media);
        }
        @Override protected void onDetachedFromWindow() {
            clearAnchor();
            for(InlineImage image:media) { image.cancelRequest(); image.stopAnimation(); }
            super.onDetachedFromWindow();
        }
    }

    /** Reused storage, but values are valid only during one synchronous visibility pass. */
    private final class VisibilityGeometry {
        final Rect visibleRect=new Rect();
        final int[] ownerLocation=new int[2],viewportLocation=new int[2];
        int ownerToViewportY,horizontalCount;
        float[] clipLeft=new float[2],clipRight=new float[2];
        boolean valid;
        void capture(RichTextView owner) {
            valid=false;horizontalCount=0;ownerToViewportY=0;
            if(!owner.isAttachedToWindow()||!owner.isShown()||!NativeRichText.this.isShown()
                    ||owner.getWindowVisibility()!=VISIBLE||owner.getLayout()==null)return;
            ScrollView viewport=owner.scrollContainer();
            boolean positioned=false;
            if(viewport!=null) {
                if(!viewport.getLocalVisibleRect(visibleRect))return;
                // ScrollView local rectangles include its content scroll offset.
                visibleRect.offset(-viewport.getScrollX(),-viewport.getScrollY());
                owner.getLocationInWindow(ownerLocation);viewport.getLocationInWindow(viewportLocation);
                positioned=true;ownerToViewportY=ownerLocation[1]-viewportLocation[1];
            } else {
                if(!owner.getLocalVisibleRect(visibleRect))return;
                visibleRect.offset(-owner.getScrollX(),-owner.getScrollY());
            }
            ViewParent parent=owner.getParent();
            while(parent instanceof View) {
                View ancestor=(View)parent;
                if(ancestor instanceof HorizontalScrollView) {
                    if(!positioned) { owner.getLocationInWindow(ownerLocation);positioned=true; }
                    ancestor.getLocationInWindow(viewportLocation);
                    float ownerX=ownerLocation[0]-viewportLocation[0]+owner.getTotalPaddingLeft()-owner.getScrollX();
                    boolean padded=((HorizontalScrollView)ancestor).getClipToPadding();
                    if(horizontalCount==clipLeft.length) {
                        clipLeft=java.util.Arrays.copyOf(clipLeft,horizontalCount*2);
                        clipRight=java.util.Arrays.copyOf(clipRight,horizontalCount*2);
                    }
                    clipLeft[horizontalCount]=(padded?ancestor.getPaddingLeft():0)-ownerX;
                    clipRight[horizontalCount]=ancestor.getWidth()-(padded?ancestor.getPaddingRight():0)-ownerX;
                    horizontalCount++;
                }
                parent=ancestor.getParent();
            }
            valid=true;
        }
    }

    @SuppressWarnings("deprecation") // Drawable.getOpacity is required on the API 26 baseline.
    private final class InlineImage extends Drawable implements Drawable.Callback {
        final RichTextView owner;
        final String url;
        final boolean emoticon;
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path clipping=new Path();
        final Map<Runnable,Runnable> scheduled=new LinkedHashMap<>();
        Drawable actual;
        NativeImages.Request request;
        int textOffset=-1,requestGeneration,busyRetries,intrinsicWidth,intrinsicHeight;
        java.lang.ref.WeakReference<android.text.Layout> horizontalLayout;
        int horizontalOffset=-1,horizontalWidth=-1;
        float horizontalLeft,horizontalRight;
        long busyRetryAt,scheduledRetryAt;
        boolean failed,busyPaused,retryScheduled;
        final Runnable retryLoad=()->{retryScheduled=false;scheduledRetryAt=0;syncVisibility();};
        InlineImage(RichTextView owner,String url,boolean emoticon) { this.owner=owner; this.url=url; this.emoticon=emoticon; }
        void scheduleRetry(long at) {
            if(retryScheduled&&scheduledRetryAt==at)return;
            if(retryScheduled)owner.removeCallbacks(retryLoad);
            retryScheduled=true;scheduledRetryAt=at;
            owner.postDelayed(retryLoad,Math.max(0,at-SystemClock.uptimeMillis()));
        }
        void cancelRetry() {
            if(retryScheduled)owner.removeCallbacks(retryLoad);
            retryScheduled=false;scheduledRetryAt=0;
        }
        void cancelRequest() {
            requestGeneration++; cancelRetry(); busyRetryAt=0;
            if(request!=null) { request.cancel(); request=null; }
            if(busyPaused) { failed=false; busyPaused=false; }
            busyRetries=0;
        }
        void load() {
            if(request!=null||actual!=null||failed)return;
            long delay=busyRetryAt-SystemClock.uptimeMillis();
            if(delay>0) { scheduleRetry(busyRetryAt); return; }
            cancelRetry();
            final int token=++requestGeneration;
            request=images.loadDrawable(url,false,new NativeImages.DrawableCallback() {
                @Override public void loaded(Drawable drawable) {
                    if(token!=requestGeneration)return;
                    request=null; InlineImage.this.loaded(drawable);
                }
                @Override public void failed(String message) {
                    if(token!=requestGeneration)return;
                    request=null;
                    if("图片加载繁忙".equals(message)&&busyRetries<12) {
                        long delay=Math.min(4000L,180L << Math.min(5,busyRetries++));
                        busyRetryAt=SystemClock.uptimeMillis()+delay;
                        if(nearViewport(dp(320)))scheduleRetry(busyRetryAt);
                    } else { failed=true; busyPaused="图片加载繁忙".equals(message); }
                    owner.invalidate();
                }
            });
        }
        void loaded(Drawable drawable) {
            if(drawable==null) { failed=true; owner.invalidate(); return; }
            cancelRetry(); busyRetryAt=0; busyRetries=0; busyPaused=false;
            int[] original=images.sourceDimensions(drawable);
            stopAnimation(); actual=drawable; intrinsicWidth=original[0]; intrinsicHeight=original[1];
            failed=false; reflow(); syncVisibility(); owner.invalidate();
        }
        void reflow() {
            int available=owner.getWidth()-owner.getPaddingLeft()-owner.getPaddingRight();
            if(available<=0)available=Math.min(dp(320),Math.max(1,getResources().getDisplayMetrics().widthPixels-dp(64)));
            int maxWidth=Math.max(1,Math.min(available,dp(emoticon?32:560))),width=maxWidth,height=dp(emoticon?32:80);
            // Keep the measured aspect ratio when far-offscreen decoded pixels are released.
            if(intrinsicWidth>0&&intrinsicHeight>0) {
                double scale=Math.min(maxWidth/(double)intrinsicWidth,
                        dp(emoticon?32:720)/(double)intrinsicHeight);
                if(!emoticon)scale=Math.min(1d,scale);
                width=Math.max(1,(int)Math.round(intrinsicWidth*scale));
                height=Math.max(1,(int)Math.round(intrinsicHeight*scale));
            }
            if(actual!=null)actual.setBounds(0,0,width,height);
            if(getBounds().width()!=width||getBounds().height()!=height) {
                setBounds(0,0,width,height); owner.relayoutSpans();
            }
        }
        boolean nearViewport(int margin) {
            if(textOffset<0)return false;
            VisibilityGeometry geometry=owner.visibilityGeometry();
            if(!geometry.valid)return false;
            android.text.Layout layout=owner.getLayout();
            int line=layout.getLineForOffset(Math.min(textOffset,owner.length()));
            int top=owner.getTotalPaddingTop()+layout.getLineTop(line)-owner.getScrollY()+geometry.ownerToViewportY;
            int bottom=owner.getTotalPaddingTop()+layout.getLineBottom(line)-owner.getScrollY()+geometry.ownerToViewportY;
            if(bottom<geometry.visibleRect.top-margin||top>geometry.visibleRect.bottom+margin)return false;
            // Tables may scroll independently of the page; a visible row does not expose every column.
            // Use the same prefetch/release margin without starting animations outside the viewport.
            if(geometry.horizontalCount>0)horizontalImageBounds(layout);
            for(int i=0;i<geometry.horizontalCount;i++)
                if(horizontalRight<=geometry.clipLeft[i]-margin||horizontalLeft>=geometry.clipRight[i]+margin)return false;
            return true;
        }
        /** A colspan cell can remain visible after this particular image has scrolled out. */
        void horizontalImageBounds(android.text.Layout layout) {
            int offset=Math.min(textOffset,Math.max(0,owner.length()-1));
            if(horizontalLayout!=null&&horizontalLayout.get()==layout&&horizontalOffset==offset&&horizontalWidth==getBounds().width())return;
            Path selection=new Path();RectF bounds=new RectF();
            int end=Math.min(owner.length(),offset+1),line=layout.getLineForOffset(offset);
            layout.getSelectionPath(offset,end,selection);selection.computeBounds(bounds,true);
            if(layout.getLineForOffset(end)!=line) {
                // A cross-line selection includes paragraph continuation fill and an empty next-line contour.
                // Keep this line's non-empty area, then remove its continuation fill from the image width.
                android.graphics.Region filled=new android.graphics.Region();
                filled.setPath(selection,new android.graphics.Region((int)Math.floor(bounds.left),layout.getLineTop(line),
                        (int)Math.ceil(bounds.right),layout.getLineBottom(line)));
                Rect oneLine=filled.getBounds();
                if(oneLine.isEmpty())bounds.setEmpty();
                else if(layout.getParagraphDirection(line)==Layout.DIR_RIGHT_TO_LEFT) {
                    bounds.right=oneLine.right;bounds.left=bounds.right-getBounds().width();
                } else {
                    bounds.left=oneLine.left;bounds.right=bounds.left+getBounds().width();
                }
            }
            if(bounds.width()>0&&!Float.isNaN(bounds.left)&&!Float.isInfinite(bounds.left)
                    &&!Float.isNaN(bounds.right)&&!Float.isInfinite(bounds.right)) {
                horizontalLeft=bounds.left;horizontalRight=bounds.right;
            } else {
                float start=layout.getPrimaryHorizontal(offset);
                horizontalLeft=layout.isRtlCharAt(offset)?start-getBounds().width():start;
                horizontalRight=horizontalLeft+getBounds().width();
            }
            horizontalLayout=new java.lang.ref.WeakReference<>(layout);horizontalOffset=offset;horizontalWidth=getBounds().width();
        }
        boolean shouldRun() { return nearViewport(0); }
        void syncVisibility() {
            long previous=activeVisibilityPass;
            if(previous==0)activeVisibilityPass=++visibilitySequence;
            try { syncVisibilityInPass(); }
            finally { activeVisibilityPass=previous; }
        }
        void syncVisibilityInPass() {
            if(actual==null) {
                if(nearViewport(dp(320)))load();
                else cancelRequest();
                return;
            }
            if(!shouldRun()) {
                stopAnimation();
                if(!nearViewport(dp(640))&&images.hasLocalMediaCopy(url)) {
                    // Never recycle: a static bitmap may still belong to another visible span/cache.
                    actual=null;
                }
                return;
            }
            actual.setCallback(this); actual.setVisible(true,false);
            if(actual instanceof Animatable&&!((Animatable)actual).isRunning())((Animatable)actual).start();
        }
        void stopAnimation() {
            if(actual!=null) {
                if(actual instanceof Animatable)((Animatable)actual).stop();
                actual.setVisible(false,false); actual.setCallback(null);
            }
            for(Runnable callback:scheduled.values())owner.removeCallbacks(callback);
            scheduled.clear();
        }
        @Override public void invalidateDrawable(Drawable who) { if(who==actual)owner.invalidate(); }
        @Override public void scheduleDrawable(Drawable who,Runnable what,long when) {
            if(who!=actual||!shouldRun())return;
            unscheduleDrawable(who,what);
            Runnable wrapped=()->{ scheduled.remove(what); if(who==actual&&shouldRun())what.run(); };
            scheduled.put(what,wrapped); owner.postDelayed(wrapped,Math.max(0,when-SystemClock.uptimeMillis()));
        }
        @Override public void unscheduleDrawable(Drawable who,Runnable what) {
            Runnable wrapped=scheduled.remove(what); if(wrapped!=null)owner.removeCallbacks(wrapped);
        }
        @Override public void draw(Canvas canvas) {
            if(actual!=null) {
                if(emoticon) { actual.draw(canvas); return; }
                int saved=canvas.save();
                clipping.reset(); clipping.addRoundRect(0,0,getBounds().width(),getBounds().height(),dp(10),dp(10),Path.Direction.CW);
                canvas.clipPath(clipping); actual.draw(canvas); canvas.restoreToCount(saved); return;
            }
            paint.setColor(dark?0xff243042:0xffe0e6ef);
            canvas.drawRoundRect(0,0,getBounds().width(),getBounds().height(),dp(8),dp(8),paint);
            paint.setColor(AppPalette.of(dark).muted); paint.setTextSize(dp(13));
            if(emoticon) {
                String mark=failed?"!":"…";
                canvas.drawText(mark,(getBounds().width()-paint.measureText(mark))/2f,
                        (getBounds().height()-paint.ascent()-paint.descent())/2f,paint);
                return;
            }
            canvas.drawText(failed?"图片加载失败 · 点按查看重试":"正在加载图片…",dp(12),dp(40),paint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); if(actual!=null)actual.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) {
            paint.setColorFilter(filter); if(actual!=null)actual.setColorFilter(filter);
        }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}

/** Pure Java parser for the fixed, escaped markup emitted by PostRenderer. */
final class NativeContentParser {
    static final int TEXT=0,CODE=1,COLLAPSE=2,QUOTE=3,TABLE=4,MEDIA=5,CRYPT=6;
    private static final String CODE_OPEN="<pre class=\"code\"><code>",CODE_CLOSE="</code></pre>";
    private static final String CONTENT_OPEN="<div class=\"collapse-content\">";
    private static final String QUOTE_OPEN="<blockquote>",QUOTE_CLOSE="</blockquote>";
    static final String SIZE_FACE="ngapure-reader-size:";
    private static final java.util.regex.Pattern SIZE_SPAN=java.util.regex.Pattern.compile("<span style=\"font-size:([0-9]{1,3}(?:\\.[0-9]{1,2})?)px\">");
    private NativeContentParser() { }
    static final class Block {
        final int kind;
        final String body,title,prefix,suffix;
        Block(int kind,String body,String title) { this(kind,body,title,"",""); }
        Block(int kind,String body,String title,String prefix,String suffix) { this.kind=kind; this.body=body; this.title=title;this.prefix=prefix;this.suffix=suffix; }
    }
    static List<Block> parse(String html) {
        List<Block> blocks=new ArrayList<>();
        if(html==null)return blocks;
        MarkupScopes scopes=new MarkupScopes();
        String[] openings={"<details",CODE_OPEN,QUOTE_OPEN,"<table class=\"nga-table\">",
                "<figure class=\"nga-media\"","<section class=\"nga-crypt\">"};
        int[] next=new int[openings.length];
        for(int i=0;i<next.length;i++)next[i]=html.indexOf(openings[i]);
        int from=0;
        while(from<html.length()) {
            // Each delimiter scans forwards only. An absent block type must not
            // rescan the rest of a long multi-quote post for every sibling.
            for(int i=0;i<next.length;i++)if(next[i]>=0&&next[i]<from)next[i]=html.indexOf(openings[i],from);
            int details=next[0],code=next[1],quote=next[2],table=next[3],media=next[4],crypt=next[5];
            int start=details<0?code:code<0?details:Math.min(details,code);
            if(quote>=0&&(start<0||quote<start))start=quote;
            if(table>=0&&(start<0||table<start))start=table;
            if(media>=0&&(start<0||media<start))start=media;
            if(crypt>=0&&(start<0||crypt<start))start=crypt;
            if(start<0) { addText(blocks,html.substring(from),scopes); break; }
            addText(blocks,html.substring(from,start),scopes);
            if(start==table) {
                int end=balancedEnd(html,start,"<table", "</table>");
                if(end<0) { addText(blocks,html.substring(start),scopes); break; }
                int begin=html.indexOf('>',start)+1; blocks.add(new Block(TABLE,html.substring(begin,end),"",scopes.opening(),scopes.closing())); from=end+8; continue;
            }
            if(start==media) {
                int begin=html.indexOf('>',start),end=html.indexOf("</figure>",begin+1);
                if(begin<0||end<0) { addText(blocks,html.substring(start),scopes); break; }
                int href=html.indexOf("href=\"",begin+1),stop=href<0?-1:html.indexOf('"',href+6);
                if(href>=0&&stop>href&&stop<end) blocks.add(new Block(MEDIA,decodeEntities(html.substring(href+6,stop)),html.substring(start,begin).contains("data-kind=\"audio\"")?"audio":"video"));
                else addText(blocks,html.substring(begin+1,end),scopes);
                from=end+9; continue;
            }
            if(start==crypt) {
                int begin=html.indexOf('>',start),end=html.indexOf("</section>",begin+1);
                if(begin<0||end<0) { addText(blocks,html.substring(start),scopes); break; }
                blocks.add(new Block(CRYPT,decodeEntities(html.substring(begin+1,end)),"")); from=end+10; continue;
            }
            if(start==code) {
                int end=html.indexOf(CODE_CLOSE,start+CODE_OPEN.length());
                if(end<0) { addText(blocks,html.substring(start),scopes); break; }
                blocks.add(new Block(CODE,decodeEntities(html.substring(start+CODE_OPEN.length(),end)),""));
                from=end+CODE_CLOSE.length(); continue;
            }
            if(start==quote) {
                int cursor=start+QUOTE_OPEN.length(),nesting=1,close=-1;
                while(nesting>0) {
                    int nextOpen=html.indexOf(QUOTE_OPEN,cursor),nextClose=html.indexOf(QUOTE_CLOSE,cursor);
                    if(nextClose<0)break;
                    if(nextOpen>=0&&nextOpen<nextClose) { nesting++; cursor=nextOpen+QUOTE_OPEN.length(); }
                    else { nesting--; close=nextClose; cursor=nextClose+QUOTE_CLOSE.length(); }
                }
                if(nesting!=0) { addText(blocks,html.substring(start),scopes); break; }
                blocks.add(new Block(QUOTE,scopes.opening()+html.substring(start+QUOTE_OPEN.length(),close)+scopes.closing(),""));
                from=close+QUOTE_CLOSE.length(); continue;
            }
            int open=html.indexOf('>',start),nesting=1,cursor=open+1,close=-1;
            if(open<0) { addText(blocks,html.substring(start),scopes); break; }
            while(nesting>0) {
                int nextOpen=html.indexOf("<details",cursor),nextClose=html.indexOf("</details>",cursor);
                if(nextClose<0)break;
                if(nextOpen>=0&&nextOpen<nextClose) { nesting++; cursor=nextOpen+8; }
                else { nesting--; close=nextClose; cursor=nextClose+10; }
            }
            int summary=html.indexOf("<summary>",open+1);
            int summaryEnd=summary<0?-1:html.indexOf("</summary>",summary+9);
            if(nesting!=0||summary<0||summaryEnd<0||summaryEnd>close) {
                addText(blocks,html.substring(start),scopes); break;
            }
            String body=html.substring(summaryEnd+10,close);
            if(body.startsWith(CONTENT_OPEN)&&body.endsWith("</div>"))
                body=body.substring(CONTENT_OPEN.length(),body.length()-6);
            blocks.add(new Block(COLLAPSE,scopes.opening()+body+scopes.closing(),decodeEntities(html.substring(summary+9,summaryEnd))));
            from=close+10;
        }
        return blocks;
    }
    private static int balancedEnd(String html,int start,String open,String close) {
        int cursor=html.indexOf('>',start)+1,nesting=1;
        while(cursor>0&&cursor<html.length()) { int next=html.indexOf(open,cursor),end=html.indexOf(close,cursor); if(end<0)return -1; if(next>=0&&next<end){nesting++;cursor=next+open.length();}else{if(--nesting==0)return end;cursor=end+close.length();} } return -1;
    }
    private static void addText(List<Block> blocks,String html,MarkupScopes scopes) {
        if(html.isEmpty())return;
        String opening=scopes.opening();scopes.accept(html);
        blocks.add(new Block(TEXT,opening+html+scopes.closing(),""));
    }
    /** Carry fixed inline formats across native block views without copying user HTML.
     * Parsing each view as an independent HTML fragment would otherwise lose an
     * outer bold/link/alignment on the quote and on the following sibling text.
     */
    private static final class MarkupScopes {
        private final List<String> names=new ArrayList<>(),tags=new ArrayList<>();
        String opening() {StringBuilder value=new StringBuilder();for(String tag:tags)value.append(tag);return value.toString();}
        String closing() {StringBuilder value=new StringBuilder();for(int i=names.size()-1;i>=0;i--)value.append("</").append(names.get(i)).append('>');return value.toString();}
        void accept(String html) {
            int from=0;
            while(from<html.length()) {
                int start=html.indexOf('<',from);if(start<0)return;
                int end=html.indexOf('>',start+1);if(end<0)return;
                int first=start+1;boolean close=first<end&&html.charAt(first)=='/';if(close)first++;
                int last=first;while(last<end&&html.charAt(last)>='a'&&html.charAt(last)<='z')last++;
                String name=html.substring(first,last);from=end+1;
                if(!name.equals("strong")&&!name.equals("em")&&!name.equals("u")&&!name.equals("del")
                        &&!name.equals("span")&&!name.equals("div")&&!name.equals("a")
                        &&!name.equals("font")&&!name.equals("sup")&&!name.equals("sub"))continue;
                if(close) {
                    int match=names.lastIndexOf(name);
                    if(match>=0)while(names.size()>match) {names.remove(names.size()-1);tags.remove(tags.size()-1);}
                } else if(names.size()<32) {names.add(name);tags.add(html.substring(start,end+1));}
            }
        }
    }
    /** Html.fromHtml understands inline alignment but does not resolve CSS classes.
     * Only rewrite literal markup emitted by PostRenderer; quoted user HTML remains escaped.
     */
    static String nativeMarkup(String html) {
        String aligned=html.replace("<div class=\"align-left\">","<div style=\"text-align:left;\">")
                .replace("<div class=\"align-center\">","<div style=\"text-align:center;\">")
                .replace("<div class=\"align-right\">","<div style=\"text-align:right;\">");
        if(!aligned.contains("<span style=\"font-size:"))return aligned;
        StringBuilder result=new StringBuilder(aligned.length());List<String> closings=new ArrayList<>();int cursor=0;
        while(cursor<aligned.length()) {
            int start=aligned.indexOf('<',cursor),end=start<0?-1:aligned.indexOf('>',start+1);
            if(end<0){result.append(aligned,cursor,aligned.length());break;}
            result.append(aligned,cursor,start);String tag=aligned.substring(start,end+1);
            if(tag.startsWith("<span ")||tag.equals("<span>")) {
                java.util.regex.Matcher size=SIZE_SPAN.matcher(tag);
                if(size.matches()) {
                    result.append("<font face=\"").append(SIZE_FACE).append(size.group(1)).append(':').append(closings.size()).append("\">");
                    closings.add("</font>");
                } else {result.append(tag);closings.add("</span>");}
            } else if(tag.equals("</span>")&&!closings.isEmpty())result.append(closings.remove(closings.size()-1));
            else result.append(tag);
            cursor=end+1;
        }
        return result.toString();
    }
    static String decodeEntities(String source) {
        StringBuilder result=new StringBuilder(source.length());
        for(int i=0;i<source.length();i++) {
            char ch=source.charAt(i);
            if(ch!='&') { result.append(ch); continue; }
            int end=i+1;
            while(end<source.length()&&end-i<=32&&source.charAt(end)!=';'
                    &&source.charAt(end)!='&'&&!Character.isWhitespace(source.charAt(end)))end++;
            if(end>=source.length()||source.charAt(end)!=';') { result.append(ch); continue; }
            String entity=source.substring(i+1,end),value=null;
            switch(entity) {
                case "amp": value="&"; break;
                case "lt": value="<"; break;
                case "gt": value=">"; break;
                case "quot": value="\""; break;
                case "apos": value="'"; break;
                case "nbsp": value="\u00a0"; break;
                default:
                    if(entity.startsWith("#"))try {
                        boolean hex=entity.startsWith("#x")||entity.startsWith("#X");
                        int point=Integer.parseInt(entity.substring(hex?2:1),hex?16:10);
                        if(point>0&&Character.isValidCodePoint(point)&&!(point>=0xd800&&point<=0xdfff))
                            value=new String(Character.toChars(point));
                    }catch(NumberFormatException ignored) { /* Preserve malformed entities literally. */ }
            }
            if(value==null)result.append(ch);
            else { result.append(value); i=end; }
        }
        return result.toString();
    }
}
