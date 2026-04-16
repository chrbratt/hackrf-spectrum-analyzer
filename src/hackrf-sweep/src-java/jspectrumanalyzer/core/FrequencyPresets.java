package jspectrumanalyzer.core;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FrequencyPresets {
	private static final String RESOURCE_PATH = "/presets.csv";

	private List<Preset> presets;

	public FrequencyPresets() throws FileNotFoundException {
		InputStream is = FrequencyPresets.class.getResourceAsStream(RESOURCE_PATH);
		if (is == null) {
			throw new FileNotFoundException(
					"Classpath resource '" + RESOURCE_PATH + "' not found. "
							+ "Check that src/hackrf-sweep/presets.csv is on the resource path.");
		}
		loadTableFromCSV("presets", is);
	}

	public List<Preset> getList() {
		return presets;
	}

	private void loadTableFromCSV(String tableName, InputStream is) {
		BufferedReader reader = null;
		presets = new ArrayList<>();
		try {
			reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
			String line = null;
			int lineNo = 0;
			while ((line = reader.readLine()) != null) {
				lineNo++;
				if (lineNo == 1)
					continue;
				String[] s = line.split(";");
				Preset preset = new Preset(s[0], Integer.parseInt(s[1]), Integer.parseInt(s[2]), String.valueOf(s[3]), String.valueOf(s[4]), Integer.parseInt(s[5]));
				presets.add(preset);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return;
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException e) {
				}
			}
		}
	}
}
