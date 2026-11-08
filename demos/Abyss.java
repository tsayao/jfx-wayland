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

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.ColorScheme;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.effect.Glow;
import javafx.scene.effect.Reflection;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.HeaderBar;
import javafx.scene.layout.HeaderDragType;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The deep sea drawn on canvases with the Prism renderer: bioluminescent
 * jellyfish with tentacles of Verlet chains, a school of lanternfish that
 * flocks as boids, and thousands of plankton that light up where the water is
 * stirred, by the pointer, the fish, the jellyfish or a sonar pulse. The
 * deeper the dive, the darker the water and the brighter the life.
 */
public class Abyss implements EyeCandyDemo.Candy {

    private static final String CSS = """
        .root {
            -fx-font-family: "Adwaita Sans", "Cantarell", "System";
            -fx-font-size: 13px;
            -fx-base: #0d2230;
            -fx-background: transparent;
            -fx-control-inner-background: #081822;
            -fx-accent: #2ee6d6;
            -fx-focus-color: #7c8bff;
            -fx-faint-focus-color: #7c8bff22;
            -fx-text-fill: #e4fbff;
        }
        .label, .check-box { -fx-text-fill: #e4fbff; }
        .muted { -fx-text-fill: #84a8b8; }
        .mono { -fx-font-family: "Adwaita Mono", "Monospace"; }
        .glass {
            -fx-background-color: rgba(2, 14, 24, 0.5);
            -fx-background-radius: 18;
            -fx-border-color: rgba(255, 255, 255, 0.16) rgba(255, 255, 255, 0.07)
                rgba(255, 255, 255, 0.04) rgba(255, 255, 255, 0.07);
            -fx-border-radius: 18;
            -fx-padding: 16;
        }
        .section { -fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #84a8b8; -fx-padding: 8 0 0 0; }
        .pill {
            -fx-background-color: rgba(255, 255, 255, 0.07);
            -fx-background-radius: 999;
            -fx-border-color: rgba(255, 255, 255, 0.14);
            -fx-border-radius: 999;
            -fx-padding: 5 12 5 12;
            -fx-spacing: 7;
            -fx-alignment: center;
        }
        .pill .label { -fx-font-size: 12px; }
        .brand { -fx-font-size: 15px; -fx-font-weight: bold; }
        .stat { -fx-font-size: 20px; -fx-font-weight: bold; }
        .stat-key { -fx-font-size: 10px; -fx-text-fill: #84a8b8; }
        .check-box > .box {
            -fx-background-color: rgba(255, 255, 255, 0.08);
            -fx-background-radius: 5;
            -fx-border-color: rgba(255, 255, 255, 0.22);
            -fx-border-radius: 5;
        }
        .check-box:selected > .box {
            -fx-background-color: linear-gradient(to bottom right, #2ee6d6, #3e8bff, #c86bff);
            -fx-border-color: transparent;
        }
        .check-box:selected > .box > .mark { -fx-background-color: white; }
        .slider .track { -fx-background-color: rgba(255, 255, 255, 0.14); -fx-background-radius: 4; }
        .slider .thumb {
            -fx-background-color: white;
            -fx-effect: dropshadow(gaussian, #2ee6d6, 10, 0.3, 0, 0);
        }
        .button {
            -fx-background-color: rgba(255, 255, 255, 0.08);
            -fx-background-radius: 9;
            -fx-border-color: rgba(255, 255, 255, 0.14);
            -fx-border-radius: 9;
            -fx-text-fill: #e4fbff;
            -fx-padding: 6 14 6 14;
            -fx-cursor: hand;
        }
        .button:hover { -fx-background-color: rgba(255, 255, 255, 0.15); }
        .button.accent {
            -fx-background-color: linear-gradient(to right, #2ee6d6, #3e8bff, #c86bff);
            -fx-border-color: transparent;
            -fx-text-fill: white;
            -fx-font-weight: bold;
        }
        .swatch { -fx-cursor: hand; }
        .scroll-pane, .scroll-pane > .viewport { -fx-background-color: transparent; }
        .scroll-pane { -fx-padding: 0; }
        .scroll-bar { -fx-background-color: transparent; }
        .scroll-bar .thumb { -fx-background-color: rgba(255, 255, 255, 0.25); -fx-background-radius: 6; }
        .scroll-bar .increment-button, .scroll-bar .decrement-button { -fx-padding: 0; }
        .scroll-bar .increment-arrow, .scroll-bar .decrement-arrow { -fx-shape: " "; -fx-padding: 0; }
        """;

    private static final String STYLESHEET = "data:text/css;base64,"
            + Base64.getEncoder().encodeToString(CSS.getBytes(StandardCharsets.UTF_8));

    private static final int MAX_FISH = 400;
    private static final int MAX_PLANKTON = 9000;
    private static final int MAX_JELLIES = 16;
    private static final int SNOW = 260;
    private static final int RAYS = 7;
    private static final int KELP = 12;
    private static final int VENTS = 16;
    private static final int TENTACLES = 9;
    private static final int ARMS = 4;
    private static final int GRAPH = 160;
    private static final double GRAPH_W = 268;
    private static final double GRAPH_H = 56;
    private static final double CELL = 36;
    private static final double FLOOR = 0.9;
    private static final double PULSE_LIFE = 1.8;

    /** The colors of the light of the life down there. */
    private static final Color[] GLOWS = {
        Color.web("#3ef2ff"), Color.web("#2effb4"), Color.web("#7c8bff"),
        Color.web("#c86bff"), Color.web("#ff6bd5"), Color.web("#ffd36b")
    };
    private static final String[] GLOW_NAMES = {"Cyan", "Emerald", "Periwinkle", "Violet", "Rose", "Amber"};

    /**
     * A rope of Verlet points, pinned at its first point and kept at the length
     * of its segments by following the leader.
     */
    private static final class Chain {
        final double[] x;
        final double[] y;
        final double[] ox;
        final double[] oy;
        final double length;

        Chain(int points, double length, double sx, double sy) {
            x = new double[points];
            y = new double[points];
            ox = new double[points];
            oy = new double[points];
            this.length = length;
            reset(sx, sy);
        }

        void reset(double sx, double sy) {
            for (int i = 0; i < x.length; i++) {
                x[i] = ox[i] = sx;
                y[i] = oy[i] = sy + i * length;
            }
        }

        void step(double px, double py, double damping, double fx, double fy) {
            x[0] = ox[0] = px;
            y[0] = oy[0] = py;
            for (int i = 1; i < x.length; i++) {
                double vx = (x[i] - ox[i]) * damping;
                double vy = (y[i] - oy[i]) * damping;
                ox[i] = x[i];
                oy[i] = y[i];
                x[i] += vx + fx;
                y[i] += vy + fy;
            }
            for (int i = 1; i < x.length; i++) {
                double dx = x[i] - x[i - 1];
                double dy = y[i] - y[i - 1];
                double d = Math.max(1e-6, Math.hypot(dx, dy));
                x[i] = x[i - 1] + dx / d * length;
                y[i] = y[i - 1] + dy / d * length;
            }
        }
    }

    /** A jellyfish, which swims by the pulses of its bell. */
    private static final class Jelly {
        double x;
        double y;
        double vx;
        double vy;
        double angle;
        double radius;
        double phase;
        double period;
        double flash;
        double contract;
        double targetX;
        double targetY;
        double seed;
        int color;
        final Chain[] tentacles = new Chain[TENTACLES];
        final Chain[] arms = new Chain[ARMS];
    }

    private Stage stage;
    private Scene scene;
    private boolean backdrop;
    private boolean paused;
    private double time;
    private double unit = 1;
    private double width;
    private double height;
    private final Random random = new Random(1977);

    private final Canvas back = new Canvas();
    private final Canvas glow = new Canvas();
    private Image[] halos;
    private Image[] dots;
    private Image snowSprite;

    private final List<Jelly> jellies = new ArrayList<>();

    private final double[] fx = new double[MAX_FISH];
    private final double[] fy = new double[MAX_FISH];
    private final double[] fvx = new double[MAX_FISH];
    private final double[] fvy = new double[MAX_FISH];
    private final double[] fseed = new double[MAX_FISH];
    private int fishCount;

    private final float[] px = new float[MAX_PLANKTON];
    private final float[] py = new float[MAX_PLANKTON];
    private final float[] pglow = new float[MAX_PLANKTON];
    private final float[] pseed = new float[MAX_PLANKTON];
    private int planktonCount;
    private int lit;

    private final double[][] snow = new double[SNOW][3];
    private final double[][] rays = new double[RAYS][3];
    private final double[][] kelp = new double[KELP][3];
    private final double[][] vents = new double[VENTS][3];
    private final List<double[]> bubbles = new ArrayList<>();
    private final List<double[]> pulses = new ArrayList<>();

    private float[] stir = new float[0];
    private int cols;
    private int rows;

    private Point2D mouse;
    private Point2D lastMouse;
    private int planktonColor;
    private boolean seeded;

    private final CheckBox raysOn = new CheckBox("Light rays");
    private final CheckBox planktonOn = new CheckBox("Plankton");
    private final CheckBox fishOn = new CheckBox("Lanternfish school");
    private final CheckBox jelliesOn = new CheckBox("Jellyfish");
    private final CheckBox floorOn = new CheckBox("Seafloor and kelp");
    private final CheckBox snowOn = new CheckBox("Marine snow");
    private final CheckBox bubblesOn = new CheckBox("Bubbles");
    private final CheckBox bloom = new CheckBox("Bloom (Decora)");
    private final Slider depth = new Slider(0, 1000, 420);
    private final Slider current = new Slider(0, 2, 0.7);
    private final Slider timeScale = new Slider(0, 3, 1);
    private final Slider tint = new Slider(0, 0.9, 0.35);
    private final Slider jellyCount = new Slider(0, MAX_JELLIES, 7);
    private final Slider fishSlider = new Slider(0, MAX_FISH, 220);
    private final Slider planktonSlider = new Slider(0, MAX_PLANKTON, 5000);

    private final Label fpsValue = new Label("–");
    private final Label frameValue = new Label("–");
    private final Label worstValue = new Label("–");
    private final Label depthValue = new Label("–");
    private final Label litValue = new Label("–");
    private final Label jellyValue = new Label("–");
    private final Label sizeValue = new Label("–");
    private final Polyline frameLine = new Polyline();
    private final Polygon frameArea = new Polygon();
    private final double[] frameTimes = new double[GRAPH];
    private int frameHead;
    private final Region fill = new Region();
    private final Text title = new Text("ABYSS");
    private final Group logo = new Group();
    private final List<Circle> swatches = new ArrayList<>();
    private Node switcher;
    private Node panels;

    @Override
    public Scene scene(Stage stage, Node switcher, double width, double height) {
        this.stage = stage;
        this.switcher = switcher;
        backdrop = Boolean.getBoolean("glass.gtk4.backdrop.active");
        for (CheckBox c : List.of(raysOn, planktonOn, fishOn, jelliesOn, floorOn, snowOn, bubblesOn, bloom)) {
            c.setSelected(true);
        }
        for (Slider s : List.of(jellyCount, fishSlider, planktonSlider)) {
            s.setMajorTickUnit(1);
            s.setSnapToTicks(false);
        }
        updateFill();
        depth.valueProperty().addListener(o -> updateFill());
        tint.valueProperty().addListener(o -> updateFill());
        tint.setDisable(!backdrop);
        makeSprites();

        Bloom glowBloom = new Bloom(0.35);
        glow.effectProperty().bind(Bindings.when(bloom.selectedProperty())
                .then((Effect) glowBloom).otherwise((Effect) null));
        Pane canvases = new Pane(back, glow);
        for (Canvas c : List.of(back, glow)) {
            c.setManaged(false);
            c.setMouseTransparent(true);
            c.widthProperty().bind(canvases.widthProperty());
            c.heightProperty().bind(canvases.heightProperty());
        }
        canvases.setMinSize(0, 0);

        BorderPane overlay = new BorderPane();
        overlay.setTop(header());
        overlay.setCenter(panels());
        overlay.setPickOnBounds(false);

        StackPane root = new StackPane(fill, canvases, overlay);
        root.setMinSize(0, 0);
        scene = new Scene(root, width, height, Color.TRANSPARENT);
        scene.getStylesheets().add(STYLESHEET);
        scene.getPreferences().setColorScheme(ColorScheme.DARK);
        mouse(root);
        keys();
        startLoop();
        return scene;
    }

    // The light: how much of the sun reaches the depth, 1 at the surface
    private double light() {
        return Math.clamp(1 - depth.getValue() / 650, 0, 1);
    }

    private void updateFill() {
        double d = depth.getValue() / 1000;
        Color top = Color.web("#1b7fa8").interpolate(Color.web("#020a16"), Math.pow(d, 0.6));
        Color mid = Color.web("#0b4766").interpolate(Color.web("#01050d"), Math.pow(d, 0.7));
        Color bottom = Color.web("#04243a").interpolate(Color.web("#000104"), d);
        double a = backdrop ? tint.getValue() : 1;
        fill.setBackground(new Background(new BackgroundFill(new LinearGradient(0, 0, 0, 1, true,
                CycleMethod.NO_CYCLE,
                new Stop(0, alpha(top, a)), new Stop(0.5, alpha(mid, a)),
                new Stop(1, alpha(bottom, backdrop ? Math.min(1, a + 0.35) : 1))), null, null)));
    }

    private static Color alpha(Color c, double a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.clamp(a, 0, 1));
    }

    private static String pipelineName() {
        String name = com.sun.prism.GraphicsPipeline.getPipeline().getClass().getSimpleName();
        return switch (name) {
            case "VKPipeline" -> "Vulkan";
            case "ES2Pipeline" -> "OpenGL ES";
            case "SWPipeline" -> "Software";
            default -> name;
        };
    }

    // ---------------------------------------------------------------- chrome

    private Node header() {
        Label brand = new Label("Abyss");
        brand.getStyleClass().add("brand");
        Label sub = new Label("Prism renderer showcase");
        sub.getStyleClass().add("muted");
        HBox left = new HBox(10, logo(), brand, sub);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setPadding(new Insets(0, 0, 0, 18));

        HBox live = new HBox(pulsingDot(), fpsLabel());
        live.getStyleClass().add("pill");
        HBox pipeline = new HBox(new Label(pipelineName() + " · GTK 4"));
        pipeline.getStyleClass().add("pill");
        HBox glass = new HBox(new Label(backdrop ? "Backdrop blur" : "Opaque"));
        glass.getStyleClass().add("pill");
        Button sonar = new Button("Sonar  S");
        sonar.getStyleClass().add("accent");
        sonar.setOnAction(e -> pulse(width / 2, height / 2));
        Button hide = new Button("Panels  H");
        hide.setOnAction(e -> togglePanels());

        HBox right = new HBox(10, live, pipeline, glass, sonar, hide);
        right.setAlignment(Pos.CENTER_RIGHT);
        right.setPadding(new Insets(0, 10, 0, 0));
        for (Node n : List.of(left, live, pipeline, glass)) {
            HeaderBar.setDragType(n, HeaderDragType.DRAGGABLE_SUBTREE);
        }
        HeaderBar.setDragType(right, HeaderDragType.DRAGGABLE);

        HeaderBar header = new HeaderBar(left, switcher, right);
        header.setBackground(null);
        header.setMinHeight(54);
        HeaderBar.setAlignment(left, Pos.CENTER_LEFT);
        return header;
    }

    // A little jellyfish
    private Node logo() {
        Arc bell = new Arc(0, 0, 9, 8, 0, 180);
        bell.setType(ArcType.CHORD);
        bell.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#c86bff")), new Stop(1, Color.web("#3ef2ff"))));
        logo.getChildren().add(bell);
        for (int i = -1; i <= 1; i++) {
            Polyline t = new Polyline(i * 5, 0, i * 5 + 2, 4, i * 5 - 1, 8, i * 5 + 1, 12);
            t.setStroke(Color.web("#3ef2ff"));
            t.setStrokeWidth(1.4);
            t.setStrokeLineCap(StrokeLineCap.ROUND);
            logo.getChildren().add(t);
        }
        logo.setEffect(new Glow(0.9));
        return logo;
    }

    private static Node pulsingDot() {
        Circle dot = new Circle(4, Color.web("#2ee6d6"));
        dot.setEffect(new DropShadow(8, Color.web("#2ee6d6")));
        FadeTransition pulse = new FadeTransition(Duration.millis(1100), dot);
        pulse.setFromValue(1);
        pulse.setToValue(0.3);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
        return dot;
    }

    private Label fpsLabel() {
        Label fps = new Label();
        fps.getStyleClass().add("mono");
        fps.textProperty().bind(fpsValue.textProperty().concat(" fps"));
        return fps;
    }

    private void togglePanels() {
        panels.setVisible(!panels.isVisible());
    }

    private Node panels() {
        AnchorPane area = new AnchorPane();
        area.setPickOnBounds(false);

        Node hud = hud();
        AnchorPane.setTopAnchor(hud, 4.0);
        AnchorPane.setLeftAnchor(hud, 18.0);

        ScrollPane controls = new ScrollPane(controls());
        controls.setFitToWidth(true);
        controls.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        controls.getStyleClass().add("glass");
        controls.setPadding(new Insets(14, 6, 14, 16));
        controls.setPrefWidth(300);
        AnchorPane.setTopAnchor(controls, 4.0);
        AnchorPane.setRightAnchor(controls, 18.0);
        AnchorPane.setBottomAnchor(controls, 18.0);

        area.getChildren().addAll(hud, controls);
        panels = area;
        return area;
    }

    private Node hud() {
        title.setFont(Font.font("Adwaita Sans", FontWeight.BLACK, 54));
        Reflection reflection = new Reflection(0, 0.45, 0.4, 0);
        reflection.setInput(new DropShadow(BlurType.GAUSSIAN, Color.web("#2ee6d6cc"), 28, 0.25, 0, 0));
        title.setEffect(reflection);
        Label subtitle = new Label("Prism · " + pipelineName() + " · move to stir the water, click for sonar");
        subtitle.getStyleClass().add("muted");

        GridPane stats = new GridPane(22, 10);
        stats.add(stat("FPS", fpsValue), 0, 0);
        stats.add(stat("Frame ms", frameValue), 1, 0);
        stats.add(stat("Worst ms", worstValue), 2, 0);
        stats.add(stat("Depth", depthValue), 0, 1);
        stats.add(stat("Glowing", litValue), 1, 1);
        stats.add(stat("Jellyfish", jellyValue), 2, 1);

        sizeValue.getStyleClass().addAll("muted", "mono");
        VBox card = new VBox(12, stats, graph(), sizeValue);
        card.getStyleClass().add("glass");
        card.setMaxWidth(Region.USE_PREF_SIZE);

        VBox hud = new VBox(4, title, subtitle, spacer(10), card);
        hud.setPickOnBounds(false);
        return hud;
    }

    private Node graph() {
        Pane graph = new Pane();
        graph.setPrefSize(GRAPH_W, GRAPH_H);
        graph.setMinSize(GRAPH_W, GRAPH_H);
        graph.setMaxSize(GRAPH_W, GRAPH_H);
        double target = GRAPH_H - GRAPH_H * 16.7 / 40;
        Line limit = new Line(0, target, GRAPH_W, target);
        limit.setStroke(Color.color(1, 1, 1, 0.3));
        limit.getStrokeDashArray().addAll(4.0, 4.0);
        frameArea.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#2ee6d666")), new Stop(1, Color.web("#3e8bff00"))));
        frameLine.setStroke(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#2ee6d6")), new Stop(0.5, Color.web("#3e8bff")), new Stop(1, Color.web("#c86bff"))));
        frameLine.setStrokeWidth(1.6);
        frameLine.setStrokeLineJoin(StrokeLineJoin.ROUND);
        Text limitLabel = new Text(GRAPH_W - 52, target - 4, "16.7 ms");
        limitLabel.setFill(Color.color(1, 1, 1, 0.45));
        limitLabel.setFont(Font.font("Adwaita Mono", 10));
        graph.getChildren().addAll(frameArea, frameLine, limit, limitLabel);
        return graph;
    }

    private static Region spacer(double h) {
        Region r = new Region();
        r.setMinHeight(h);
        return r;
    }

    private static VBox stat(String key, Label value) {
        value.getStyleClass().addAll("stat", "mono");
        value.setMinWidth(80);
        Label k = new Label(key.toUpperCase(Locale.ROOT));
        k.getStyleClass().add("stat-key");
        return new VBox(0, k, value);
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section");
        return l;
    }

    private static Node sliderRow(String name, Slider slider, String format) {
        Label value = new Label();
        value.getStyleClass().addAll("muted", "mono");
        value.textProperty().bind(Bindings.format(Locale.ROOT, format, slider.valueProperty()));
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        return new VBox(4, new HBox(new Label(name), gap, value), slider);
    }

    private Node controls() {
        Button jelly = new Button("Jellyfish  J");
        jelly.getStyleClass().add("accent");
        jelly.setOnAction(e -> jellyCount.setValue(jellyCount.getValue() + 1));
        Button sonar = new Button("Sonar");
        sonar.setOnAction(e -> pulse(width / 2, height / 2));
        Button bubble = new Button("Bubbles");
        bubble.setOnAction(e -> bubbleBurst(width / 2, height * FLOOR));
        HBox buttons = new HBox(8, jelly, sonar, bubble);

        Label hint = new Label("Move to stir the plankton · click for sonar\n"
                + "Right click for bubbles · ↑ ↓ dive · J jellyfish\nH panels · F full screen · Space pause");
        hint.getStyleClass().add("muted");

        VBox box = new VBox(8,
                section("DIVE"),
                sliderRow("Depth", depth, "%.0f m"),
                sliderRow("Current", current, "%.2f×"),
                sliderRow("Time scale", timeScale, "%.2f×"),
                section("LIFE"),
                sliderRow("Jellyfish", jellyCount, "%.0f"),
                sliderRow("Lanternfish", fishSlider, "%.0f"),
                sliderRow("Plankton", planktonSlider, "%,.0f"),
                section("BIOLUMINESCENCE"), swatches(),
                section("LAYERS"), raysOn, planktonOn, fishOn, jelliesOn, floorOn, snowOn, bubblesOn, bloom,
                section("WINDOW"),
                sliderRow("Backdrop tint", tint, "%.2f"),
                spacer(4), buttons, spacer(4), hint);
        box.setPadding(new Insets(0, 12, 0, 0));
        return box;
    }

    // The color of the plankton, one swatch for each glow
    private Node swatches() {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < GLOWS.length; i++) {
            int index = i;
            Circle c = new Circle(9, new RadialGradient(0, 0, 0.35, 0.35, 0.7, true, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.WHITE), new Stop(1, GLOWS[i])));
            c.setEffect(new DropShadow(BlurType.GAUSSIAN, GLOWS[i], 10, 0.4, 0, 0));
            c.getStyleClass().add("swatch");
            c.setAccessibleText(GLOW_NAMES[i]);
            c.setOnMouseClicked(e -> choosePlankton(index));
            swatches.add(c);
            row.getChildren().add(c);
        }
        choosePlankton(0);
        return row;
    }

    private void choosePlankton(int index) {
        planktonColor = index;
        for (int i = 0; i < swatches.size(); i++) {
            Circle c = swatches.get(i);
            c.setStroke(i == index ? Color.WHITE : Color.TRANSPARENT);
            c.setStrokeWidth(2);
        }
    }

    // ----------------------------------------------------------------- input

    private void keys() {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (key(e.getCode())) {
                e.consume();
            }
        });
    }

    private boolean key(KeyCode code) {
        switch (code) {
            case H -> togglePanels();
            case F, F11 -> stage.setFullScreen(!stage.isFullScreen());
            case SPACE, P -> paused = !paused;
            case J -> jellyCount.setValue(jellyCount.getValue() + 1);
            case S -> pulse(width / 2, height / 2);
            case B -> bubbleBurst(width / 2, height * FLOOR);
            case UP -> depth.setValue(depth.getValue() - 50);
            case DOWN -> depth.setValue(depth.getValue() + 50);
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean onChrome(Object target) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n instanceof HeaderBar || n.getStyleClass().contains("glass")) {
                return true;
            }
        }
        return false;
    }

    private void mouse(Node root) {
        root.addEventHandler(MouseEvent.MOUSE_MOVED, e -> mouse = point(e));
        root.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> mouse = point(e));
        root.addEventHandler(MouseEvent.MOUSE_EXITED, e -> mouse = null);
        root.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (onChrome(e.getTarget())) {
                return;
            }
            Point2D p = point(e);
            if (e.getButton() == MouseButton.SECONDARY || e.isShiftDown()) {
                bubbleBurst(p.getX(), p.getY());
            } else {
                pulse(p.getX(), p.getY());
            }
        });
    }

    private Point2D point(MouseEvent e) {
        return glow.sceneToLocal(e.getSceneX(), e.getSceneY());
    }

    private void pulse(double x, double y) {
        pulses.add(new double[] {x, y, 0});
    }

    private void bubbleBurst(double x, double y) {
        for (int i = 0; i < 26; i++) {
            bubbles.add(bubble(x + gauss() * 14 * unit, y + gauss() * 10 * unit, 2 + random.nextDouble() * 7));
        }
    }

    private double[] bubble(double x, double y, double radius) {
        return new double[] {x, y, radius, random.nextDouble() * Math.PI * 2};
    }

    private double gauss() {
        return random.nextGaussian();
    }

    // ------------------------------------------------------------- the world

    private static Image sprite(Color color, int size, double sharpness, boolean core) {
        WritableImage image = new WritableImage(size, size);
        PixelWriter pw = image.getPixelWriter();
        double h = (size - 1) / 2.0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = (x - h) / h;
                double dy = (y - h) / h;
                double d2 = dx * dx + dy * dy;
                double a = d2 >= 1 ? 0 : Math.exp(-d2 * sharpness) * (1 - d2);
                Color c = core ? color.interpolate(Color.WHITE, Math.exp(-d2 * sharpness * 5)) : color;
                pw.setColor(x, y, alpha(c, Math.min(1, a)));
            }
        }
        return image;
    }

    private void makeSprites() {
        halos = new Image[GLOWS.length];
        dots = new Image[GLOWS.length];
        for (int i = 0; i < GLOWS.length; i++) {
            halos[i] = sprite(GLOWS[i], 128, 3.2, false);
            dots[i] = sprite(GLOWS[i], 32, 5, true);
        }
        snowSprite = sprite(Color.web("#dff4ff"), 12, 4, false);
    }

    // The world, made when the canvases first have a size
    private void seed() {
        for (double[] s : snow) {
            s[0] = random.nextDouble() * width;
            s[1] = random.nextDouble() * height;
            s[2] = 0.5 + random.nextDouble();
        }
        for (int i = 0; i < RAYS; i++) {
            rays[i][0] = (i + 0.5 + gauss() * 0.15) / RAYS;
            rays[i][1] = 0.025 + random.nextDouble() * 0.05;
            rays[i][2] = random.nextDouble() * Math.PI * 2;
        }
        for (int i = 0; i < KELP; i++) {
            kelp[i][0] = (i + 0.3 + random.nextDouble() * 0.4) / KELP;
            kelp[i][1] = 0.18 + random.nextDouble() * 0.3;
            kelp[i][2] = random.nextDouble() * Math.PI * 2;
        }
        for (double[] v : vents) {
            v[0] = random.nextDouble();
            v[1] = random.nextInt(GLOWS.length);
            v[2] = random.nextDouble() * Math.PI * 2;
        }
        seeded = true;
    }

    private void resize(double w, double h) {
        if (w == width && h == height) {
            return;
        }
        width = w;
        height = h;
        unit = Math.max(0.5, h / 1000);
        cols = (int) Math.ceil(w / CELL) + 1;
        rows = (int) Math.ceil(h / CELL) + 1;
        stir = new float[cols * rows];
    }

    private double floorY(double x) {
        return height * FLOOR + Math.sin(x * 0.004 + 1) * height * 0.025 + Math.sin(x * 0.011) * height * 0.012;
    }

    // The flow of the water at a point, in pixels per second
    private double flowX(double x, double y) {
        double c = current.getValue();
        return c * unit * (22 + 18 * Math.sin(y * 0.0031 + time * 0.21) + 10 * Math.sin(x * 0.002 + y * 0.004 + time * 0.4));
    }

    private double flowY(double x, double y) {
        return current.getValue() * unit * 9 * Math.cos(x * 0.0027 - time * 0.33);
    }

    // The stirring of the water, which lights the plankton
    private void stirAt(double x, double y, double amount) {
        int cx = (int) (x / CELL);
        int cy = (int) (y / CELL);
        if (cx < 0 || cy < 0 || cx >= cols || cy >= rows) {
            return;
        }
        int i = cy * cols + cx;
        stir[i] = (float) Math.min(1, stir[i] + amount);
    }

    private float stirOf(double x, double y) {
        int cx = (int) (x / CELL);
        int cy = (int) (y / CELL);
        return cx < 0 || cy < 0 || cx >= cols || cy >= rows ? 0 : stir[cy * cols + cx];
    }

    // ------------------------------------------------------------ simulation

    private void step(double dt) {
        float fade = (float) Math.exp(-dt * 3.5);
        for (int i = 0; i < stir.length; i++) {
            stir[i] *= fade;
        }
        stirByMouse(dt);
        stepPulses(dt);
        syncLife();
        stepJellies(dt);
        stepFish(dt);
        stepPlankton(dt);
        stepSnow(dt);
        stepBubbles(dt);
    }

    private void stirByMouse(double dt) {
        if (mouse != null && lastMouse != null) {
            double d = mouse.distance(lastMouse);
            double amount = Math.clamp(d / dt / 1400, 0, 1);
            int samples = (int) Math.ceil(d / (CELL / 2)) + 1;
            for (int s = 0; s <= samples; s++) {
                double t = s / (double) samples;
                double x = lastMouse.getX() + (mouse.getX() - lastMouse.getX()) * t;
                double y = lastMouse.getY() + (mouse.getY() - lastMouse.getY()) * t;
                stirAround(x, y, amount);
            }
        }
        lastMouse = mouse;
    }

    private void stirAround(double x, double y, double amount) {
        stirAt(x, y, amount);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx != 0 || dy != 0) {
                    stirAt(x + dx * CELL, y + dy * CELL, amount * 0.45);
                }
            }
        }
    }

    private double pulseRadius(double[] p) {
        return p[2] * 620 * unit;
    }

    private void stepPulses(double dt) {
        pulses.removeIf(p -> (p[2] += dt) > PULSE_LIFE);
        for (double[] p : pulses) {
            double r = pulseRadius(p);
            double strength = 1 - p[2] / PULSE_LIFE;
            for (int cy = 0; cy < rows; cy++) {
                for (int cx = 0; cx < cols; cx++) {
                    double d = Math.hypot((cx + 0.5) * CELL - p[0], (cy + 0.5) * CELL - p[1]);
                    if (Math.abs(d - r) < CELL) {
                        int i = cy * cols + cx;
                        stir[i] = (float) Math.max(stir[i], strength);
                    }
                }
            }
            for (Jelly j : jellies) {
                if (Math.abs(Math.hypot(j.x - p[0], j.y - p[1]) - r) < j.radius) {
                    j.flash = 1;
                }
            }
        }
    }

    // The numbers of the sliders
    private void syncLife() {
        int jn = (int) jellyCount.getValue();
        while (jellies.size() < jn) {
            jellies.add(newJelly());
        }
        while (jellies.size() > jn) {
            jellies.removeLast();
        }
        int fn = (int) fishSlider.getValue();
        for (int i = fishCount; i < fn; i++) {
            newFish(i);
        }
        fishCount = fn;
        int pn = (int) planktonSlider.getValue();
        for (int i = planktonCount; i < pn; i++) {
            newPlankton(i);
        }
        planktonCount = pn;
    }

    private Jelly newJelly() {
        Jelly j = new Jelly();
        j.radius = (22 + random.nextDouble() * 34) * unit;
        j.x = random.nextDouble() * width;
        j.y = !seededJellies() ? height * (0.15 + random.nextDouble() * 0.6) : height + j.radius * 2;
        j.angle = gauss() * 0.3;
        j.period = 1.6 + random.nextDouble() * 1.6;
        j.phase = random.nextDouble();
        j.seed = random.nextDouble() * 100;
        j.color = random.nextInt(GLOWS.length);
        for (int i = 0; i < TENTACLES; i++) {
            j.tentacles[i] = new Chain(16 + random.nextInt(10), j.radius * 0.16, j.x, j.y);
        }
        for (int i = 0; i < ARMS; i++) {
            j.arms[i] = new Chain(10, j.radius * 0.17, j.x, j.y);
        }
        newTarget(j);
        return j;
    }

    // Whether the jellyfish of the start are there, after which new ones rise from below
    private boolean seededJellies() {
        return time > 0.5;
    }

    private void newTarget(Jelly j) {
        j.targetX = width * (0.08 + random.nextDouble() * 0.84);
        j.targetY = height * (0.08 + random.nextDouble() * 0.7);
    }

    private void stepJellies(double dt) {
        for (Jelly j : jellies) {
            swim(j, dt);
            wrap(j);
            moveTentacles(j, dt);
        }
    }

    private void swim(Jelly j, double dt) {
        j.phase = (j.phase + dt / j.period) % 1;
        j.contract = j.phase < 0.3 ? Math.sin(Math.PI * j.phase / 0.3) : 0;
        double thrust = j.contract * 230 * unit;
        j.vx += Math.sin(j.angle) * thrust * dt;
        j.vy += (-Math.cos(j.angle) * thrust + 10 * unit) * dt;
        double drag = Math.exp(-1.5 * dt);
        j.vx *= drag;
        j.vy *= drag;
        j.x += (j.vx + flowX(j.x, j.y) * 0.6) * dt;
        j.y += (j.vy + flowY(j.x, j.y) * 0.6) * dt;
        steer(j, dt);
        j.flash = Math.max(j.flash * Math.exp(-1.8 * dt), j.contract * 0.4);
        if (mouse != null && mouse.distance(j.x, j.y) < j.radius * 1.4) {
            j.flash = 1;
        }
        if (j.contract > 0.5) {
            stirAround(j.x, j.y, j.contract * dt * 2);
        }
    }

    private void steer(Jelly j, double dt) {
        if (Math.hypot(j.targetX - j.x, j.targetY - j.y) < j.radius * 3) {
            newTarget(j);
        }
        double desired = Math.atan2(j.targetX - j.x, -(j.targetY - j.y));
        double diff = Math.IEEEremainder(desired - j.angle, Math.PI * 2);
        // Jellyfish only lean, never swim upside down
        j.angle += Math.clamp(diff, -0.45 * dt, 0.45 * dt) + Math.sin(time * 0.7 + j.seed) * 0.12 * dt;
        j.angle = Math.clamp(j.angle, -1.3, 1.3);
    }

    private void wrap(Jelly j) {
        double m = j.radius * 4;
        boolean moved = true;
        if (j.x < -m) {
            j.x = width + m;
        } else if (j.x > width + m) {
            j.x = -m;
        } else if (j.y < -m) {
            j.y = height + m * 0.5;
        } else if (j.y > height + m) {
            j.y = -m * 0.5;
        } else {
            moved = false;
        }
        if (moved) {
            for (Chain c : j.tentacles) {
                c.reset(j.x, j.y);
            }
            for (Chain c : j.arms) {
                c.reset(j.x, j.y);
            }
        }
    }

    private void moveTentacles(Jelly j, double dt) {
        double cos = Math.cos(j.angle);
        double sin = Math.sin(j.angle);
        double w = bellWidth(j);
        double damping = Math.pow(0.08, dt);
        double gx = flowX(j.x, j.y) * 0.25 * dt * dt * 60;
        double gy = 30 * unit * dt * dt * 60;
        for (int i = 0; i < TENTACLES; i++) {
            double lx = (-1 + 2.0 * i / (TENTACLES - 1)) * w * 0.92;
            j.tentacles[i].step(j.x + lx * cos, j.y + lx * sin, damping, gx, gy);
        }
        for (int i = 0; i < ARMS; i++) {
            double lx = (-1 + 2.0 * i / (ARMS - 1)) * w * 0.25;
            double ly = -j.radius * 0.2;
            j.arms[i].step(j.x + lx * cos - ly * sin, j.y + lx * sin + ly * cos, damping, gx, gy * 0.6);
        }
    }

    private double bellWidth(Jelly j) {
        return j.radius * (1 - 0.26 * j.contract);
    }

    private void newFish(int i) {
        fx[i] = random.nextDouble() * width;
        fy[i] = height * (0.2 + random.nextDouble() * 0.5);
        double a = random.nextDouble() * Math.PI * 2;
        fvx[i] = Math.cos(a) * 60 * unit;
        fvy[i] = Math.sin(a) * 30 * unit;
        fseed[i] = random.nextDouble();
    }

    private void stepFish(double dt) {
        double goalX = width * (0.5 + 0.36 * Math.sin(time * 0.071));
        double goalY = height * (0.42 + 0.2 * Math.sin(time * 0.113 + 1));
        for (int i = 0; i < fishCount; i++) {
            double[] a = flock(i);
            a[0] += (goalX - fx[i]) * 0.05 + flee(i, 0);
            a[1] += (goalY - fy[i]) * 0.08 + flee(i, 1) + edges(fy[i], height * FLOOR * 0.92);
            fvx[i] += a[0] * dt;
            fvy[i] += a[1] * dt;
            limitSpeed(i);
        }
        for (int i = 0; i < fishCount; i++) {
            fx[i] += (fvx[i] + flowX(fx[i], fy[i]) * 0.3) * dt;
            fy[i] += fvy[i] * dt;
            fx[i] = wrapped(fx[i], width, 30 * unit);
            stirAt(fx[i], fy[i], 0.06 * dt * 60 * Math.hypot(fvx[i], fvy[i]) / (150 * unit));
        }
    }

    // Separation, alignment and cohesion of the neighbors of a fish
    private double[] flock(int i) {
        double reach = 70 * unit;
        double close = 16 * unit;
        double sx = 0, sy = 0, ax = 0, ay = 0, cx = 0, cy = 0;
        int n = 0;
        for (int k = 0; k < fishCount; k++) {
            double dx = fx[k] - fx[i];
            double dy = fy[k] - fy[i];
            if (k == i || Math.abs(dx) > reach || Math.abs(dy) > reach) {
                continue;
            }
            double d2 = dx * dx + dy * dy;
            if (d2 > reach * reach) {
                continue;
            }
            if (d2 < close * close) {
                double d = Math.sqrt(d2) + 1e-3;
                sx -= dx / d * (close - d);
                sy -= dy / d * (close - d);
            }
            ax += fvx[k];
            ay += fvy[k];
            cx += dx;
            cy += dy;
            n++;
        }
        if (n == 0) {
            return new double[] {Math.sin(time + fseed[i] * 40) * 30 * unit, 0};
        }
        return new double[] {
            sx * 30 + (ax / n - fvx[i]) * 1.4 + cx / n * 0.9,
            sy * 30 + (ay / n - fvy[i]) * 1.4 + cy / n * 0.9
        };
    }

    // Away from the pointer and from the fronts of the pulses
    private double flee(int i, int axis) {
        double a = 0;
        if (mouse != null) {
            a += away(fx[i] - mouse.getX(), fy[i] - mouse.getY(), 150 * unit, axis) * 2400 * unit;
        }
        for (double[] p : pulses) {
            double dx = fx[i] - p[0];
            double dy = fy[i] - p[1];
            double d = Math.hypot(dx, dy) + 1e-3;
            if (Math.abs(d - pulseRadius(p)) < 50 * unit) {
                a += (axis == 0 ? dx : dy) / d * 1800 * unit;
            }
        }
        return a;
    }

    private static double away(double dx, double dy, double reach, int axis) {
        double d = Math.hypot(dx, dy) + 1e-3;
        return d > reach ? 0 : (axis == 0 ? dx : dy) / d * (1 - d / reach);
    }

    private double edges(double v, double max) {
        double margin = 60 * unit;
        if (v < margin) {
            return (margin - v) * 6;
        }
        return v > max - margin ? (max - margin - v) * 6 : 0;
    }

    private void limitSpeed(int i) {
        double speed = Math.hypot(fvx[i], fvy[i]) + 1e-6;
        double min = 45 * unit;
        double max = 170 * unit;
        double s = Math.clamp(speed, min, max) / speed;
        fvx[i] *= s;
        fvy[i] *= s;
    }

    private static double wrapped(double v, double max, double margin) {
        if (v < -margin) {
            return max + margin;
        }
        return v > max + margin ? -margin : v;
    }

    private void newPlankton(int i) {
        px[i] = (float) (random.nextDouble() * width);
        py[i] = (float) (random.nextDouble() * height * FLOOR);
        pglow[i] = 0;
        pseed[i] = (float) (random.nextDouble() * 1000);
    }

    private void stepPlankton(double dt) {
        float fade = (float) Math.exp(-dt * 0.85);
        double bottom = height * FLOOR;
        lit = 0;
        for (int i = 0; i < planktonCount; i++) {
            double x = px[i];
            double y = py[i];
            double s = pseed[i];
            x += (flowX(x, y) * 0.8 + Math.sin(time * 0.9 + s) * 5 * unit) * dt;
            y += (flowY(x, y) * 0.8 + Math.cos(time * 0.7 + s * 1.3) * 4 * unit) * dt;
            px[i] = (float) wrapped(x, width, 4);
            py[i] = (float) (y < 0 ? bottom : y > bottom ? 0 : y);
            float st = stirOf(px[i], py[i]);
            pglow[i] = Math.max(pglow[i] * fade, st > 0.06f ? Math.min(1, st * 1.3f) : 0);
            if (pglow[i] > 0.1f) {
                lit++;
            }
        }
    }

    private void stepSnow(double dt) {
        for (double[] s : snow) {
            s[0] = wrapped(s[0] + flowX(s[0], s[1]) * 0.9 * dt, width, 4);
            s[1] += (8 + s[2] * 8) * unit * dt;
            if (s[1] > height) {
                s[1] = -4;
                s[0] = random.nextDouble() * width;
            }
        }
    }

    private void stepBubbles(double dt) {
        if (bubblesOn.isSelected() && random.nextDouble() < dt * 2.2) {
            double vx = width * (0.18 + 0.64 * vents[0][0]);
            bubbles.add(bubble(vx + gauss() * 4 * unit, floorY(vx), 1.5 + random.nextDouble() * 4));
        }
        for (double[] b : bubbles) {
            b[1] -= (30 + b[2] * 9) * unit * dt;
            b[0] += (Math.sin(time * 3 + b[3]) * 18 * unit + flowX(b[0], b[1]) * 0.4) * dt;
            stirAt(b[0], b[1], 0.5 * dt * 4);
        }
        bubbles.removeIf(b -> b[1] < -b[2] * 4);
    }

    // ------------------------------------------------------------- rendering

    private void draw() {
        GraphicsContext g = back.getGraphicsContext2D();
        GraphicsContext l = glow.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        l.clearRect(0, 0, width, height);
        if (raysOn.isSelected()) {
            drawRays(g);
        }
        if (snowOn.isSelected()) {
            drawSnow(g);
        }
        if (floorOn.isSelected()) {
            drawFloor(g, l);
        }
        if (fishOn.isSelected()) {
            drawFish(g, l);
        }
        if (planktonOn.isSelected()) {
            drawPlankton(l);
        }
        if (jelliesOn.isSelected()) {
            for (Jelly j : jellies) {
                drawJelly(l, j);
            }
        }
        if (bubblesOn.isSelected()) {
            drawBubbles(l);
        }
        drawPulses(l);
        g.setGlobalAlpha(1);
        l.setGlobalAlpha(1);
    }

    private void drawRays(GraphicsContext g) {
        double light = light();
        if (light <= 0) {
            return;
        }
        g.setGlobalAlpha(1);
        g.setFill(new LinearGradient(0, 0, 0, height * 0.12, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(0.8, 0.97, 1, 0.16 * light)), new Stop(1, Color.TRANSPARENT)));
        g.fillRect(0, 0, width, height * 0.12);
        double length = height * (0.55 + 0.3 * light);
        for (double[] r : rays) {
            double a = light * 0.09 * (0.55 + 0.45 * Math.sin(time * 0.45 + r[2]));
            double top = width * (r[0] + 0.025 * Math.sin(time * 0.13 + r[2]));
            double half = width * r[1] / 2;
            double skew = width * 0.12;
            g.setFill(new LinearGradient(0, 0, 0, length, false, CycleMethod.NO_CYCLE,
                    new Stop(0, Color.color(0.75, 0.95, 1, a)), new Stop(1, Color.TRANSPARENT)));
            g.fillPolygon(new double[] {top - half, top + half, top + skew + half * 2.6, top + skew - half * 2.6},
                    new double[] {0, 0, length, length}, 4);
        }
    }

    private void drawSnow(GraphicsContext g) {
        g.setGlobalAlpha(0.35);
        for (double[] s : snow) {
            double size = s[2] * 3 * unit;
            g.drawImage(snowSprite, s[0] - size / 2, s[1] - size / 2, size, size);
        }
    }

    private void drawFloor(GraphicsContext g, GraphicsContext l) {
        double light = light();
        drawKelp(g, light);
        int n = 64;
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        for (int i = 0; i < n; i++) {
            xs[i] = width * i / (n - 1);
            ys[i] = floorY(xs[i]);
        }
        xs[n] = width;
        ys[n] = height;
        xs[n + 1] = 0;
        ys[n + 1] = height;
        g.setGlobalAlpha(1);
        g.setFill(new LinearGradient(0, height * (FLOOR - 0.05), 0, height, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.color(0.04 * light + 0.01, 0.12 * light + 0.03, 0.16 * light + 0.05, 0.95)),
                new Stop(1, Color.color(0, 0.01, 0.02, 1))));
        g.fillPolygon(xs, ys, n + 2);
        drawVents(l);
    }

    private void drawKelp(GraphicsContext g, double light) {
        g.setGlobalAlpha(0.35 + 0.5 * light);
        g.setLineCap(StrokeLineCap.ROUND);
        int segments = 16;
        double[] xs = new double[segments + 1];
        double[] ys = new double[segments + 1];
        for (double[] k : kelp) {
            double x = width * k[0];
            double y = floorY(x) + 6 * unit;
            double seg = height * k[1] / segments;
            double angle = 0;
            for (int i = 0; i <= segments; i++) {
                xs[i] = x;
                ys[i] = y;
                angle += Math.sin(time * 0.8 + k[2] + i * 0.35) * 0.05 + current.getValue() * 0.012;
                x += Math.sin(angle) * seg;
                y -= Math.cos(angle) * seg;
            }
            g.setStroke(Color.color(0.08 + 0.1 * light, 0.2 + 0.25 * light, 0.12 + 0.08 * light));
            g.setLineWidth(3.2 * unit);
            g.strokePolyline(xs, ys, segments + 1);
            g.setLineWidth(5 * unit);
            for (int i = 2; i < segments; i += 2) {
                double side = (i % 4 == 0 ? 1 : -1) * (9 + 4 * Math.sin(time + i)) * unit;
                g.strokeLine(xs[i], ys[i], xs[i] + side, ys[i] - 5 * unit);
            }
        }
    }

    // Glowing anemones on the seafloor
    private void drawVents(GraphicsContext l) {
        double deep = 1 - light() * 0.7;
        for (double[] v : vents) {
            double x = width * v[0];
            double y = floorY(x) - 3 * unit;
            double s = (26 + 10 * Math.sin(time * 1.3 + v[2])) * unit;
            l.setGlobalAlpha(deep * (0.35 + 0.25 * Math.sin(time * 1.3 + v[2])));
            l.drawImage(halos[(int) v[1]], x - s, y - s, s * 2, s * 2);
            l.setGlobalAlpha(deep * 0.9);
            l.drawImage(dots[(int) v[1]], x - 4 * unit, y - 4 * unit, 8 * unit, 8 * unit);
        }
    }

    private void drawFish(GraphicsContext g, GraphicsContext l) {
        double light = light();
        g.setGlobalAlpha(1);
        g.setFill(Color.color(0.55 + 0.3 * light, 0.7 + 0.2 * light, 0.85, 0.3 + 0.5 * light));
        double deep = 1 - light * 0.6;
        double[] xs = new double[7];
        double[] ys = new double[7];
        for (int i = 0; i < fishCount; i++) {
            fishShape(i, xs, ys);
            g.fillPolygon(xs, ys, 7);
            double s = (6 + fseed[i] * 3) * unit;
            l.setGlobalAlpha(deep * (0.5 + 0.4 * Math.sin(time * 2 + fseed[i] * 30)));
            l.drawImage(dots[planktonColor], xs[6] - s / 2, ys[6] - s / 2, s, s);
        }
    }

    // A fish along its velocity, with the tail wagging; its belly last
    private void fishShape(int i, double[] xs, double[] ys) {
        double speed = Math.hypot(fvx[i], fvy[i]) + 1e-6;
        double ux = fvx[i] / speed;
        double uy = fvy[i] / speed;
        double length = (6 + fseed[i] * 4) * unit;
        double w = length * 0.38;
        double wag = Math.sin(time * (10 + speed / unit * 0.05) + fseed[i] * 50) * w * 0.7;
        double x = fx[i];
        double y = fy[i];
        double tx = x - ux * length * 1.75 - uy * wag;
        double ty = y - uy * length * 1.75 + ux * wag;
        set(xs, ys, 0, x + ux * length, y + uy * length);
        set(xs, ys, 1, x - uy * w, y + ux * w);
        set(xs, ys, 2, x - ux * length, y - uy * length);
        set(xs, ys, 3, tx - uy * w * 1.1, ty + ux * w * 1.1);
        set(xs, ys, 4, tx + uy * w * 1.1, ty - ux * w * 1.1);
        set(xs, ys, 5, x - ux * length, y - uy * length);
        set(xs, ys, 6, x + uy * w * 0.6, y - ux * w * 0.6);
    }

    private static void set(double[] xs, double[] ys, int i, double x, double y) {
        xs[i] = x;
        ys[i] = y;
    }

    private void drawPlankton(GraphicsContext l) {
        Color c = GLOWS[planktonColor];
        Image dot = dots[planktonColor];
        double deep = 0.45 + 0.55 * (1 - light());
        double dim = 1.6 * unit;
        l.setGlobalAlpha(1);
        l.setFill(alpha(c, 0.22 * deep));
        for (int i = 0; i < planktonCount; i++) {
            if (pglow[i] <= 0.04f) {
                l.fillRect(px[i], py[i], dim, dim);
            }
        }
        for (int i = 0; i < planktonCount; i++) {
            float a = pglow[i];
            if (a > 0.04f) {
                double s = (7 + 15 * a) * unit;
                l.setGlobalAlpha(a * deep);
                l.drawImage(dot, px[i] - s / 2, py[i] - s / 2, s, s);
            }
        }
    }

    private void drawJelly(GraphicsContext l, Jelly j) {
        Color c = GLOWS[j.color];
        double glowLevel = 0.35 + 0.65 * Math.max(j.flash, 1 - light());
        drawTentacles(l, j, c, glowLevel);
        double halo = j.radius * (3.4 + j.flash * 1.6);
        l.setGlobalAlpha(0.18 + 0.45 * j.flash);
        l.drawImage(halos[j.color], j.x - halo / 2, j.y - j.radius * 0.5 - halo / 2, halo, halo);
        l.setGlobalAlpha(1);
        l.save();
        l.translate(j.x, j.y);
        l.rotate(Math.toDegrees(j.angle));
        drawBell(l, j, c, glowLevel);
        l.restore();
        drawRimLights(l, j);
    }

    private void drawBell(GraphicsContext l, Jelly j, Color c, double level) {
        double w = bellWidth(j);
        double h = j.radius * (0.85 + 0.3 * j.contract);
        l.setFill(new RadialGradient(0, 0, 0, -h * 0.75, j.radius * 1.3, false, CycleMethod.NO_CYCLE,
                new Stop(0, alpha(c.interpolate(Color.WHITE, 0.4), 0.55 * level)),
                new Stop(0.6, alpha(c, 0.22 * level)), new Stop(1, alpha(c, 0.04))));
        l.beginPath();
        l.moveTo(-w, 0);
        l.bezierCurveTo(-w, -h * 1.35, w, -h * 1.35, w, 0);
        int lobes = 8;
        for (int k = 0; k < lobes; k++) {
            double mid = w - 2 * w * (k + 0.5) / lobes;
            double end = w - 2 * w * (k + 1) / lobes;
            l.quadraticCurveTo(mid, h * 0.14, end, 0);
        }
        l.closePath();
        l.fill();
        l.setStroke(alpha(c.interpolate(Color.WHITE, 0.3), 0.5 + 0.4 * level));
        l.setLineWidth(1.4 * unit);
        l.stroke();

        l.setStroke(alpha(Color.WHITE, 0.22));
        l.beginPath();
        l.moveTo(-w * 0.62, -h * 0.12);
        l.bezierCurveTo(-w * 0.62, -h * 1.0, w * 0.62, -h * 1.0, w * 0.62, -h * 0.12);
        l.stroke();

        // The four gonads, seen through the bell
        l.setStroke(alpha(c.interpolate(Color.WHITE, 0.5), 0.35 + 0.6 * j.flash));
        l.setLineWidth(2 * unit);
        double g = j.radius * 0.2;
        for (int k = 0; k < 4; k++) {
            double gx = (k - 1.5) * w * 0.26;
            l.strokeArc(gx - g / 2, -h * 0.62, g, g * 1.2, 200, 140, ArcType.OPEN);
        }
    }

    private void drawRimLights(GraphicsContext l, Jelly j) {
        if (j.flash < 0.05) {
            return;
        }
        double s = 9 * unit;
        l.setGlobalAlpha(j.flash);
        for (Chain t : j.tentacles) {
            l.drawImage(dots[j.color], t.x[0] - s / 2, t.y[0] - s / 2, s, s);
        }
    }

    private void drawTentacles(GraphicsContext l, Jelly j, Color c, double level) {
        l.setLineCap(StrokeLineCap.ROUND);
        l.setLineJoin(StrokeLineJoin.ROUND);
        l.setStroke(c.interpolate(Color.WHITE, 0.25));
        for (Chain t : j.tentacles) {
            drawRope(l, t, 1.4 * unit, 0.55 * level, 0, 0);
        }
        l.setStroke(c);
        for (int i = 0; i < ARMS; i++) {
            drawRope(l, j.arms[i], 4.5 * unit, 0.4 * level, j.radius * 0.12, j.seed + i * 1.7);
        }
    }

    // A rope in pieces, thinner and fainter to its end; wavy when it is an oral arm
    private void drawRope(GraphicsContext l, Chain c, double width, double alpha, double wave, double seed) {
        int n = c.x.length;
        int piece = 4;
        double[] xs = new double[piece + 1];
        double[] ys = new double[piece + 1];
        for (int start = 0; start < n - 1; start += piece) {
            int count = Math.min(piece, n - 1 - start) + 1;
            for (int k = 0; k < count; k++) {
                int i = start + k;
                double off = wave * Math.sin(i * 0.8 - time * 3 + seed) * i / n;
                xs[k] = c.x[i] + off;
                ys[k] = c.y[i];
            }
            double f = 1 - (start + count / 2.0) / n;
            l.setGlobalAlpha(alpha * f);
            l.setLineWidth(Math.max(0.6, width * f));
            l.strokePolyline(xs, ys, count);
        }
    }

    private void drawBubbles(GraphicsContext l) {
        l.setLineWidth(1.1 * unit);
        l.setStroke(Color.color(0.85, 0.97, 1));
        l.setFill(Color.WHITE);
        for (double[] b : bubbles) {
            double r = b[2] * unit;
            l.setGlobalAlpha(0.5);
            l.strokeOval(b[0] - r, b[1] - r, r * 2, r * 2);
            l.setGlobalAlpha(0.7);
            l.fillOval(b[0] - r * 0.5, b[1] - r * 0.6, r * 0.5, r * 0.4);
        }
    }

    private void drawPulses(GraphicsContext l) {
        Color c = GLOWS[planktonColor];
        for (double[] p : pulses) {
            double r = pulseRadius(p);
            double f = p[2] / PULSE_LIFE;
            l.setStroke(c);
            for (int k = 0; k < 3; k++) {
                double rk = Math.max(0, r - k * 18 * unit);
                l.setGlobalAlpha((1 - f) * 0.45 / (k + 1));
                l.setLineWidth((2.5 - k * 0.6) * unit);
                l.strokeOval(p[0] - rk, p[1] - rk, rk * 2, rk * 2);
            }
        }
    }

    // ------------------------------------------------------------------ loop

    private void startLoop() {
        new EyeCandyDemo.Loop() {
            long last;
            long window;
            int frames;
            double sum;
            double worst;
            int reports;

            @Override
            public void handle(long now) {
                double dt = last == 0 ? 0 : (now - last) / 1e9;
                last = now;
                if (dt > 0) {
                    measure(dt);
                }
                frame(dt);
            }

            private void measure(double dt) {
                double ms = dt * 1000;
                frameTimes[frameHead] = ms;
                frameHead = (frameHead + 1) % GRAPH;
                frames++;
                sum += ms;
                worst = Math.max(worst, ms);
                window += (long) (dt * 1e9);
                if (window >= 500_000_000L) {
                    report(frames * 1e9 / window, sum / frames, worst, ++reports % 4 == 0);
                    frames = 0;
                    sum = 0;
                    worst = 0;
                    window = 0;
                }
                updateGraph();
            }
        }.start();
    }

    private void frame(double dt) {
        resize(glow.getWidth(), glow.getHeight());
        if (width < 1 || height < 1) {
            return;
        }
        if (!seeded) {
            seed();
        }
        double step = paused ? 0 : Math.min(dt, 0.05) * timeScale.getValue();
        time += step;
        if (step > 0) {
            step(step);
        }
        draw();
        animateChrome();
    }

    private void report(double fps, double average, double worst, boolean print) {
        fpsValue.setText(String.format(Locale.ROOT, "%.0f", fps));
        frameValue.setText(String.format(Locale.ROOT, "%.1f", average));
        worstValue.setText(String.format(Locale.ROOT, "%.1f", worst));
        depthValue.setText(String.format(Locale.ROOT, "%.0f m", depth.getValue()));
        litValue.setText(String.format(Locale.ROOT, "%,d", lit));
        jellyValue.setText(String.valueOf(jellies.size()));
        double scale = stage.getOutputScaleX();
        sizeValue.setText(String.format(Locale.ROOT, "%.0f×%.0f @%.2f · %,d plankton · %d fish",
                scene.getWidth(), scene.getHeight(), scale, planktonCount, fishCount));
        if (print) {
            System.out.printf(Locale.ROOT, "abyss: %.0f fps, %.1f ms avg, %.1f ms worst, %d glowing%n",
                    fps, average, worst, lit);
        }
    }

    private void animateChrome() {
        double shift = time * 0.08;
        title.setFill(new LinearGradient(-shift, 0, 1 - shift, 0, true, CycleMethod.REFLECT,
                new Stop(0, GLOWS[0]), new Stop(0.35, GLOWS[2]), new Stop(0.7, GLOWS[3]), new Stop(1, GLOWS[1])));
        logo.setTranslateY(Math.sin(time * 2.2) * 2);
    }

    private void updateGraph() {
        Double[] line = new Double[GRAPH * 2];
        for (int i = 0; i < GRAPH; i++) {
            double ms = frameTimes[(frameHead + i) % GRAPH];
            line[i * 2] = GRAPH_W * i / (GRAPH - 1);
            line[i * 2 + 1] = GRAPH_H - Math.min(ms / 40, 1) * GRAPH_H;
        }
        frameLine.getPoints().setAll(line);
        frameArea.getPoints().setAll(line);
        frameArea.getPoints().addAll(GRAPH_W, GRAPH_H, 0.0, GRAPH_H);
    }
}
