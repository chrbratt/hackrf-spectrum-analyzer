package jspectrumanalyzer.fx.model;

import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.Property;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import jspectrumanalyzer.core.HackRFSettings;
import shared.mvc.ModelValue;
import shared.mvc.ModelValue.ModelValueBoolean;
import shared.mvc.ModelValue.ModelValueInt;

/**
 * Bridges {@link ModelValue} (used by the core model, {@link HackRFSettings}) with JavaFX
 * {@link Property}.
 * <p>
 * JavaFX counterpart to the old {@code MVCController} binder. A reentrancy flag prevents
 * the infinite feedback loop that would otherwise occur when a model change triggers a
 * property change that in turn tries to update the model.
 */
public final class FxModelBinder {

    private FxModelBinder() {
    }

    public static IntegerProperty asIntegerProperty(ModelValueInt model) {
        IntegerProperty property = new SimpleIntegerProperty(model.getValue());
        bindInt(property, model);
        return property;
    }

    public static BooleanProperty asBooleanProperty(ModelValueBoolean model) {
        BooleanProperty property = new SimpleBooleanProperty(model.getValue());
        bindBoolean(property, model);
        return property;
    }

    public static <T> Property<T> asObjectProperty(ModelValue<T> model) {
        Property<T> property = new SimpleObjectProperty<>(model.getValue());
        bindObject(property, model);
        return property;
    }

    public static void bindInt(IntegerProperty property, ModelValueInt model) {
        final boolean[] updating = {false};
        property.addListener((obs, oldV, newV) -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                if (model.isBounded()) {
                    int clamped = clamp(newV.intValue(), model.getMin(), model.getMax());
                    model.setValue(clamped);
                    if (clamped != newV.intValue()) {
                        property.set(clamped);
                    }
                } else {
                    model.setValue(newV.intValue());
                }
            } finally {
                updating[0] = false;
            }
        });
        model.addListener(() -> runOnFx(() -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                property.set(model.getValue());
            } finally {
                updating[0] = false;
            }
        }));
    }

    public static void bindBoolean(BooleanProperty property, ModelValueBoolean model) {
        final boolean[] updating = {false};
        property.addListener((obs, oldV, newV) -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                model.setValue(newV);
            } finally {
                updating[0] = false;
            }
        });
        model.addListener(() -> runOnFx(() -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                property.set(model.getValue());
            } finally {
                updating[0] = false;
            }
        }));
    }

    public static <T> void bindObject(Property<T> property, ModelValue<T> model) {
        final boolean[] updating = {false};
        property.addListener((obs, oldV, newV) -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                model.setValue(newV);
            } finally {
                updating[0] = false;
            }
        });
        model.addListener(() -> runOnFx(() -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                property.setValue(model.getValue());
            } finally {
                updating[0] = false;
            }
        }));
    }

    /**
     * Bind with value conversion in both directions. Useful for mapping String spinner
     * values to numeric model values.
     */
    public static <V, M> void bindConverted(Property<V> property, ModelValue<M> model,
                                            Function<V, M> toModel, Function<M, V> toView) {
        final boolean[] updating = {false};
        property.addListener((obs, oldV, newV) -> {
            if (updating[0] || newV == null) return;
            updating[0] = true;
            try {
                model.setValue(toModel.apply(newV));
            } finally {
                updating[0] = false;
            }
        });
        model.addListener(() -> runOnFx(() -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                property.setValue(toView.apply(model.getValue()));
            } finally {
                updating[0] = false;
            }
        }));
    }

    private static void runOnFx(Runnable r) {
        if (Platform.isFxApplicationThread()) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
