package jspectrumanalyzer.fx.ui;

import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import jspectrumanalyzer.wifi.capture.CaptureStats;

/**
 * Horizontal stacked bar showing the proportion of management /
 * control / data / extension frames in a {@link CaptureStats.Snapshot}.
 *
 * <p>Designed for the monitor-capture panel as a single-glance "what is
 * the air full of?" widget, replacing the dense
 * {@code "Type: mgmt=12345 ctrl=678 ..."} label that the original UI
 * shipped with. Each segment is colour-coded and labelled with its
 * percentage when wide enough; sub-1% slivers omit the label rather
 * than render unreadable text.
 *
 * <h2>Why a Canvas instead of an HBox of styled Regions?</h2>
 * The four colour segments need to (a) total exactly the bar's pixel
 * width regardless of rounding and (b) overlay text on top with
 * collision-aware truncation. A Canvas gives us pixel-level control
 * over both for ~30 lines of code; the HBox approach would need
 * percentage Hgrow rules (which round) plus per-segment width-binding
 * for the text-fits-or-not test (which is fiddly in CSS).
 */
public final class FrameTypeBar extends Canvas {

    private static final Color BG = Color.web("#16161a");
    private static final Color BORDER = Color.web("#2a2a30");
    private static final Color LABEL = Color.web("#0a0a10");

    /** Mgmt: teal/cyan - same accent family as the insight card. */
    private static final Color MGMT = Color.web("#2f9aa6");
    /** Ctrl: amber - small but mechanically important. */
    private static final Color CTRL = Color.web("#d99c2e");
    /** Data: green - "real traffic". */
    private static final Color DATA = Color.web("#5cae5c");
    /** Ext: muted purple - rare, protocol-experimental. */
    private static final Color EXT  = Color.web("#8a4fbd");

    private CaptureStats.Snapshot snapshot;

    public FrameTypeBar() {
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
    }

    public void setSnapshot(CaptureStats.Snapshot s) {
        this.snapshot = s;
        redraw();
    }

    @Override public boolean isResizable() { return true; }
    @Override public double prefWidth(double h) { return 400; }
    @Override public double prefHeight(double w) { return 28; }
    @Override public double minHeight(double w)  { return 24; }

    private void redraw() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;

        GraphicsContext g = getGraphicsContext2D();
        g.setFill(BG);
        g.fillRect(0, 0, w, h);

        if (snapshot == null || snapshot.total() <= 0) {
            g.setStroke(BORDER);
            g.setLineWidth(1);
            g.strokeRect(0.5, 0.5, w - 1, h - 1);
            g.setFill(Color.web("#888"));
            g.setFont(Font.font(11));
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.CENTER);
            g.fillText("No frames captured yet.", w / 2, h / 2);
            return;
        }

        long total = snapshot.total();
        Segment[] segs = {
                new Segment("mgmt", snapshot.mgmt(), MGMT),
                new Segment("ctrl", snapshot.ctrl(), CTRL),
                new Segment("data", snapshot.data(), DATA),
                new Segment("ext",  snapshot.ext(),  EXT),
        };

        // Round-aware pixel layout: accumulate fractional widths and
        // round each segment's *cumulative* edge to the nearest pixel,
        // so the segments tile to the bar's full width with no gaps or
        // overlap regardless of accumulation drift.
        double prevEdge = 0;
        g.setFont(Font.font(11));
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < segs.length; i++) {
            double frac = (double) segs[i].count / total;
            double thisEdge = (i == segs.length - 1)
                    ? w
                    : Math.round((prevEdge / w + frac) * w);
            double segW = thisEdge - prevEdge;
            if (segW > 0) {
                g.setFill(segs[i].color);
                g.fillRect(prevEdge, 0, segW, h);
                int pct = (int) Math.round(frac * 100);
                if (segW > 42 && pct >= 1) {
                    String label = segs[i].name + " " + pct + "%";
                    g.setFill(LABEL);
                    g.fillText(label, prevEdge + segW / 2, h / 2);
                } else if (segW > 22 && pct >= 1) {
                    g.setFill(LABEL);
                    g.fillText(pct + "%", prevEdge + segW / 2, h / 2);
                }
            }
            prevEdge = thisEdge;
        }

        g.setStroke(BORDER);
        g.setLineWidth(1);
        g.strokeRect(0.5, 0.5, w - 1, h - 1);
    }

    private record Segment(String name, long count, Color color) {}
}
