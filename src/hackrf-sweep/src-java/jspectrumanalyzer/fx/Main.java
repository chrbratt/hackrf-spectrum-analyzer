package jspectrumanalyzer.fx;

import javafx.application.Application;

/**
 * Non-{@link Application} entry point. jpackage puts JavaFX jars on the classpath
 * rather than the module path. In that configuration the Java launcher refuses to
 * start a main class that extends {@link Application} directly; routing through
 * this wrapper sidesteps the check.
 */
public final class Main {
	private Main() {}

	public static void main(String[] args) {
		Application.launch(FxApp.class, args);
	}
}
