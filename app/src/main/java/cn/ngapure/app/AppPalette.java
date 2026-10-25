package cn.ngapure.app;

/** Shared native colour roles. Gold is an accent, never the primary button fill. */
public final class AppPalette {
    private static final AppPalette LIGHT = new AppPalette(false);
    private static final AppPalette DARK = new AppPalette(true);
    public final int background, surface, ink, muted, line, accent, soft, border;
    public final int primaryFill, onPrimary, selectionFill, selectionStroke, ripple, handle, error;
    public static AppPalette of(boolean dark) { return dark ? DARK : LIGHT; }
    private AppPalette(boolean dark) {
        background = dark ? 0xff171917 : 0xfff5f4ef;
        surface = dark ? 0xff232622 : 0xfffffefa;
        ink = dark ? 0xffe9e7e1 : 0xff292d28;
        muted = dark ? 0xffb0b3a9 : 0xff62685e;
        line = dark ? 0xff393e35 : 0xffe1e0d7;
        accent = dark ? 0xffd2bb88 : 0xff7b5f31;
        soft = dark ? 0xff2e322c : 0xffeeeee7;
        border = dark ? 0xff50564a : 0xffd4d7cb;
        primaryFill = dark ? 0xffdcd7c9 : 0xff343a32;
        onPrimary = dark ? 0xff272b25 : 0xfffffdf6;
        selectionFill = dark ? 0xff403b2d : 0xffede5d5;
        selectionStroke = dark ? 0xffa48d5e : 0xff94794e;
        ripple = dark ? 0x28d2bb88 : 0x207b714f;
        handle = dark ? 0xff646b5e : 0xffc8cdbf;
        error = dark ? 0xffeda39a : 0xffad423c;
    }
}
