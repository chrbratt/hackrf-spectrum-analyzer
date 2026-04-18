package jspectrumanalyzer.ui;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.Transparency;
import java.awt.image.BufferedImage;

/**
 * Tiny helper for allocating GPU-accelerated AWT {@link BufferedImage}s.
 *
 * <p>Used by the spectrum waterfall ring buffer ({@code WaterfallCanvas}) and
 * the persistent-display heatmap ({@code PersistentDisplay}); both keep an
 * AWT image for raw {@code int[]}-pixel access and bridge into JavaFX via
 * {@code SwingFXUtils.toFXImage(...)}. Going via the default
 * {@link GraphicsConfiguration} lets the JDK pick a display-compatible pixel
 * layout, which avoids per-frame format conversion when we hand the image
 * off to FX.
 *
 * <p>Only the opaque variant is exposed - the previous {@code transparent}
 * variant was used by the old Swing allocation-overlay renderer, which has
 * since been replaced by an FX {@code Canvas} and removed.
 */
public class GraphicsToolkit {

	public static BufferedImage createAcceleratedImageOpaque(int width, int height) {
		GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
		GraphicsConfiguration gc = ge.getDefaultScreenDevice().getDefaultConfiguration();
		BufferedImage image = gc.createCompatibleImage(width, height, Transparency.OPAQUE);
		image.setAccelerationPriority(1);
		return image;
	}
}
