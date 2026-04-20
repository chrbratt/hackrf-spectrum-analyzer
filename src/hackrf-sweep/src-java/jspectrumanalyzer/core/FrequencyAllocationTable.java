package jspectrumanalyzer.core;

import java.util.ArrayList;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Domain model for a regional frequency allocation table.
 *
 * <p>Holds a sorted set of {@link FrequencyBand}s and exposes lookup helpers
 * used by the (FX-based) {@code AllocationOverlayCanvas} to render the band
 * stripe over the spectrum chart.
 *
 * <p>This class used to also carry a {@code drawAllocationTable(...)} method
 * that rendered the bands directly into a Swing {@code BufferedImage}. That
 * code path was retired when the overlay moved to JavaFX; the method has been
 * removed (along with its 16 {@code java.awt.*} imports) to keep the domain
 * layer free of UI-toolkit dependencies.
 */
public class FrequencyAllocationTable {
	/**
	 * bands will be sorted in the set by frequency
	 */
	private final TreeSet<FrequencyBand> frequencyBands	= new TreeSet<>();
	private final String area;
	
	public FrequencyAllocationTable(String area, ArrayList<FrequencyBand> bands) {
		this.area	= area;
		this.frequencyBands.addAll(bands);
	}
	
	public FrequencyBand lookupBand(long hz) {
		FrequencyBand band	= frequencyBands.floor(new FrequencyBand(hz, hz, "", ""));
		return band;
	}

	@Override
	public String toString() {
		return area;
	}
	
	/** Total number of bands in the table. Used by the UI to surface
	 *  table size next to the country name in the picker. */
	public int size() {
		return frequencyBands.size();
	}

	public ArrayList<FrequencyBand> getFrequencyBands(long startHz, long endHz){
		ArrayList<FrequencyBand> bands	= new ArrayList<>();
		if (frequencyBands.isEmpty()) {
			return bands;
		}
		// Use a synthetic key for tailSet so we never pass null (which
		// would trip TreeMap.compare() with a NullPointerException). When
		// startHz lies below every band in the table, floor() returns null
		// and we fall back to iterating the whole set from the synthetic
		// key (which compares as the smallest possible band).
		FrequencyBand searchKey = new FrequencyBand(startHz, startHz, "", "");
		FrequencyBand startBand = frequencyBands.floor(searchKey);
		FrequencyBand from = (startBand != null) ? startBand : searchKey;
		SortedSet<FrequencyBand> entries = frequencyBands.tailSet(from);
		for (FrequencyBand frequencyBand : entries) {
			if (frequencyBand.getHzStartIncl() > endHz) {
				break;
			}
			bands.add(frequencyBand);
		}
		return bands;
	}
}
