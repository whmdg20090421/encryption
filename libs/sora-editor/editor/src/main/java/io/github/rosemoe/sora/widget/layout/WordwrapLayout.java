/*
 *    sora-editor - the awesome code editor for Android
 *    https://github.com/Rosemoe/sora-editor
 *    Copyright (C) 2020-2024  Rosemoe
 *
 *     This library is free software; you can redistribute it and/or
 *     modify it under the terms of the GNU Lesser General Public
 *     License as published by the Free Software Foundation; either
 *     version 2.1 of the License, or (at your option) any later version.
 *
 *     This library is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *     Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public
 *     License along with this library; if not, write to the Free Software
 *     Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 *     USA
 *
 *     Please contact Rosemoe by email 2073412493@qq.com if you need
 *     additional information or have any questions
 */
package io.github.rosemoe.sora.widget.layout;

import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import io.github.rosemoe.sora.graphics.Paint;
import io.github.rosemoe.sora.graphics.TextRow;
import io.github.rosemoe.sora.lang.analysis.StyleUpdateRange;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.SpanFactory;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.lang.styling.inlayHint.InlayHint;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentLine;
import io.github.rosemoe.sora.util.IntPair;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Wordwrap layout for editor
 * <p>
 * This layout will not let character displayed outside the editor's width
 * <p>
 * However, using this can be power-costing because we will have to recreate this layout in various
 * conditions, such as when the line number increases and its width grows or when the text size has changed
 *
 * @author Rose
 */
public class WordwrapLayout extends AbstractLayout {

    /**
     * When measuring text in wordwrap mode, we must use the max possible width of the character sequence
     * so that no character will be invisible after its styles are applied on actual drawing.
     */

    private final static List<Span> sSpansForWordwrap = new ArrayList<>();

    static {
        sSpansForWordwrap.add(SpanFactory.obtainNoExt(0, TextStyle.makeStyle(0, 0, true, true, false)));
    }

    private final int width;
    private final float miniGraphWidth;
    private final boolean antiWordBreaking;
    private final boolean supportRtlRow;

    /**
     * Row table. Always a complete list covering every line of the document, so readers never need
     * to guard against a missing region. It is replaced (not mutated) when a background segment is
     * published, and a reference assignment is atomic, so readers always observe a consistent
     * table. Marked volatile for safe publication across the layout worker threads.
     */
    private volatile List<RowRegion> rowTable;

    /**
     * How many lines above and below the viewport are broken synchronously before the background
     * pass runs. Bounds the main-thread stall to a small, constant amount of work.
     */
    private static final int PRIORITY_MARGIN_LINES = 200;

    /**
     * Priority window (inclusive line range) and its already-broken rows. Overlaid on top of the
     * per-segment results by {@link #rebuildTable}. Guarded by {@code this}.
     */
    private int priorityStart;
    private int priorityEnd;
    @Nullable
    private List<RowRegion> priorityRows;

    /**
     * Monotonic id of the newest break-all pass. Background tasks carrying a stale id drop their
     * results, so a resize that starts while an older pass is still running can not be overwritten.
     */
    private final java.util.concurrent.atomic.AtomicInteger breakVersion = new java.util.concurrent.atomic.AtomicInteger(0);

    public WordwrapLayout(@NonNull CodeEditor editor, @NonNull Content text, boolean antiWordBreaking, boolean supportRtlRow, @Nullable WordwrapLayout oldLayout, boolean clearCache) {
        super(editor, text);
        this.antiWordBreaking = antiWordBreaking;
        this.supportRtlRow = supportRtlRow;
        rowTable = oldLayout != null ? oldLayout.rowTable : new ArrayList<>();
        if (clearCache) {
            rowTable.clear();
        }
        miniGraphWidth = (editor.getNonPrintablePaintingFlags() & CodeEditor.FLAG_DRAW_SOFT_WRAP) != 0 ?
                editor.getRenderer().getMiniGraphWidth() : 0f;
        width = editor.getWidth() - (int) (editor.measureTextRegionOffset() + editor.getTextPaint().measureText("a")) - (int) miniGraphWidth * 2;
        breakAllLines();
    }

    private void breakAllLines() {
        var lineCount = text.getLineCount();
        // Adaptive subtask count: about one subtask per LINES_PER_SUBTASK lines, clamped to [SUBTASK_COUNT, SUBTASK_COUNT_MAX].
        // More subtasks improve parallelism for very large files; small files keep the minimum.
        var adaptive = (int) Math.ceil((float) lineCount / LINES_PER_SUBTASK);
        var taskCount = Math.max(SUBTASK_COUNT, Math.min(SUBTASK_COUNT_MAX, adaptive));
        taskCount = Math.min(taskCount, Math.max(1, lineCount));

        // Segment boundaries: segment i covers lines [segStart[i], segEnd[i]] (inclusive).
        final var segStart = new int[taskCount];
        final var segEnd = new int[taskCount];
        var sizeEachTask = lineCount / taskCount;
        for (int i = 0; i < taskCount; i++) {
            segStart[i] = sizeEachTask * i;
            segEnd[i] = i + 1 == taskCount ? (lineCount - 1) : (sizeEachTask * (i + 1) - 1);
        }

        final int version = breakVersion.incrementAndGet();

        // Publish a complete but approximate table at once: one row per line. Every row index the
        // editor may query is valid right now, so no reader needs special-casing while the real
        // soft-wrap breaks are computed. This mirrors how MT renders a usable frame immediately and
        // refines it afterwards.
        final var results = new WordwrapResult[taskCount];
        synchronized (this) {
            priorityStart = priorityEnd = 0;
            priorityRows = null;
            rebuildTable(results, segStart, segEnd, lineCount);
        }

        // Anchor on the line the user is currently viewing. The current editor layout is still the
        // previous one here (this instance is not assigned to the editor yet). On the very first
        // layout there is no previous layout, so we simply start from the top.
        int anchorLine = 0;
        try {
            var old = editor.getLayout();
            if (old != null && old != this) {
                anchorLine = old.getLineNumberForRow(Math.max(0, editor.getOffsetY() / Math.max(1, editor.getRowHeight())));
            }
        } catch (Throwable ignored) {
            anchorLine = 0;
        }
        final int visibleLine = Math.max(0, Math.min(lineCount - 1, anchorLine));

        editor.setLayoutBusy(true);

        // Phase one: synchronously break a bounded window around the viewport so the user sees
        // correct wrapping right away. The window is intentionally small (viewport plus margin) so
        // the main thread stall is bounded, no matter how large the document is.
        final int windowStart = Math.max(0, visibleLine - PRIORITY_MARGIN_LINES);
        final int windowEnd = Math.min(lineCount - 1, visibleLine + PRIORITY_MARGIN_LINES);
        computePriorityWindow(version, results, segStart, segEnd, lineCount, windowStart, windowEnd);

        // Capture the editor as a snapshot: this layout may be destroyed (editor field cleared)
        // before the posted runnable executes. Comparing against the live field would then see
        // `null == null` and wrongly proceed, dereferencing a null editor.
        final var visibleEditor = editor;
        visibleEditor.postInLifecycle(() -> {
            if (WordwrapLayout.this.editor != visibleEditor) {
                // This layout was abandoned before the runnable ran.
                return;
            }
            // Scroll position is not adjusted here. The editor already keeps the viewport anchored
            // across relayouts: CodeEditor.setLayoutBusy(false) re-applies the position captured by
            // EditorTouchEventHandler when a pinch gesture starts (positionNotApplied). Adding our
            // own correction would fight that logic and visibly jump before it snapped back.
            visibleEditor.setLayoutBusy(false);
            visibleEditor.postInvalidate();
        });

        // Phase two: break the remaining segments in the background, publishing as they complete.
        // The segments overlapping the priority window are still computed; their rows are simply
        // overridden by the priority rows until then (rebuildTable overlays the window).
        final var monitor = new TaskMonitor(taskCount, (completed, cancelledCount) -> {
            final var ed = this.editor;
            if (ed != null) {
                ed.postInvalidate();
            }
        });
        for (int idx = 0; idx < taskCount; idx++) {
            submitTask(new WordwrapAnalyzeTask(monitor, idx, segStart[idx], segEnd[idx], version, results, segStart, segEnd, lineCount));
        }
    }

    /**
     * Synchronously break the priority window and publish it if it is still the newest version.
     */
    private void computePriorityWindow(int version, WordwrapResult[] results, int[] segStart, int[] segEnd, int lineCount, int windowStart, int windowEnd) {
        List<RowRegion> rows;
        try {
            rows = breakLineRange(windowStart, windowEnd);
        } catch (Throwable t) {
            return;
        }
        if (breakVersion.get() != version) {
            return;
        }
        synchronized (this) {
            priorityStart = windowStart;
            priorityEnd = windowEnd;
            priorityRows = rows;
            rebuildTable(results, segStart, segEnd, lineCount);
        }
    }

    /**
     * Break an inclusive line range on the calling thread.
     */
    private List<RowRegion> breakLineRange(int startLine, int endLine) {
        var list = new ArrayList<RowRegion>();
        var paint = new Paint(editor.isRenderFunctionCharacters());
        paint.set(editor.getTextPaint());
        paint.onAttributeUpdate();
        var reusableRow = new TextRow();
        for (int line = startLine; line <= endLine && line < text.getLineCount(); line++) {
            list.addAll(breakLine(line, text.getLine(line), paint, reusableRow));
        }
        return list;
    }

    /**
     * Rebuild a complete, ordered row table from the computed segments, overlaying the priority
     * window. Lines whose data is not available yet fall back to a single row spanning the whole
     * line, so the table length is always consistent with the document.
     */
    private void rebuildTable(WordwrapResult[] results, int[] segStart, int[] segEnd, int lineCount) {
        var table = new ArrayList<RowRegion>(Math.max(lineCount, 16));
        int priority = 0;
        for (int i = 0; i < results.length; i++) {
            int line = segStart[i];
            var regions = results[i] != null ? results[i].regions : null;
            int cursor = 0;
            while (line <= segEnd[i] && line < lineCount) {
                if (priorityRows != null && line >= priorityStart && line <= priorityEnd) {
                    while (priority < priorityRows.size() && priorityRows.get(priority).line < line) {
                        priority++;
                    }
                    while (priority < priorityRows.size() && priorityRows.get(priority).line == line) {
                        table.add(priorityRows.get(priority++));
                    }
                    line++;
                    continue;
                }
                if (regions != null) {
                    while (cursor < regions.size() && regions.get(cursor).line < line) {
                        cursor++;
                    }
                    if (cursor < regions.size() && regions.get(cursor).line == line) {
                        while (cursor < regions.size() && regions.get(cursor).line == line) {
                            table.add(regions.get(cursor++));
                        }
                        line++;
                        continue;
                    }
                }
                // Not computed yet: one placeholder row spanning the whole line.
                table.add(new RowRegion(line, 0, text.getColumnCount(line), null, 0f, false));
                line++;
            }
        }
        rowTable = table;
    }

    private int findRow(int line) {
        int index;
        // Binary find line
        int left = 0, right = rowTable.size();
        while (left <= right) {
            var mid = (left + right) / 2;
            if (mid < 0 || mid >= rowTable.size()) {
                left = Math.max(0, Math.min(rowTable.size() - 1, mid));
                break;
            }
            int value = rowTable.get(mid).line;
            if (value < line) {
                left = mid + 1;
            } else if (value > line) {
                right = mid - 1;
            } else {
                left = mid;
                break;
            }
        }
        index = left;
        while (index > 0 && rowTable.get(index).startColumn > 0) {
            index--;
        }
        return index;
    }

    public int findRow(int line, int column) {
        int row = findRow(line);
        while (rowTable.get(row).endColumn <= column && row + 1 < rowTable.size() && rowTable.get(row + 1).line == line) {
            row++;
        }
        return row;
    }

    private void breakLines(int startLine, int endLine) {
        int insertPosition = 0;
        while (insertPosition < rowTable.size()) {
            if (rowTable.get(insertPosition).line < startLine) {
                insertPosition++;
            } else {
                break;
            }
        }
        while (insertPosition < rowTable.size()) {
            int line = rowTable.get(insertPosition).line;
            if (line >= startLine && line <= endLine) {
                rowTable.remove(insertPosition);
            } else {
                break;
            }
        }
        List<RowRegion> newRegions = new ArrayList<>();
        for (int i = startLine; i <= endLine; i++) {
            newRegions.addAll(breakLine(i, text.getLine(i), null));
        }
        rowTable.addAll(insertPosition, newRegions);
    }

    /**
     * Break a single line
     */
    private List<RowRegion> breakLine(int line, ContentLine sequence, Paint paint) {
        return breakLine(line, sequence, paint, null);
    }

    /**
     * Break a single line.
     * <p>
     * {@code reusableRow} may be a {@link TextRow} owned by the caller (for example a background
     * layout task breaking many lines in a row). {@link TextRow#set} re-initializes all state, so
     * reusing the same instance across lines is safe and avoids allocating one TextRow per line.
     * When {@code null}, a fresh instance is created as before.
     */
    private List<RowRegion> breakLine(int line, ContentLine sequence, Paint paint, TextRow reusableRow) {
        Paint p = paint;
        if (p == null) {
            p = new Paint(editor.isRenderFunctionCharacters());
            p.set(editor.getTextPaint());
        }
        var tr = reusableRow != null ? reusableRow : new TextRow();
        var directions = text.getLineDirections(line);
        tr.set(sequence, 0, sequence.length(), sSpansForWordwrap, getInlayHints(line), directions, p, null, editor.getRenderer().createTextRowParams());

        boolean isRtlBased = false;
        if (supportRtlRow && sequence.mayNeedBidi()) {
            int minRunLevel = Integer.MAX_VALUE;
            for (int i = 0; i < directions.getRunCount(); i++) {
                minRunLevel = Math.min(minRunLevel, directions.getRunLevel(i));
            }
            if ((minRunLevel & 1) != 0) {
                isRtlBased = true;
            }
        }

        var rows = tr.breakText(width, antiWordBreaking);
        var results = new ArrayList<RowRegion>();
        for (var row : rows) {
            results.add(new RowRegion(line, row.startColumn, row.endColumn, row.inlayHints, row.rowWidth, isRtlBased));
        }
        return results;
    }

    @Override
    public void beforeReplace(@NonNull Content content) {
        // An edit invalidates any in-flight background pass: its precomputed segments no longer
        // match the document. Bumping the version makes those tasks drop their results, and the
        // in-place line patching below takes over. The table stays complete throughout because
        // lines whose segment was not computed still have their placeholder row.
        synchronized (this) {
            breakVersion.incrementAndGet();
        }
    }

    @Override
    public void afterInsert(@NonNull Content content, int startLine, int startColumn, int endLine, int endColumn, @NonNull CharSequence insertedContent) {
        super.afterInsert(content, startLine, startColumn, endLine, endColumn, insertedContent);
        // Update line numbers
        int delta = endLine - startLine;
        if (delta != 0) {
            for (int row = findRow(startLine + 1); row < rowTable.size(); row++) {
                rowTable.get(row).line += delta;
            }
        }
        // Re-break
        breakLines(startLine, endLine);
    }

    @Override
    public void afterDelete(@NonNull Content content, int startLine, int startColumn, int endLine, int endColumn, @NonNull CharSequence deletedContent) {
        super.afterDelete(content, startLine, startColumn, endLine, endColumn, deletedContent);
        int delta = endLine - startLine;
        if (delta != 0) {
            int startRow = findRow(startLine);
            while (startRow < rowTable.size()) {
                int line = rowTable.get(startRow).line;
                if (line >= startLine && line <= endLine) {
                    rowTable.remove(startRow);
                } else {
                    break;
                }
            }
            for (int row = findRow(endLine + 1); row < rowTable.size(); row++) {
                var region = rowTable.get(row);
                if (region.line >= endLine)
                    region.line -= delta;
            }
        }
        breakLines(startLine, startLine);
    }

    @Override
    public void destroyLayout() {
        super.destroyLayout();
        rowTable = null;
    }

    @NonNull
    @Override
    public Row getRowAt(int rowIndex) {
        if (rowTable.isEmpty()) {
            var r = new Row();
            r.startColumn = 0;
            r.endColumn = text.getColumnCount(rowIndex);
            r.isLeadingRow = true;
            r.isTrailingRow = true;
            r.lineIndex = rowIndex;
            r.inlayHints = getInlayHints(rowIndex);
            return r;
        }
        var region = rowTable.get(rowIndex);
        var isLeadingRow = rowIndex <= 0 || rowTable.get(rowIndex - 1).line != region.line;
        var isTrailingRow = rowIndex + 1 >= rowTable.size() || rowTable.get(rowIndex + 1).line != region.line;
        return rowTable.get(rowIndex).toRow(isLeadingRow, isTrailingRow, width);
    }

    @Override
    public int getLineNumberForRow(int row) {
        if (rowTable.isEmpty()) {
            return Math.max(0, Math.min(row, text.getLineCount() - 1));
        }
        return row >= rowTable.size() ? rowTable.get(rowTable.size() - 1).line : rowTable.get(row).line;
    }

    @NonNull
    @Override
    public RowIterator obtainRowIterator(int initialRow, @Nullable SparseArray<ContentLine> preloadedLines) {
        return rowTable.isEmpty() ? new LineBreakLayout.LineBreakLayoutRowItr(this, text, initialRow, preloadedLines) : new WordwrapLayoutRowItr(initialRow);
    }

    @Override
    public long getUpPosition(int line, int column) {
        if (rowTable.isEmpty()) {
            if (line - 1 < 0) {
                return IntPair.pack(0, 0);
            }
            int c_column = text.getColumnCount(line - 1);
            if (column > c_column) {
                column = c_column;
            }
            return IntPair.pack(line - 1, column);
        }
        int row = findRow(line, column);
        if (row > 0) {
            var offset = column - rowTable.get(row).startColumn;
            var lastRow = rowTable.get(row - 1);
            var max = lastRow.endColumn - lastRow.startColumn;
            offset = Math.min(offset, max);
            return IntPair.pack(lastRow.line, lastRow.startColumn + offset);
        }
        return IntPair.pack(0, 0);
    }

    @Override
    public long getDownPosition(int line, int column) {
        if (rowTable.isEmpty()) {
            int c_line = text.getLineCount();
            if (line + 1 >= c_line) {
                return IntPair.pack(line, text.getColumnCount(line));
            } else {
                int c_column = text.getColumnCount(line + 1);
                if (column > c_column) {
                    column = c_column;
                }
                return IntPair.pack(line + 1, column);
            }
        }
        int row = findRow(line, column);
        if (row + 1 < rowTable.size()) {
            var offset = column - rowTable.get(row).startColumn;
            var nextRow = rowTable.get(row + 1);
            var max = nextRow.endColumn - nextRow.startColumn;
            offset = Math.min(offset, max);
            return IntPair.pack(nextRow.line, nextRow.startColumn + offset);
        } else {
            return IntPair.pack(line, text.getColumnCount(line));
        }
    }

    @Override
    public int getLayoutWidth() {
        return 0;
    }

    @Override
    public int getLayoutHeight() {
        if (rowTable.isEmpty()) {
            return editor.getRowHeight() * text.getLineCount();
        }
        return rowTable.size() * editor.getRowHeight();
    }

    @Override
    public int getRowIndexForPosition(int index) {
        var pos = editor.getText().getIndexer().getCharPosition(index);
        var line = pos.line;
        if (rowTable.isEmpty()) {
            return line;
        }
        var column = pos.column;
        int row = findRow(line);
        if (row < rowTable.size()) {
            var region = rowTable.get(row);
            if (region.line != line) {
                return 0;
            }
            while (region.startColumn < column && row + 1 < rowTable.size()) {
                row++;
                region = rowTable.get(row);
                if (region.line != line || region.startColumn > column) {
                    row--;
                    break;
                }
            }
            return row;
        }
        return 0;
    }

    @Override
    public void invalidateLines(StyleUpdateRange range) {
        var itr = range.lineIndexIterator(text.getLineCount() - 1);
        while (itr.hasNext()) {
            var line = itr.nextInt();
            breakLines(line, line);
        }
    }

    @NonNull
    @Override
    public VisualLocation getVisualPositionForLayoutOffset(float offsetX, float offsetY) {
        if (rowTable.isEmpty()) {
            int lineCount = text.getLineCount();
            int line = Math.min(lineCount - 1, Math.max((int) (offsetY / editor.getRowHeight()), 0));
            var tr = editor.getRenderer().createTextRow(line);
            var pos = tr.getElementPositionForCursorOffset(offsetX);
            return new VisualLocation(line, pos.textOffset, pos.element, pos.isInElementBounds);
        }
        int row = (int) (offsetY / editor.getRowHeight());
        row = Math.max(0, Math.min(row, rowTable.size() - 1));
        RowRegion region = rowTable.get(row);
        if (region.startColumn != 0) {
            offsetX -= miniGraphWidth;
        }
        offsetX -= region.getRenderTranslateX(width);
        var tr = editor.getRenderer().createTextRow(row);
        var pos = tr.getElementPositionForCursorOffset(offsetX);
        return new VisualLocation(region.line, pos.textOffset, pos.element, pos.isInElementBounds);
    }

    @NonNull
    @Override
    public float[] getCharLayoutOffset(int line, int column, float[] dest) {
        if (dest == null || dest.length < 2) {
            dest = new float[2];
        }
        if (rowTable.isEmpty()) {
            dest[0] = editor.getRowBottom(line);
            var tr = editor.getRenderer().createTextRow(line);
            dest[1] = tr.getCursorOffsetForIndex(column);
            return dest;
        }
        int row = findRow(line);
        if (row < rowTable.size()) {
            RowRegion region = rowTable.get(row);
            if (region.line != line) {
                dest[0] = dest[1] = 0;
                return dest;
            }
            while (region.startColumn < column && row + 1 < rowTable.size()) {
                row++;
                region = rowTable.get(row);
                if (region.line != line || region.startColumn > column) {
                    row--;
                    region = rowTable.get(row);
                    break;
                }
            }
            dest[0] = editor.getRowBottom(row);
            var tr = editor.getRenderer().createTextRow(row);
            dest[1] = tr.getCursorOffsetForIndex(column);
            if (region.startColumn != 0) {
                dest[1] += miniGraphWidth;
            }
            dest[1] += region.getRenderTranslateX(width);
        } else {
            dest[0] = dest[1] = 0;
        }
        return dest;
    }

    @Override
    public int getRowCountForLine(int line) {
        if (rowTable.isEmpty()) {
            return 1;
        }
        int row = findRow(line);
        int count = 0;
        while (row < rowTable.size() && rowTable.get(row).line == line) {
            count++;
            row++;
        }
        return count;
    }

    /**
     * Get soft breaks on the given line
     */
    public List<Integer> getSoftBreaksForLine(int line) {
        if (rowTable.isEmpty()) {
            return Collections.emptyList();
        }
        int row = findRow(line);
        var list = new ArrayList<Integer>();
        while (row < rowTable.size() && rowTable.get(row).line == line) {
            var column = rowTable.get(row).startColumn;
            if (column != 0) {
                list.add(column);
            }
            row++;
        }
        return list;
    }

    @Override
    public int getRowCount() {
        if (rowTable.isEmpty()) {
            return text.getLineCount();
        }
        return rowTable.size();
    }

    static class RowRegion {

        final int startColumn;
        final int endColumn;
        List<InlayHint> inlayHints;
        int line;
        float rowWidth;
        boolean displayFromRight;

        RowRegion(int line, int start, int end, List<InlayHint> inlayHints, float rowWidth, boolean displayFromRight) {
            this.line = line;
            startColumn = start;
            endColumn = end;
            this.inlayHints = inlayHints;
            this.rowWidth = rowWidth;
            this.displayFromRight = displayFromRight;
        }

        public Row toRow(boolean isLeadingRow, boolean isTrailingRow, float layoutWidth) {
            var row = new Row();
            row.isLeadingRow = isLeadingRow;
            row.isTrailingRow = isTrailingRow;
            row.startColumn = startColumn;
            row.endColumn = endColumn;
            row.lineIndex = line;
            row.inlayHints = inlayHints == null ? Collections.emptyList() : inlayHints;
            row.renderTranslateX = getRenderTranslateX(layoutWidth);
            return row;
        }

        public float getRenderTranslateX(float layoutWidth) {
            return displayFromRight && layoutWidth > rowWidth ? layoutWidth - rowWidth : 0f;
        }

        @NonNull
        @Override
        public String toString() {
            return "RowRegion{" +
                    "startColumn=" + startColumn +
                    ", endColumn=" + endColumn +
                    ", line=" + line +
                    '}';
        }
    }

    private static class WordwrapResult implements Comparable<WordwrapResult> {

        int index;
        List<RowRegion> regions;

        public WordwrapResult(int idx, List<RowRegion> r) {
            index = idx;
            regions = r;
        }

        @Override
        public int compareTo(WordwrapResult wordwrapResult) {
            return Integer.compare(index, wordwrapResult.index);
        }
    }

    class WordwrapLayoutRowItr implements RowIterator {

        private final Row result;
        private final int initRow;
        private int currentRow;

        WordwrapLayoutRowItr(int initialRow) {
            initRow = currentRow = initialRow;
            result = new Row();
        }

        @NonNull
        @Override
        public Row next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            RowRegion region = rowTable.get(currentRow);
            result.lineIndex = region.line;
            result.startColumn = region.startColumn;
            result.endColumn = region.endColumn;
            result.inlayHints = region.inlayHints == null ? Collections.emptyList() : region.inlayHints;
            result.isLeadingRow = currentRow <= 0 || rowTable.get(currentRow - 1).line != region.line;
            result.isTrailingRow = currentRow + 1 >= rowTable.size() || rowTable.get(currentRow + 1).line != region.line;
            result.renderTranslateX = region.getRenderTranslateX(width);
            currentRow++;
            return result;
        }

        @Override
        public boolean hasNext() {
            return currentRow >= 0 && currentRow < rowTable.size();
        }

        @Override
        public void reset() {
            currentRow = initRow;
        }
    }

    private class WordwrapAnalyzeTask extends LayoutTask<WordwrapResult> {

        private final int start, end, id;
        private final Paint paint;
        private final TextRow reusableRow;
        private final int version;
        private final WordwrapResult[] results;
        private final int[] segStart;
        private final int[] segEnd;
        private final int lineCount;

        WordwrapAnalyzeTask(TaskMonitor monitor, int id, int start, int end, int version,
                            WordwrapResult[] results, int[] segStart, int[] segEnd, int lineCount) {
            super(monitor);
            this.start = start;
            this.id = id;
            this.end = end;
            this.version = version;
            this.results = results;
            this.segStart = segStart;
            this.segEnd = segEnd;
            this.lineCount = lineCount;
            paint = new Paint(editor.isRenderFunctionCharacters());
            paint.set(editor.getTextPaint());
            paint.onAttributeUpdate();
            // One TextRow is reused for every line in this task. LayoutTask runs on a single
            // worker thread and TextRow.set() fully re-initializes the row, so this is safe and
            // avoids allocating a TextRow per line.
            reusableRow = new TextRow();
        }

        @Override
        protected WordwrapResult compute() {
            var list = new ArrayList<RowRegion>();
            text.runReadActionsOnLines(start, end, (int index, ContentLine line, Content.ContentLineConsumer2.AbortFlag abortFlag) -> {
                list.addAll(breakLine(index, line, paint, reusableRow));
                if (!shouldRun()) {
                    abortFlag.set = true;
                }
            });
            return new WordwrapResult(id, list);
        }

        @Override
        public void run() {
            // This overrides LayoutTask.run(), so the base shouldRun() guard must be repeated here:
            // the layout may have been destroyed while the task was queued, which nulls out the
            // editor that compute() depends on.
            if (!shouldRun()) {
                if (monitor != null) {
                    monitor.reportCancelled();
                }
                return;
            }
            var result = compute();
            // Publish this segment only if no newer pass has started. A stale pass must not
            // overwrite a more recent layout (for example, results of a previous text size).
            if (breakVersion.get() != version) {
                if (monitor != null) {
                    monitor.reportCancelled();
                }
                return;
            }
            results[id] = result;
            synchronized (WordwrapLayout.this) {
                if (breakVersion.get() == version && WordwrapLayout.this.editor != null) {
                    rebuildTable(results, segStart, segEnd, lineCount);
                }
            }
            if (monitor != null) {
                monitor.reportCompleted(result);
            }
        }
    }

}
