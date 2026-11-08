/*
 * Copyright (c) 2026, Thiago Sayão. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The copyright holder designates
 * this particular file as subject to the "Classpath" exception as provided
 * in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 */

import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The showcases of the Prism renderer in one window, chosen in the middle of
 * its header bar (or with Ctrl+1 to Ctrl+7):
 * <ul>
 * <li>Nebula: a planet with its moon, a galaxy and a nebula, in 3D;</li>
 * <li>Fractals: Mandelbrot and Julia sets computed in the background, and a
 *     Menger sponge;</li>
 * <li>Fireworks: shells, salvos and smoke over a city, on canvases with a
 *     bloom;</li>
 * <li>Disco: a disco ball, dancers, beams and confetti;</li>
 * <li>Aurora: real-time 3D, charts and controls, in light and dark;</li>
 * <li>Abyss: jellyfish, lanternfish and plankton that glow in the deep sea;</li>
 * <li>Synthwave: a flight down a neon valley whose mountains dance to a beat.</li>
 * </ul>
 * -Deyecandy.show=fractals starts on a candy, and -Deyecandy.snapshot=file.png
 * saves a snapshot of the window after a few seconds and exits.
 */
public class EyeCandyDemo extends Application {

    /**
     * A showcase, which builds a scene for the stage of the demo each time it
     * is chosen.
     */
    interface Candy {
        Scene scene(Stage stage, Node switcher, double width, double height);

        /** Stops what the candy runs besides its loops, which stop anyway. */
        default void dispose() {
        }
    }

    /**
     * The animation loop of a candy, stopped when another candy shows.
     */
    abstract static class Loop extends AnimationTimer {
        @Override
        public void start() {
            LOOPS.add(this);
            super.start();
        }

        @Override
        public void stop() {
            LOOPS.remove(this);
            super.stop();
        }
    }

    private record Entry(String name, Supplier<Candy> create) {}

    private static final List<Loop> LOOPS = new ArrayList<>();
    private static final List<Entry> CANDIES = List.of(
            new Entry("Nebula", Nebula::new),
            new Entry("Fractals", Fractals::new),
            new Entry("Fireworks", Fireworks::new),
            new Entry("Disco", Disco::new),
            new Entry("Aurora", Aurora::new),
            new Entry("Abyss", Abyss::new),
            new Entry("Synthwave", Synthwave::new));

    private static final String CSS = """
        .candies {
            -fx-background-color: rgba(255, 255, 255, 0.07);
            -fx-background-radius: 999;
            -fx-border-color: rgba(255, 255, 255, 0.14);
            -fx-border-radius: 999;
            -fx-padding: 3;
            -fx-spacing: 2;
        }
        .candies .toggle-button {
            -fx-background-color: transparent;
            -fx-background-radius: 999;
            -fx-border-color: transparent;
            -fx-text-fill: rgba(255, 255, 255, 0.78);
            -fx-font-size: 12px;
            -fx-padding: 4 14 4 14;
        }
        .candies .toggle-button:hover { -fx-background-color: rgba(255, 255, 255, 0.10); }
        .candies .toggle-button:selected {
            -fx-background-color: rgba(255, 255, 255, 0.92);
            -fx-text-fill: #0b1120;
            -fx-font-weight: bold;
        }
        /* Over the candies that color every .text */
        .candies .toggle-button .text { -fx-fill: rgba(255, 255, 255, 0.78); }
        .candies .toggle-button:selected .text { -fx-fill: #0b1120; }
        .light .candies { -fx-background-color: rgba(0, 0, 0, 0.05); -fx-border-color: rgba(0, 0, 0, 0.12); }
        .light .candies .toggle-button .text { -fx-fill: rgba(0, 0, 0, 0.7); }
        .light .candies .toggle-button:selected { -fx-background-color: #0b1120; }
        .light .candies .toggle-button:selected .text { -fx-fill: white; }
        """;
    private static final String STYLESHEET = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));

    /** Held, as the levels of loggers no longer referenced are lost. */
    private static final List<Logger> QUIET = new ArrayList<>();

    private Stage stage;
    private Candy current;

    public static void main(String[] args) {
        launch(args);
    }

    /**
     * Without 3D, as with the software pipeline, the 3D parts of the candies
     * do not show and JavaFX warns once for each 3D node: warn once instead.
     */
    private static void quiet3D() {
        if (Platform.isSupported(ConditionalFeature.SCENE3D)) {
            return;
        }
        System.err.println("EyeCandy: this pipeline has no 3D, so the 3D parts of the candies do not show");
        for (String name : List.of("javafx.scene.shape.Shape3D", "javafx.scene.SubScene",
                "javafx.scene.PerspectiveCamera", "javafx.scene.LightBase", "javafx.scene.paint.Material",
                "javafx.scene.shape.Mesh")) {
            Logger logger = Logger.getLogger(name);
            logger.setLevel(Level.SEVERE);
            QUIET.add(logger);
        }
    }

    @Override
    public void start(Stage stage) {
        quiet3D();
        this.stage = stage;
        stage.initStyle(StageStyle.EXTENDED);
        stage.setMinWidth(1100);
        stage.setMinHeight(700);
        String first = System.getProperty("eyecandy.show", CANDIES.getFirst().name());
        int index = 0;
        for (int i = 0; i < CANDIES.size(); i++) {
            if (CANDIES.get(i).name().equalsIgnoreCase(first)) {
                index = i;
            }
        }
        show(index);
        stage.show();

        String snapshot = System.getProperty("eyecandy.snapshot");
        if (snapshot != null) {
            PauseTransition wait = new PauseTransition(Duration.seconds(8));
            wait.setOnFinished(e -> {
                try {
                    javax.imageio.ImageIO.write(javafx.embed.swing.SwingFXUtils.fromFXImage(
                            stage.getScene().snapshot(null), null), "png", new java.io.File(snapshot));
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                Platform.exit();
            });
            wait.play();
        }
    }

    @Override
    public void stop() {
        dispose();
    }

    /**
     * Shows a candy in a new scene of the stage, of the size of the window.
     */
    private void show(int index) {
        dispose();
        double width;
        double height;
        if (stage.getScene() != null) {
            width = stage.getScene().getWidth();
            height = stage.getScene().getHeight();
        } else {
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            width = Math.max(1200, Math.min(1760, screen.getWidth() * 0.88));
            height = Math.max(760, Math.min(1100, screen.getHeight() * 0.9));
        }
        Entry entry = CANDIES.get(index);
        current = entry.create().get();
        Scene scene = current.scene(stage, switcher(index), width, height);
        scene.getStylesheets().add(STYLESHEET);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            int n = e.getCode().ordinal() - KeyCode.DIGIT1.ordinal();
            if (e.isShortcutDown() && n >= 0 && n < CANDIES.size() && n != index) {
                e.consume();
                show(n);
            }
        });
        stage.setScene(scene);
        stage.setTitle(entry.name() + " — Eye candy");
    }

    private void dispose() {
        if (current != null) {
            current.dispose();
            current = null;
        }
        for (Loop loop : new ArrayList<>(LOOPS)) {
            loop.stop();
        }
    }

    /**
     * The choice of the candies, for the header bar of a candy.
     */
    private Node switcher(int selected) {
        HBox box = new HBox();
        box.getStyleClass().add("candies");
        box.setAlignment(Pos.CENTER);
        box.setMaxSize(HBox.USE_PREF_SIZE, HBox.USE_PREF_SIZE);
        ToggleGroup group = new ToggleGroup();
        for (int i = 0; i < CANDIES.size(); i++) {
            int index = i;
            ToggleButton b = new ToggleButton(CANDIES.get(i).name());
            b.setToggleGroup(group);
            b.setSelected(i == selected);
            b.setFocusTraversable(false);
            b.setOnAction(e -> {
                if (index == selected) {
                    b.setSelected(true);
                } else {
                    // After the event, which the old scene still dispatches
                    Platform.runLater(() -> show(index));
                }
            });
            b.setAccessibleText(CANDIES.get(i).name().toLowerCase(Locale.ROOT));
            box.getChildren().add(b);
        }
        return box;
    }
}
