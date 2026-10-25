package cn.ngapure.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Span-aware grid parsed only from the fixed escaped markup emitted by PostRenderer. */
public final class NativeTableModel {
    private static final int MAX_ROWS = 256, MAX_COLUMNS = 32, MAX_CELLS = 1024;
    public static final class Cell {
        public final int row, column, rowSpan, columnSpan;
        public final boolean header;
        public final String html;
        Cell(int r, int c, int rs, int cs, boolean h, String body) { row = r; column = c; rowSpan = rs; columnSpan = cs; header = h; html = body; }
    }
    public final List<Cell> cells;
    public final int rows, columns;
    private NativeTableModel(List<Cell> values, int r, int c) { cells = Collections.unmodifiableList(values); rows = r; columns = c; }
    public static NativeTableModel parse(String html) {
        if (html == null || html.length() > 1048576) return null;
        List<Cell> cells = new ArrayList<>(); boolean[][] occupied = new boolean[MAX_ROWS][MAX_COLUMNS];
        int cursor = 0, row = -1, column = 0, rows = 0, columns = 0; boolean header = false;
        while (cursor < html.length()) {
            int nextRow = html.indexOf("<tr", cursor), nextCell = html.indexOf("<td", cursor);
            if (nextCell < 0 && nextRow < 0) break;
            if (nextRow >= 0 && (nextCell < 0 || nextRow < nextCell)) {
                int end = html.indexOf('>', nextRow); if (end < 0) return null;
                if (++row >= MAX_ROWS) return null; column = 0; header = html.substring(nextRow, end + 1).contains("class=\"header\""); cursor = end + 1; rows = Math.max(rows, row + 1); continue;
            }
            if (row < 0) { row = 0; rows = 1; }
            int open = html.indexOf('>', nextCell); if (open < 0) return null;
            int close = closingCell(html, open + 1); if (close < 0) return null;
            int cs = span(html.substring(nextCell, open + 1), "colspan"), rs = span(html.substring(nextCell, open + 1), "rowspan");
            while (column < MAX_COLUMNS && occupied[row][column]) column++;
            if (column + cs > MAX_COLUMNS || row + rs > MAX_ROWS || cells.size() >= MAX_CELLS) return null;
            for (int r = row; r < row + rs; r++) for (int c = column; c < column + cs; c++) { if (occupied[r][c]) return null; occupied[r][c] = true; }
            cells.add(new Cell(row, column, rs, cs, header, html.substring(open + 1, close)));
            column += cs; columns = Math.max(columns, column); rows = Math.max(rows, row + rs); cursor = close + 5;
        }
        return cells.isEmpty() ? null : new NativeTableModel(cells, rows, columns);
    }
    private static int closingCell(String html, int cursor) {
        int nesting = 1;
        while (cursor < html.length()) {
            int open = html.indexOf("<td", cursor), close = html.indexOf("</td>", cursor);
            if (close < 0) return -1;
            if (open >= 0 && open < close) { nesting++; cursor = open + 3; }
            else { if (--nesting == 0) return close; cursor = close + 5; }
        }
        return -1;
    }
    private static int span(String tag, String name) { Matcher found = Pattern.compile(" " + name + "=\"([0-9]{1,2})\"").matcher(tag); if (!found.find()) return 1; int count = Integer.parseInt(found.group(1)); return count >= 1 && count <= 32 ? count : 1; }
}
