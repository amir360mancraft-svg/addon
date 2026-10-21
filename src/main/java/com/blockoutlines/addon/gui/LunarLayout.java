package com.blockoutlines.addon.gui;

import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;

/** The small layout pieces of the Dawn-style settings page: a two column grid of "label ... control" rows. */
final class LunarLayout {
    private LunarLayout() {
    }

    /** One setting: the name on the left, the control on the right (about half of the row). */
    static class Row extends WContainer {
        private static final double CONTROL_SHARE = 0.5;

        @Override
        protected void onCalculateSize() {
            if (cells.size() < 2) {
                super.onCalculateSize();
                return;
            }
            WWidget label = cells.get(0).widget(), control = cells.get(1).widget();
            double gap = theme.scale(14), vpad = theme.scale(6);
            width = label.width + gap + control.width;
            height = Math.max(label.height, control.height) + vpad * 2;
        }

        @Override
        protected void onCalculateWidgetPositions() {
            if (cells.size() < 2) {
                super.onCalculateWidgetPositions();
                return;
            }
            Cell<?> lc = cells.get(0), cc = cells.get(1);
            double gap = theme.scale(14), vpad = theme.scale(6);
            double controlW = Math.max(cc.widget().width, (width - gap) * CONTROL_SHARE);
            controlW = Math.min(controlW, width);

            lc.x = x;
            lc.y = y + vpad;
            lc.width = Math.max(0, width - gap - controlW);
            lc.height = height - vpad * 2;
            lc.alignWidget();

            cc.x = x + width - controlW;
            cc.y = y + vpad;
            cc.width = controlW;
            cc.height = height - vpad * 2;
            cc.alignWidget();
        }
    }

    /** Puts its children in two columns of equal width. */
    static class Grid extends WContainer {
        private double[] rowHeights = new double[0];

        @Override
        protected void onCalculateSize() {
            double gap = theme.scale(18), rowGap = theme.scale(2);
            int n = cells.size();
            double colW = 0;
            for (Cell<?> cell : cells) colW = Math.max(colW, cell.widget().width);
            int rows = (n + 1) / 2;
            rowHeights = new double[rows];
            for (int i = 0; i < n; i++) rowHeights[i / 2] = Math.max(rowHeights[i / 2], cells.get(i).widget().height);
            double h = 0;
            for (int i = 0; i < rows; i++) h += rowHeights[i] + (i > 0 ? rowGap : 0);
            width = n > 1 ? colW * 2 + gap : colW;
            height = h;
        }

        @Override
        protected void onCalculateWidgetPositions() {
            double gap = theme.scale(18), rowGap = theme.scale(2);
            double colW = Math.max(0, (width - gap) / 2);
            double yy = y;
            int n = cells.size();
            for (int row = 0; row < rowHeights.length; row++) {
                for (int col = 0; col < 2; col++) {
                    int i = row * 2 + col;
                    if (i >= n) break;
                    Cell<?> cell = cells.get(i);
                    cell.x = x + col * (colW + gap);
                    cell.y = yy;
                    cell.width = colW;
                    cell.height = rowHeights[row];
                    cell.alignWidget();
                }
                yy += rowHeights[row] + rowGap;
            }
        }
    }

    /**
     * The table Meteor's own setting factories fill with the control of one setting. It right-aligns what it gets
     * and drops the little "reset" button of every setting (the screen has one "Reset Settings" button instead).
     */
    static class ControlTable extends WTable {
        @Override
        public <T extends WWidget> Cell<T> add(T widget) {
            if (widget instanceof LunarWidgets.Button b && b.isReset()) return new Cell<>(widget);
            Cell<T> cell = super.add(widget);
            cell.right();
            return cell;
        }
    }
}
