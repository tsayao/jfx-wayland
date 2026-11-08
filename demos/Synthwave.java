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
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.Scene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.effect.Bloom;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.effect.Effect;
import javafx.scene.effect.Reflection;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.AnchorPane;
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
import javafx.scene.paint.PhongMaterial;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Box;
import javafx.scene.shape.Circle;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Line;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * A flight down a neon valley at dusk: a terrain of ten thousand triangles
 * streamed toward the camera every frame, wrapped in a grid of glowing
 * ribbons, whose mountains dance to a synthetic beat, under a striped sun.
 * 3D on the Prism renderer in a SubScene, between canvases for the sky and the
 * screen.
 */
public class Synthwave implements EyeCandyDemo.Candy {

    private static final String CSS = """
        .root {
            -fx-font-family: "Adwaita Sans", "Cantarell", "System";
            -fx-font-size: 13px;
            -fx-base: #241035;
            -fx-background: transparent;
            -fx-control-inner-background: #170a24;
            -fx-accent: #ff2bd6;
            -fx-focus-color: #29f3ff;
            -fx-faint-focus-color: #29f3ff22;
            -fx-text-fill: #fdeaff;
        }
        .label, .check-box { -fx-text-fill: #fdeaff; }
        .muted { -fx-text-fill: #b393c4; }
        .mono { -fx-font-family: "Adwaita Mono", "Monospace"; }
        .glass {
            -fx-background-color: rgba(16, 4, 30, 0.55);
            -fx-background-radius: 18;
            -fx-border-color: rgba(255, 255, 255, 0.16) rgba(255, 255, 255, 0.07)
                rgba(255, 255, 255, 0.04) rgba(255, 255, 255, 0.07);
            -fx-border-radius: 18;
            -fx-padding: 16;
        }
        .section { -fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #b393c4; -fx-padding: 8 0 0 0; }
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
        .stat-key { -fx-font-size: 10px; -fx-text-fill: #b393c4; }
        .check-box > .box {
            -fx-background-color: rgba(255, 255, 255, 0.08);
            -fx-background-radius: 5;
            -fx-border-color: rgba(255, 255, 255, 0.22);
            -fx-border-radius: 5;
        }
        .check-box:selected > .box {
            -fx-background-color: linear-gradient(to bottom right, #ffe66b, #ff2bd6, #29f3ff);
            -fx-border-color: transparent;
        }
        .check-box:selected > .box > .mark { -fx-background-color: white; }
        .slider .track { -fx-background-color: rgba(255, 255, 255, 0.14); -fx-background-radius: 4; }
        .slider .thumb {
            -fx-background-color: white;
            -fx-effect: dropshadow(gaussian, #ff2bd6, 10, 0.3, 0, 0);
        }
        .button {
            -fx-background-color: rgba(255, 255, 255, 0.08);
            -fx-background-radius: 9;
            -fx-border-color: rgba(255, 255, 255, 0.14);
            -fx-border-radius: 9;
            -fx-text-fill: #fdeaff;
            -fx-padding: 6 14 6 14;
            -fx-cursor: hand;
        }
        .button:hover { -fx-background-color: rgba(255, 255, 255, 0.15); }
        .button.accent {
            -fx-background-color: linear-gradient(to right, #ffe66b, #ff2bd6, #8b5cf6, #29f3ff);
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

    // The terrain: an odd number of columns, so that one runs down the middle
    private static final int COLS = 65;
    private static final int CHUNK_ROWS = 12;
    private static final int CHUNKS = 7;
    private static final int ROWS = CHUNK_ROWS * CHUNKS;
    private static final int FIRST_ROW = -2;
    private static final double CELL = 50;
    private static final double FAR = (ROWS + FIRST_ROW) * CELL;
    private static final double VALLEY = 230;
    // The flat columns of the valley, between the two ranges, which dance
    private static final int VALLEY_COLS = 4;
    private static final int[][] PARTS = {
        {0, COLS / 2 - VALLEY_COLS}, {COLS / 2 - VALLEY_COLS, COLS / 2 + VALLEY_COLS}, {COLS / 2 + VALLEY_COLS, COLS - 1}
    };
    // The heights of the terrain are about 0 to 1, by this many units
    private static final double SCALE = 1000;
    private static final int ROAD = 3;
    private static final int POST_EVERY = 4;
    private static final int POSTS = ROWS / POST_EVERY + 1;
    private static final double PITCH = 3.5;
    private static final double MAX_ROLL = 5;
    // The sky canvas is larger than the window, so that it covers it when the camera rolls
    private static final double SKY_SCALE = 1.2;
    private static final int STARS = 320;
    private static final int GRAPH = 160;
    private static final double GRAPH_W = 268;
    private static final double GRAPH_H = 56;

    /** The colors of a ride. */
    private record Palette(String name, Color low, Color high, Color haze, Color skyTop, Color skyHorizon,
                           Color sunTop, Color sunBottom, Color ground) {

        static Palette of(String name, String... colors) {
            Color[] c = new Color[colors.length];
            for (int i = 0; i < colors.length; i++) {
                c[i] = Color.web(colors[i]);
            }
            return new Palette(name, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
        }
    }

    private static final List<Palette> PALETTES = List.of(
            Palette.of("Miami", "#ff2bd6", "#29f3ff", "#ff4fb8", "#07011a", "#5b0e6b",
                    "#ffe66b", "#ff2f87", "#0e0219"),
            Palette.of("Cyber", "#00e5ff", "#8b6bff", "#1f6bff", "#010310", "#0b2a66",
                    "#b8fbff", "#2a6bff", "#020713"),
            Palette.of("Sunset", "#ff7a18", "#ffd36b", "#ff4d4d", "#170610", "#8a1f2b",
                    "#fff1a8", "#ff3d5a", "#16040a"),
            Palette.of("Acid", "#39ff14", "#e0ff4f", "#1fa34a", "#010a04", "#0c3b1c",
                    "#f1ff7a", "#22d36b", "#020a05"));

    private Stage stage;
    private Scene scene;
    private boolean backdrop;
    private boolean paused;
    private double time;
    private double distance;
    private double camX;
    private double camVX;
    private double steer = Double.NaN;
    private double keySteer;
    private double boost;
    private double boostUntil = -1;
    private double beat;
    private double pulse;
    private Palette palette = PALETTES.getFirst();
    private final Random random = new Random(1984);

    private final Canvas sky = new Canvas();
    private final Canvas screen = new Canvas();
    private final StackPane world = new StackPane();
    private SubScene view;
    private double width;
    private double height;

    /*
     * The terrain is in chunks of rows, which move toward the camera, and the
     * chunk that passes it goes to the horizon with new rows: Prism builds the
     * normals of a mesh again whenever its points change, so the points change
     * only in that chunk. The haze toward the horizon is in a texture for each
     * chunk, painted each frame, and the ranges dance with a scale.
     */
    private static final class Chunk {
        final Group node = new Group();
        final TriangleMesh[] grounds = new TriangleMesh[PARTS.length];
        final TriangleMesh[] grids = new TriangleMesh[PARTS.length];
        final WritableImage groundTexture = new WritableImage(COLS, CHUNK_ROWS + 1);
        final WritableImage gridTexture = new WritableImage(COLS, CHUNK_ROWS + 1);
        long row;
    }

    private final List<Chunk> chunks = new ArrayList<>();
    private long nearRow = FIRST_ROW;
    private final Scale leftRange = new Scale(1, 1, 1, 0, 0, 0);
    private final Scale rightRange = new Scale(1, 1, 1, 0, 0, 0);
    private final int[] texels = new int[COLS * (CHUNK_ROWS + 1)];
    private final double[][] nearGrid = new double[COLS][];
    private final double[][] nearGround = new double[COLS][];
    private double[] farGrid;
    private double[] farGround;
    private final PhongMaterial poleMaterial = new PhongMaterial(Color.BLACK);
    private final PhongMaterial lampMaterial = new PhongMaterial(Color.BLACK);
    private final AmbientLight gridLight = new AmbientLight();
    private final AmbientLight groundLight = new AmbientLight();
    private final Group terrain = new Group();
    private final Group[] posts = new Group[POSTS * 2];
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Translate cameraAt = new Translate();
    private final Rotate roll = new Rotate(0, Rotate.Z_AXIS);
    private final Rotate pitch = new Rotate(-PITCH, Rotate.X_AXIS);

    private double danceLeft = 0.5;
    private double danceRight = 0.5;
    private double targetLeft = 0.5;
    private double targetRight = 0.5;

    private final double[][] stars = new double[STARS][3];
    private final List<double[]> meteors = new ArrayList<>();
    private final double[][] streaks = new double[90][3];

    private final CheckBox dance = new CheckBox("Dance to the beat");
    private final CheckBox sunOn = new CheckBox("Sun");
    private final CheckBox starsOn = new CheckBox("Stars and meteors");
    private final CheckBox rangeOn = new CheckBox("Far mountains");
    private final CheckBox postsOn = new CheckBox("Light posts");
    private final CheckBox scanlines = new CheckBox("Scanlines");
    private final CheckBox bloom = new CheckBox("Bloom (Decora)");
    private final Slider speed = new Slider(100, 2400, 700);
    private final Slider height3d = new Slider(25, 320, 70);
    private final Slider bpm = new Slider(60, 180, 118);
    private final Slider mountains = new Slider(0, 1400, 760);
    private final Slider lineWidth = new Slider(0.4, 5, 1.6);
    private final Slider tint = new Slider(0, 1, 0.55);

    private final Label fpsValue = new Label("–");
    private final Label frameValue = new Label("–");
    private final Label worstValue = new Label("–");
    private final Label speedValue = new Label("–");
    private final Label bpmValue = new Label("–");
    private final Label trianglesValue = new Label("–");
    private final Label sizeValue = new Label("–");
    private final Polyline frameLine = new Polyline();
    private final Polygon frameArea = new Polygon();
    private final double[] frameTimes = new double[GRAPH];
    private int frameHead;
    private final Text title = new Text("SYNTHWAVE");
    private final List<Circle> swatches = new ArrayList<>();
    private Node switcher;
    private Node panels;

    @Override
    public Scene scene(Stage stage, Node switcher, double width, double height) {
        this.stage = stage;
        this.switcher = switcher;
        backdrop = Boolean.getBoolean("glass.gtk4.backdrop.active");
        for (CheckBox c : List.of(dance, sunOn, starsOn, rangeOn, postsOn, scanlines, bloom)) {
            c.setSelected(true);
        }
        tint.setDisable(!backdrop);
        seedSky();

        BorderPane overlay = new BorderPane();
        overlay.setTop(header());
        overlay.setCenter(panels());
        overlay.setPickOnBounds(false);

        StackPane root = new StackPane(world(), screenLayer(), overlay);
        root.setMinSize(0, 0);
        scene = new Scene(root, width, height, Color.TRANSPARENT);
        scene.getStylesheets().add(STYLESHEET);
        scene.getPreferences().setColorScheme(ColorScheme.DARK);
        applyPalette(PALETTES.getFirst());
        chunks.forEach(this::build);
        lineWidth.valueProperty().addListener(o -> chunks.forEach(this::build));
        mouse(root);
        keys();
        startLoop();
        return scene;
    }

    // The sky, and the 3D in front of it, glowing together
    private Node world() {
        Pane skyLayer = new Pane(sky);
        sky.setManaged(false);
        sky.setScaleX(SKY_SCALE);
        sky.setScaleY(SKY_SCALE);
        sky.widthProperty().bind(skyLayer.widthProperty());
        sky.heightProperty().bind(skyLayer.heightProperty());
        skyLayer.setMinSize(0, 0);

        Pane viewLayer = new Pane(view3d());
        view.setManaged(false);
        view.widthProperty().bind(viewLayer.widthProperty());
        view.heightProperty().bind(viewLayer.heightProperty());
        viewLayer.setMinSize(0, 0);

        world.getChildren().addAll(skyLayer, viewLayer);
        world.setMinSize(0, 0);
        world.setMouseTransparent(true);
        Bloom glow = new Bloom(0.5);
        world.effectProperty().bind(Bindings.when(bloom.selectedProperty())
                .then((Effect) glow).otherwise((Effect) null));
        return world;
    }

    private Node screenLayer() {
        Pane layer = new Pane(screen);
        screen.setManaged(false);
        screen.setMouseTransparent(true);
        screen.widthProperty().bind(layer.widthProperty());
        screen.heightProperty().bind(layer.heightProperty());
        layer.setMinSize(0, 0);
        layer.setMouseTransparent(true);
        return layer;
    }

    private SubScene view3d() {
        for (int k = 0; k < CHUNKS; k++) {
            Chunk c = chunk();
            c.row = nearRow + (long) k * CHUNK_ROWS;
            chunks.add(c);
            terrain.getChildren().add(c.node);
        }
        for (int i = 0; i < posts.length; i++) {
            posts[i] = post();
            terrain.getChildren().add(posts[i]);
        }

        camera.setNearClip(2);
        camera.setFarClip(FAR * 2);
        camera.setFieldOfView(55);
        camera.getTransforms().addAll(cameraAt, roll, pitch);

        Group root3d = new Group(terrain, gridLight, groundLight, camera);
        view = new SubScene(root3d, 800, 600, true, SceneAntialiasing.BALANCED);
        view.setFill(Color.TRANSPARENT);
        view.setCamera(camera);
        return view;
    }

    private Group post() {
        Box pole = new Box(3, 70, 3);
        pole.setTranslateY(-35);
        pole.setMaterial(poleMaterial);
        Sphere lamp = new Sphere(6, 12);
        lamp.setTranslateY(-74);
        lamp.setMaterial(lampMaterial);
        return new Group(pole, lamp);
    }

    // ------------------------------------------------------------- the meshes

    private Chunk chunk() {
        Chunk c = new Chunk();
        PhongMaterial groundMaterial = material(c.groundTexture);
        PhongMaterial gridMaterial = material(c.gridTexture);
        for (int p = 0; p < PARTS.length; p++) {
            c.grounds[p] = partMesh(p, false);
            c.grids[p] = partMesh(p, true);
            MeshView ground = meshView(c.grounds[p], groundMaterial, p);
            MeshView grid = meshView(c.grids[p], gridMaterial, p);
            groundLight.getScope().add(ground);
            gridLight.getScope().add(grid);
            c.node.getChildren().addAll(ground, grid);
        }
        return c;
    }

    private static PhongMaterial material(Image texture) {
        PhongMaterial m = new PhongMaterial(Color.WHITE);
        m.setDiffuseMap(texture);
        m.setSpecularColor(Color.BLACK);
        return m;
    }

    private MeshView meshView(TriangleMesh mesh, PhongMaterial material, int part) {
        MeshView v = new MeshView(mesh);
        v.setMaterial(material);
        v.setCullFace(CullFace.NONE);
        if (part != 1) {
            v.getTransforms().add(part == 0 ? leftRange : rightRange);
        }
        return v;
    }

    private static int columns(int part) {
        return PARTS[part][1] - PARTS[part][0] + 1;
    }

    // A part of a chunk, whose texture coordinates are the texels of its vertices
    private static TriangleMesh partMesh(int part, boolean lines) {
        int n = columns(part);
        int rows = CHUNK_ROWS + 1;
        float[] tex = new float[rows * n * 2];
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < n; i++) {
                tex[(j * n + i) * 2] = (float) ((PARTS[part][0] + i + 0.5) / COLS);
                tex[(j * n + i) * 2 + 1] = (float) ((j + 0.5) / rows);
            }
        }
        TriangleMesh mesh = new TriangleMesh();
        mesh.getTexCoords().setAll(tex);
        mesh.getPoints().setAll(new float[rows * n * (lines ? 6 : 3)]);
        mesh.getFaces().setAll(lines ? gridFaces(n, rows) : groundFaces(n, rows));
        return mesh;
    }

    private static int[] groundFaces(int n, int rows) {
        int[] faces = new int[(rows - 1) * (n - 1) * 12];
        int f = 0;
        for (int j = 0; j < rows - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                int a = j * n + i;
                int b = a + 1;
                int d = a + n;
                int e = d + 1;
                f = triangle(faces, f, a, a, d, d, b, b);
                f = triangle(faces, f, b, b, d, d, e, e);
            }
        }
        return faces;
    }

    /*
     * The grid is a ribbon for each segment between two vertices, flat on the
     * ground, so that it shows squares and not the diagonals of the
     * triangles. Each vertex has two points, at (x - w, z - w) and (x + w,
     * z + w), the corners of the ribbons along X and along Z.
     */
    private static int[] gridFaces(int n, int rows) {
        int[] faces = new int[((rows - 1) * n + rows * (n - 1)) * 12];
        int f = 0;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * n + i;
                if (j < rows - 1) {
                    f = ribbon(faces, f, a, a + n);
                }
                if (i < n - 1) {
                    f = ribbon(faces, f, a, a + 1);
                }
            }
        }
        return faces;
    }

    private static int ribbon(int[] faces, int f, int a, int b) {
        f = triangle(faces, f, a * 2, a, a * 2 + 1, a, b * 2, b);
        return triangle(faces, f, a * 2 + 1, a, b * 2 + 1, b, b * 2, b);
    }

    private static int triangle(int[] faces, int f, int... corners) {
        System.arraycopy(corners, 0, faces, f, 6);
        return f + 6;
    }

    private static int triangles() {
        int count = 0;
        for (int p = 0; p < PARTS.length; p++) {
            int n = columns(p);
            count += (n - 1) * CHUNK_ROWS * 2 + (CHUNK_ROWS * n + (CHUNK_ROWS + 1) * (n - 1)) * 2;
        }
        return count * CHUNKS;
    }

    // ------------------------------------------------------------ the terrain

    private static double hash(long x, long y) {
        long h = x * 374761393L + y * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xffffff) / (double) 0xffffff;
    }

    private static double noise(double x, double y) {
        long xi = (long) Math.floor(x);
        long yi = (long) Math.floor(y);
        double fx = x - xi;
        double fy = y - yi;
        double u = fx * fx * (3 - 2 * fx);
        double v = fy * fy * (3 - 2 * fy);
        double top = lerp(hash(xi, yi), hash(xi + 1, yi), u);
        double bottom = lerp(hash(xi, yi + 1), hash(xi + 1, yi + 1), u);
        return lerp(top, bottom, v);
    }

    // Ridged noise, for the sharp crests of the mountains
    private static double ridges(double x, double y) {
        double sum = 0;
        double amplitude = 0.5;
        double total = 0;
        for (int octave = 0; octave < 4; octave++) {
            double n = 1 - Math.abs(noise(x, y) * 2 - 1);
            sum += n * n * amplitude;
            total += amplitude;
            amplitude *= 0.5;
            x *= 2.03;
            y *= 2.03;
        }
        return sum / total;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double smoothstep(double edge0, double edge1, double x) {
        double t = Math.clamp((x - edge0) / (edge1 - edge0), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static double columnX(int c) {
        return (c - (COLS - 1) / 2.0) * CELL;
    }

    // The heights of a row, which the valley keeps flat in the middle, from 0 to about 1
    private static double[] rowHeights(long row) {
        double[] heights = new double[COLS];
        for (int c = 0; c < COLS; c++) {
            double x = columnX(c);
            double side = smoothstep(VALLEY, VALLEY * 4.5, Math.abs(x));
            double crest = ridges(x * 0.0016 + 40, row * CELL * 0.0016);
            heights[c] = side * (0.12 + crest * crest * 1.1);
        }
        return heights;
    }

    // The points of a chunk, in it, from its first row
    private void build(Chunk c) {
        double width = lineWidth.getValue();
        double[][] rows = new double[CHUNK_ROWS + 1][];
        for (int j = 0; j <= CHUNK_ROWS; j++) {
            rows[j] = rowHeights(c.row + j);
        }
        for (int p = 0; p < PARTS.length; p++) {
            int n = columns(p);
            float[] ground = new float[rows.length * n * 3];
            float[] grid = new float[rows.length * n * 6];
            for (int j = 0; j < rows.length; j++) {
                for (int i = 0; i < n; i++) {
                    int col = PARTS[p][0] + i;
                    double w = width * (Math.abs(col - COLS / 2) == ROAD ? 2.4 : 1);
                    double x = columnX(col);
                    double y = -rows[j][col] * SCALE;
                    double z = j * CELL;
                    int v = j * n + i;
                    // The ground sinks under the ribbons, more where they are wider
                    put(ground, v * 3, x, y + 2 + w * 2.5, z);
                    put(grid, v * 6, x - w, y, z - w);
                    put(grid, v * 6 + 3, x + w, y, z + w);
                }
            }
            c.grounds[p].getPoints().set(0, ground, 0, ground.length);
            c.grids[p].getPoints().set(0, grid, 0, grid.length);
        }
    }

    private static void put(float[] points, int i, double x, double y, double z) {
        points[i] = (float) x;
        points[i + 1] = (float) y;
        points[i + 2] = (float) z;
    }

    private void updateTerrain() {
        while ((nearRow + CHUNK_ROWS) * CELL - distance < FIRST_ROW * CELL) {
            Chunk c = chunks.removeFirst();
            nearRow += CHUNK_ROWS;
            c.row = nearRow + (long) (CHUNKS - 1) * CHUNK_ROWS;
            build(c);
            chunks.addLast(c);
        }
        for (Chunk c : chunks) {
            double z = c.row * CELL - distance;
            c.node.setTranslateZ(z);
            paint(c, z);
        }
        double top = mountains.getValue() / SCALE;
        leftRange.setY(top * danceScale(danceLeft));
        rightRange.setY(top * danceScale(danceRight));
        placePosts();
    }

    private double danceScale(double level) {
        return dance.isSelected() ? 0.45 + 0.85 * level : 1;
    }

    // The colors of a chunk, from near to the haze of the horizon by its distance
    private void paint(Chunk c, double z) {
        paint(c.gridTexture, nearGrid, farGrid, z);
        paint(c.groundTexture, nearGround, farGround, z);
    }

    private void paint(WritableImage texture, double[][] near, double[] far, double z) {
        for (int j = 0; j <= CHUNK_ROWS; j++) {
            double haze = Math.pow(Math.clamp((z + j * CELL) / FAR, 0, 1), 1.25);
            for (int col = 0; col < COLS; col++) {
                texels[j * COLS + col] = argb(near[col], far, haze);
            }
        }
        texture.getPixelWriter().setPixels(0, 0, COLS, CHUNK_ROWS + 1,
                PixelFormat.getIntArgbInstance(), texels, 0, COLS);
    }

    private static int argb(double[] a, double[] b, double t) {
        int r = (int) Math.round((a[0] + (b[0] - a[0]) * t) * 255);
        int g = (int) Math.round((a[1] + (b[1] - a[1]) * t) * 255);
        int bl = (int) Math.round((a[2] + (b[2] - a[2]) * t) * 255);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    private static double[] rgb(Color c) {
        return new double[] {c.getRed(), c.getGreen(), c.getBlue()};
    }

    private void placePosts() {
        long first = Math.floorDiv(nearRow + POST_EVERY - 1, (long) POST_EVERY) * POST_EVERY;
        double x = columnX(COLS / 2 + ROAD) + 18;
        for (int i = 0; i < POSTS; i++) {
            double z = (first + (long) i * POST_EVERY) * CELL - distance;
            boolean shown = postsOn.isSelected() && z < FAR * 0.8;
            for (int side = 0; side < 2; side++) {
                Group post = posts[i * 2 + side];
                post.setVisible(shown);
                post.setTranslateX(side == 0 ? -x : x);
                post.setTranslateZ(z);
            }
        }
    }

    // --------------------------------------------------------------- palette

    private void applyPalette(Palette p) {
        palette = p;
        for (int col = 0; col < COLS; col++) {
            double side = smoothstep(VALLEY * 0.5, VALLEY * 4, Math.abs(columnX(col)));
            nearGrid[col] = rgb(p.low().interpolate(p.high(), side));
            nearGround[col] = rgb(p.ground().interpolate(p.low().deriveColor(0, 1, 0.16, 1), side * 0.6));
        }
        farGrid = rgb(p.haze().interpolate(p.skyHorizon(), 0.3));
        farGround = rgb(p.haze().deriveColor(0, 1, 0.45, 1).interpolate(p.skyHorizon(), 0.3));
        poleMaterial.setSelfIlluminationMap(solid(p.high().interpolate(Color.WHITE, 0.2)));
        lampMaterial.setSelfIlluminationMap(solid(p.low().interpolate(Color.WHITE, 0.55)));
        for (int i = 0; i < swatches.size(); i++) {
            swatches.get(i).setStroke(PALETTES.get(i) == p ? Color.WHITE : Color.TRANSPARENT);
        }
        title.setEffect(titleEffect(p));
    }

    private static Image solid(Color color) {
        WritableImage image = new WritableImage(4, 4);
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 4; x++) {
                image.getPixelWriter().setColor(x, y, color);
            }
        }
        return image;
    }

    private static Effect titleEffect(Palette p) {
        Reflection reflection = new Reflection(0, 0.4, 0.45, 0);
        reflection.setInput(new DropShadow(BlurType.GAUSSIAN, p.low(), 26, 0.3, 0, 0));
        return reflection;
    }

    private static LinearGradient chrome() {
        return new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#f4fbff")), new Stop(0.46, Color.web("#5ab0ff")),
                new Stop(0.5, Color.web("#1b0b3a")), new Stop(0.56, Color.web("#ff4fd0")),
                new Stop(1, Color.web("#ffe6fa")));
    }

    // ---------------------------------------------------------------- chrome

    private static String pipelineName() {
        String name = com.sun.prism.GraphicsPipeline.getPipeline().getClass().getSimpleName();
        return switch (name) {
            case "VKPipeline" -> "Vulkan";
            case "ES2Pipeline" -> "OpenGL ES";
            case "SWPipeline" -> "Software";
            default -> name;
        };
    }

    private Node header() {
        Label brand = new Label("Synthwave");
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
        Button boostButton = new Button("Boost  B");
        boostButton.getStyleClass().add("accent");
        boostButton.setOnAction(e -> boost());
        Button hide = new Button("Panels  H");
        hide.setOnAction(e -> togglePanels());

        HBox right = new HBox(10, live, pipeline, glass, boostButton, hide);
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

    // A striped sun over a line
    private static Node logo() {
        Circle sun = new Circle(9, new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#ffe66b")), new Stop(1, Color.web("#ff2f87"))));
        Group logo = new Group(sun);
        for (int i = 0; i < 3; i++) {
            Line gap = new Line(-10, 1.5 + i * 3, 10, 1.5 + i * 3);
            gap.setStroke(Color.web("#170a24"));
            gap.setStrokeWidth(1 + i * 0.4);
            logo.getChildren().add(gap);
        }
        Line horizon = new Line(-12, 9, 12, 9);
        horizon.setStroke(Color.web("#29f3ff"));
        horizon.setStrokeWidth(1.5);
        logo.getChildren().add(horizon);
        logo.setEffect(new DropShadow(10, Color.web("#ff2bd6")));
        return logo;
    }

    private static Node pulsingDot() {
        Circle dot = new Circle(4, Color.web("#ff2bd6"));
        dot.setEffect(new DropShadow(8, Color.web("#ff2bd6")));
        FadeTransition fade = new FadeTransition(Duration.millis(900), dot);
        fade.setFromValue(1);
        fade.setToValue(0.3);
        fade.setAutoReverse(true);
        fade.setCycleCount(Animation.INDEFINITE);
        fade.play();
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
        title.setFill(chrome());
        title.setStroke(Color.color(1, 1, 1, 0.35));
        title.setStrokeWidth(0.8);
        Label subtitle = new Label("Prism · " + pipelineName() + " · 3D · move the pointer to steer");
        subtitle.getStyleClass().add("muted");

        GridPane stats = new GridPane(22, 10);
        stats.add(stat("FPS", fpsValue), 0, 0);
        stats.add(stat("Frame ms", frameValue), 1, 0);
        stats.add(stat("Worst ms", worstValue), 2, 0);
        stats.add(stat("km/h", speedValue), 0, 1);
        stats.add(stat("BPM", bpmValue), 1, 1);
        stats.add(stat("Triangles", trianglesValue), 2, 1);
        trianglesValue.setText(String.format(Locale.ROOT, "%,d", triangles()));

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
                new Stop(0, Color.web("#ff2bd666")), new Stop(1, Color.web("#29f3ff00"))));
        frameLine.setStroke(new LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#ffe66b")), new Stop(0.5, Color.web("#ff2bd6")), new Stop(1, Color.web("#29f3ff"))));
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
        Button boostButton = new Button("Boost  B");
        boostButton.getStyleClass().add("accent");
        boostButton.setOnAction(e -> boost());
        Button full = new Button("Full screen");
        full.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));
        HBox buttons = new HBox(8, boostButton, full);

        Label hint = new Label("Pointer or ← → to steer · wheel for speed\n"
                + "B boost · M dance · 1–4 palettes\nH panels · F full screen · Space pause");
        hint.getStyleClass().add("muted");

        VBox box = new VBox(8,
                section("DRIVE"),
                sliderRow("Speed", speed, "%.0f"),
                sliderRow("Camera height", height3d, "%.0f"),
                section("MUSIC"),
                dance,
                sliderRow("Tempo", bpm, "%.0f bpm"),
                section("TERRAIN"),
                sliderRow("Mountains", mountains, "%.0f"),
                sliderRow("Grid lines", lineWidth, "%.1f"),
                section("PALETTE"), swatches(),
                section("LAYERS"), sunOn, starsOn, rangeOn, postsOn, scanlines, bloom,
                section("WINDOW"),
                sliderRow("Backdrop sky", tint, "%.2f"),
                spacer(4), buttons, spacer(4), hint);
        box.setPadding(new Insets(0, 12, 0, 0));
        return box;
    }

    private Node swatches() {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        for (Palette p : PALETTES) {
            Circle c = new Circle(10, new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, p.sunTop()), new Stop(0.5, p.low()), new Stop(1, p.high())));
            c.setEffect(new DropShadow(BlurType.GAUSSIAN, p.low(), 10, 0.35, 0, 0));
            c.setStrokeWidth(2);
            c.getStyleClass().add("swatch");
            c.setAccessibleText(p.name());
            c.setOnMouseClicked(e -> applyPalette(p));
            swatches.add(c);
            row.getChildren().add(c);
        }
        return row;
    }

    // ----------------------------------------------------------------- input

    private void keys() {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (!e.isShortcutDown() && key(e.getCode())) {
                e.consume();
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
                keySteer = 0;
            }
        });
    }

    private boolean key(KeyCode code) {
        switch (code) {
            case H -> togglePanels();
            case F, F11 -> stage.setFullScreen(!stage.isFullScreen());
            case SPACE, P -> paused = !paused;
            case B -> boost();
            case M -> dance.setSelected(!dance.isSelected());
            case LEFT -> keySteer = -1;
            case RIGHT -> keySteer = 1;
            case DIGIT1, DIGIT2, DIGIT3, DIGIT4 -> applyPalette(PALETTES.get(code.ordinal() - KeyCode.DIGIT1.ordinal()));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void mouse(Node root) {
        root.addEventHandler(MouseEvent.MOUSE_MOVED, e -> steer = e.getSceneX() / Math.max(1, scene.getWidth()));
        root.addEventHandler(MouseEvent.MOUSE_EXITED, e -> steer = Double.NaN);
        root.addEventHandler(ScrollEvent.SCROLL, e -> speed.setValue(speed.getValue() + e.getDeltaY() * 4));
    }

    private void boost() {
        boostUntil = time + 2.6;
    }

    // ------------------------------------------------------------ simulation

    private void seedSky() {
        for (double[] s : stars) {
            s[0] = random.nextDouble();
            s[1] = Math.pow(random.nextDouble(), 1.6);
            s[2] = random.nextDouble() * Math.PI * 2;
        }
        for (double[] s : streaks) {
            s[0] = random.nextDouble() * Math.PI * 2;
            s[1] = random.nextDouble();
            s[2] = 0.4 + random.nextDouble();
        }
    }

    private void step(double dt) {
        boost = Math.clamp(boost + (time < boostUntil ? dt * 1.6 : -dt * 0.8), 0, 1);
        double ease = boost * boost * (3 - 2 * boost);
        distance += speed.getValue() * (1 + 2.4 * ease) * dt;
        camera.setFieldOfView(55 + 22 * ease);
        stepBeat(dt);
        stepCamera(dt);
        stepMeteors(dt);
    }

    private void stepBeat(double dt) {
        double before = beat;
        beat += dt * bpm.getValue() / 60;
        if (Math.floor(beat) != Math.floor(before)) {
            onBeat((long) Math.floor(beat));
        }
        double phase = beat - Math.floor(beat);
        pulse = Math.exp(-phase * 5);
        double follow = Math.min(1, dt * 12);
        double settle = Math.min(1, dt * 1.8);
        targetLeft = lerp(targetLeft, 0.35, settle);
        targetRight = lerp(targetRight, 0.35, settle);
        danceLeft += (targetLeft - danceLeft) * follow;
        danceRight += (targetRight - danceRight) * follow;
    }

    // The kick on the left range, the snare on the right, and both on the first beat of a bar
    private void onBeat(long count) {
        boolean kick = count % 2 == 0;
        boolean bar = count % 4 == 0;
        targetLeft = kick || bar ? 0.85 + random.nextDouble() * 0.15 : 0.45 + random.nextDouble() * 0.2;
        targetRight = !kick || bar ? 0.85 + random.nextDouble() * 0.15 : 0.45 + random.nextDouble() * 0.2;
        if (count % 8 == 0 && random.nextDouble() < 0.5) {
            meteor();
        }
    }

    private void stepCamera(double dt) {
        double reach = columnX(COLS / 2 + ROAD) - 25;
        double target = keySteer != 0 ? camX + keySteer * reach
                : Double.isNaN(steer) ? Math.sin(time * 0.27) * reach * 0.6 : (steer - 0.5) * 2 * reach;
        target = Math.clamp(target, -reach, reach);
        double accel = (target - camX) * 5 - camVX * 3.2;
        camVX += accel * dt;
        camX += camVX * dt;
        double bob = Math.sin(time * 1.7) * 1.5 + pulse * 2.5 * (dance.isSelected() ? 1 : 0);
        cameraAt.setX(camX);
        cameraAt.setY(-height3d.getValue() - bob);
        cameraAt.setZ(-60);
        roll.setAngle(Math.clamp(camVX * 0.025, -MAX_ROLL, MAX_ROLL));
        gridLight.setColor(Color.gray(Math.min(1, 0.72 + 0.28 * pulse * (dance.isSelected() ? 1 : 0.3))));
        groundLight.setColor(Color.gray(0.85));
    }

    private void meteor() {
        double angle = Math.toRadians(150 + random.nextDouble() * 30);
        double v = 0.9 + random.nextDouble() * 0.8;
        meteors.add(new double[] {0.2 + random.nextDouble() * 0.8, random.nextDouble() * 0.35,
                Math.cos(angle) * v, Math.sin(angle) * v * 0.35, 0});
    }

    private void stepMeteors(double dt) {
        if (random.nextDouble() < dt * 0.12) {
            meteor();
        }
        for (double[] m : meteors) {
            m[0] += m[2] * dt * 0.6;
            m[1] += m[3] * dt * 0.6;
            m[4] += dt;
        }
        meteors.removeIf(m -> m[4] > 1.4);
    }

    // ----------------------------------------------------------------- the sky

    // The height of the horizon on the window, where the camera looks a little down
    private double horizonOnScreen() {
        double f = height / 2 / Math.tan(Math.toRadians(camera.getFieldOfView() / 2));
        return height / 2 - Math.tan(Math.toRadians(PITCH)) * f;
    }

    private void drawSky() {
        GraphicsContext g = sky.getGraphicsContext2D();
        double w = width;
        double h = height;
        g.clearRect(0, 0, w, h);
        // The canvas is scaled around its center, so the horizon on it is closer to the center
        double horizon = h / 2 + (horizonOnScreen() - h / 2) / SKY_SCALE;
        sky.setRotate(-roll.getAngle());
        double a = backdrop ? tint.getValue() : 1;
        g.setGlobalAlpha(1);
        g.setFill(new LinearGradient(0, 0, 0, horizon, false, CycleMethod.NO_CYCLE,
                new Stop(0, alpha(palette.skyTop(), a)),
                new Stop(0.6, alpha(palette.skyTop().interpolate(palette.skyHorizon(), 0.45), Math.min(1, a + 0.2))),
                new Stop(1, palette.skyHorizon())));
        g.fillRect(0, 0, w, horizon);
        if (starsOn.isSelected()) {
            drawStars(g, horizon);
            drawMeteors(g, horizon);
        }
        if (sunOn.isSelected()) {
            drawSun(g, w / 2, horizon);
        }
        if (rangeOn.isSelected()) {
            drawRange(g, horizon, 0.11, 0.35, 3);
            drawRange(g, horizon, 0.065, 0.7, 7);
        }
        g.setFill(new LinearGradient(0, horizon, 0, h, false, CycleMethod.NO_CYCLE,
                new Stop(0, palette.haze()), new Stop(0.05, palette.haze().deriveColor(0, 1, 0.35, 1)),
                new Stop(1, palette.ground())));
        g.fillRect(0, horizon, w, h - horizon);
        g.setFill(new LinearGradient(0, horizon - h * 0.06, 0, horizon + 2, false, CycleMethod.NO_CYCLE,
                new Stop(0, Color.TRANSPARENT), new Stop(1, alpha(palette.haze(), 0.55))));
        g.fillRect(0, horizon - h * 0.06, w, h * 0.06 + 2);
    }

    private static Color alpha(Color c, double a) {
        return Color.color(c.getRed(), c.getGreen(), c.getBlue(), Math.clamp(a, 0, 1));
    }

    private void drawStars(GraphicsContext g, double horizon) {
        g.setFill(Color.WHITE);
        for (double[] s : stars) {
            double y = s[1] * horizon * 0.85;
            double size = 1 + (s[2] % 1) * 1.4;
            g.setGlobalAlpha(Math.max(0, (0.35 + 0.55 * Math.sin(time * 2.1 + s[2] * 7)) * (1 - y / horizon)));
            g.fillRect(s[0] * width, y, size, size);
        }
        g.setGlobalAlpha(1);
    }

    private void drawMeteors(GraphicsContext g, double horizon) {
        g.setLineWidth(1.6);
        for (double[] m : meteors) {
            double x = m[0] * width;
            double y = m[1] * horizon;
            double tx = x - m[2] * width * 0.12;
            double ty = y - m[3] * horizon * 0.12;
            double fade = Math.sin(Math.PI * Math.min(1, m[4] / 1.4));
            g.setStroke(new LinearGradient(x, y, tx, ty, false, CycleMethod.NO_CYCLE,
                    new Stop(0, alpha(Color.WHITE, fade)), new Stop(1, Color.TRANSPARENT)));
            g.strokeLine(x, y, tx, ty);
        }
    }

    // The sun, cut by stripes that widen toward the horizon and slide down
    private void drawSun(GraphicsContext g, double cx, double horizon) {
        double r = height * 0.21 * (1 + 0.025 * pulse * (dance.isSelected() ? 1 : 0));
        double cy = horizon - r * 0.42;
        g.setFill(new RadialGradient(0, 0, cx, cy, r * 2.6, false, CycleMethod.NO_CYCLE,
                new Stop(0, alpha(palette.sunBottom(), 0.45)), new Stop(0.4, alpha(palette.sunBottom(), 0.12)),
                new Stop(1, Color.TRANSPARENT)));
        g.fillRect(cx - r * 2.6, cy - r * 2.6, r * 5.2, Math.min(r * 5.2, horizon - cy + r * 2.6));

        g.save();
        g.beginPath();
        g.arc(cx, cy, r, r, 0, 360);
        g.closePath();
        g.clip();
        g.setFill(new LinearGradient(0, cy - r, 0, cy + r, false, CycleMethod.NO_CYCLE,
                new Stop(0, palette.sunTop()), new Stop(0.55, palette.sunBottom().interpolate(palette.sunTop(), 0.35)),
                new Stop(1, palette.sunBottom())));
        double start = cy - r * 0.05;
        g.fillRect(cx - r, cy - r, r * 2, start - (cy - r));
        double period = r * 0.16;
        double slide = (time * r * 0.06) % period;
        for (double y = start - period + slide; y < horizon; y += period) {
            double gap = period * (0.1 + 0.55 * Math.clamp((y - start) / (r * 0.95), 0, 1));
            double top = Math.max(start, y + gap);
            double bottom = Math.min(horizon, y + period);
            if (bottom > top) {
                g.fillRect(cx - r, top, r * 2, bottom - top);
            }
        }
        g.restore();
    }

    // A far range of mountains on the horizon, which moves a little as the camera steers
    private void drawRange(GraphicsContext g, double horizon, double tall, double near, int seed) {
        int n = 90;
        double[] xs = new double[n + 2];
        double[] ys = new double[n + 2];
        double shift = -camX * 0.04 * near;
        for (int i = 0; i < n; i++) {
            double t = i / (n - 1.0);
            xs[i] = -width * 0.1 + t * width * 1.2 + shift;
            double crest = ridges(t * 7 + seed * 13.1, seed * 3.7);
            double middle = 0.35 + 0.65 * smoothstep(0.05, 0.3, Math.abs(t - 0.5));
            ys[i] = horizon - crest * crest * height * tall * middle * 1.8;
        }
        xs[n] = xs[n - 1];
        ys[n] = horizon + 1;
        xs[n + 1] = xs[0];
        ys[n + 1] = horizon + 1;
        Color base = palette.skyHorizon().interpolate(palette.skyTop(), near);
        g.setFill(new LinearGradient(0, horizon - height * tall, 0, horizon, false, CycleMethod.NO_CYCLE,
                new Stop(0, base), new Stop(1, base.interpolate(palette.haze(), 0.25))));
        g.fillPolygon(xs, ys, n + 2);
        g.setStroke(alpha(palette.low(), 0.35 + 0.4 * near));
        g.setLineWidth(1.2);
        g.strokePolyline(xs, ys, n);
    }

    // -------------------------------------------------------------- the screen

    private void drawScreen() {
        GraphicsContext g = screen.getGraphicsContext2D();
        g.clearRect(0, 0, width, height);
        if (boost > 0.02) {
            drawStreaks(g);
        }
        g.setGlobalAlpha(1);
        g.setFill(new RadialGradient(0, 0, width / 2, height / 2, Math.hypot(width, height) * 0.6, false,
                CycleMethod.NO_CYCLE, new Stop(0.55, Color.TRANSPARENT), new Stop(1, Color.color(0, 0, 0, 0.55))));
        g.fillRect(0, 0, width, height);
        if (scanlines.isSelected()) {
            g.setFill(Color.color(0, 0, 0, 0.16));
            for (double y = 0; y < height; y += 3) {
                g.fillRect(0, y, width, 1);
            }
        }
    }

    // The stars of the boost, rushing from the point where the road meets the horizon
    private void drawStreaks(GraphicsContext g) {
        double cx = width / 2;
        double cy = horizonOnScreen();
        double reach = Math.hypot(width, height) * 0.6;
        g.setStroke(palette.high().interpolate(Color.WHITE, 0.5));
        for (double[] s : streaks) {
            double t = (s[1] + time * s[2] * 1.4) % 1;
            double r0 = reach * t * t;
            double r1 = r0 + reach * 0.08 * t * boost;
            g.setGlobalAlpha(boost * t * 0.8);
            g.setLineWidth(0.6 + t * 2);
            g.strokeLine(cx + Math.cos(s[0]) * r0, cy + Math.sin(s[0]) * r0,
                    cx + Math.cos(s[0]) * r1, cy + Math.sin(s[0]) * r1);
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
        width = screen.getWidth();
        height = screen.getHeight();
        if (width < 1 || height < 1) {
            return;
        }
        double step = paused ? 0 : Math.min(dt, 0.05);
        time += step;
        if (step > 0) {
            step(step);
        }
        updateTerrain();
        drawSky();
        drawScreen();
    }

    private void report(double fps, double average, double worst, boolean print) {
        fpsValue.setText(String.format(Locale.ROOT, "%.0f", fps));
        frameValue.setText(String.format(Locale.ROOT, "%.1f", average));
        worstValue.setText(String.format(Locale.ROOT, "%.1f", worst));
        double ease = boost * boost * (3 - 2 * boost);
        speedValue.setText(String.format(Locale.ROOT, "%.0f", speed.getValue() * (1 + 2.4 * ease) * 0.25));
        bpmValue.setText(String.format(Locale.ROOT, "%.0f", bpm.getValue()));
        double scale = stage.getOutputScaleX();
        sizeValue.setText(String.format(Locale.ROOT, "%.0f×%.0f @%.2f · %d chunks · %d posts",
                scene.getWidth(), scene.getHeight(), scale, CHUNKS, POSTS * 2));
        if (print) {
            System.out.printf(Locale.ROOT, "synthwave: %.0f fps, %.1f ms avg, %.1f ms worst%n", fps, average, worst);
        }
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
