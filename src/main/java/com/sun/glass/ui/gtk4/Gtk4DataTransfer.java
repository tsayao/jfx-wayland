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
package com.sun.glass.ui.gtk4;

import com.sun.glass.ui.Clipboard;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.gtk4.wrappers.GBytes;
import com.sun.glass.ui.gtk4.wrappers.GErrorException;
import com.sun.glass.ui.gtk4.wrappers.GInputStream;
import com.sun.glass.ui.gtk4.wrappers.GLib;
import com.sun.glass.ui.gtk4.wrappers.GMemoryOutputStream;
import com.sun.glass.ui.gtk4.wrappers.Gdk;
import com.sun.glass.ui.gtk4.wrappers.GdkContentFormats;
import com.sun.glass.ui.gtk4.wrappers.GdkContentProvider;
import com.sun.glass.ui.gtk4.wrappers.GdkTexture;
import com.sun.glass.ui.gtk4.wrappers.GdkTextureDownloader;

import java.lang.foreign.MemorySegment;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conversion between Glass data (as used by the clipboard and drag and drop)
 * and GDK content, following the GTK 3 toolkit.
 */
final class Gtk4DataTransfer {

    private static final Gtk4Log.Category CLIPBOARD = Gtk4Log.Category.CLIPBOARD;

    private static final String TEXT_UTF8 = "text/plain;charset=utf-8";
    private static final String FILE_PREFIX = "file://";
    private static final String[] IMAGE_MIMES = { "image/png", "image/jpeg", "image/tiff", "image/bmp" };
    private static final int G_OUTPUT_STREAM_SPLICE_CLOSE_SOURCE = 1;
    private static final int G_OUTPUT_STREAM_SPLICE_CLOSE_TARGET = 2;

    private Gtk4DataTransfer() {
    }

    /**
     * A source of GDK content: a clipboard or a drop.
     */
    interface Source {
        GdkContentFormats getFormats();

        /**
         * Starts reading the content in one of the given formats.
         */
        void readAsync(String[] mimeTypes, MemorySegment callback);

        GInputStream readFinish(com.sun.glass.ui.gtk4.wrappers.GAsyncResult result);
    }

    // ---------------------------------------------------------------------
    // Writing

    /**
     * Creates a content provider that offers the Glass data in the formats
     * other applications understand.
     */
    static GdkContentProvider createProvider(Map<String, Object> data) {
        List<GdkContentProvider> providers = new ArrayList<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            addProviders(providers, entry.getKey(), entry.getValue());
        }
        String uriList = toUriList(data.get(Clipboard.URI_TYPE), data.get(Clipboard.FILE_LIST_TYPE));
        if (uriList != null) {
            providers.add(bytesProvider(Clipboard.URI_TYPE, uriList.getBytes(StandardCharsets.UTF_8)));
        }
        // The union owns the providers
        return GdkContentProvider.createUnion(providers.toArray(new GdkContentProvider[0]));
    }

    /**
     * The providers of a format of Glass: text as UTF-8 under two names, an
     * image as PNG, and the other formats as their bytes. The URIs and the
     * files are offered together, as one text/uri-list.
     */
    private static void addProviders(List<GdkContentProvider> providers, String mime, Object value) {
        switch (mime) {
            case Clipboard.TEXT_TYPE -> {
                if (value instanceof String text) {
                    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                    providers.add(bytesProvider(TEXT_UTF8, bytes));
                    providers.add(bytesProvider(Clipboard.TEXT_TYPE, bytes));
                }
            }
            case Clipboard.RAW_IMAGE_TYPE -> {
                if (value instanceof Pixels pixels) {
                    GdkContentProvider png = pngProvider(pixels);
                    if (png != null) {
                        providers.add(png);
                    }
                }
            }
            // Offered by createProvider; the drag image is for the drag source only
            case Clipboard.URI_TYPE, Clipboard.FILE_LIST_TYPE, Clipboard.DRAG_IMAGE, Clipboard.DRAG_IMAGE_OFFSET -> {
            }
            default -> {
                byte[] bytes = bytesOf(value);
                if (bytes != null) {
                    providers.add(bytesProvider(mime, bytes));
                }
            }
        }
    }

    private static GdkContentProvider bytesProvider(String mime, byte[] bytes) {
        GBytes gbytes = GBytes.create(bytes);
        GdkContentProvider provider = GdkContentProvider.createForBytes(mime, gbytes);
        gbytes.unref();
        return provider;
    }

    private static GdkContentProvider pngProvider(Pixels pixels) {
        GdkTexture texture = Gtk4Pixels.toTexture(pixels);
        if (texture == null) {
            return null;
        }
        GBytes png = texture.saveToPngBytes();
        GdkContentProvider provider = GdkContentProvider.createForBytes("image/png", png);
        png.unref();
        texture.unref();
        return provider;
    }

    /** The bytes of a value of a custom format: a text as UTF-8, or a buffer. */
    private static byte[] bytesOf(Object value) {
        return switch (value) {
            case String text -> text.getBytes(StandardCharsets.UTF_8);
            case ByteBuffer buffer -> {
                ByteBuffer copy = buffer.duplicate().rewind();
                byte[] bytes = new byte[copy.remaining()];
                copy.get(bytes);
                yield bytes;
            }
            case null, default -> null;
        };
    }

    private static String toUriList(Object url, Object files) {
        List<String> uris = new ArrayList<>();
        if (files instanceof String[] paths) {
            for (String path : paths) {
                if (path != null) {
                    uris.add(Path.of(path).toUri().toString());
                }
            }
        }
        if (url instanceof String s && !s.isEmpty()) {
            uris.add(s);
        }
        // RFC 2483: lines are terminated by CRLF
        return uris.isEmpty() ? null : String.join("\r\n", uris) + "\r\n";
    }

    // ---------------------------------------------------------------------
    // Reading

    /**
     * The Glass mime types available from a source.
     */
    static String[] getMimeTypes(Source source) {
        GdkContentFormats formats = source.getFormats();
        if (formats.isNull()) {
            return new String[0];
        }
        Set<String> result = new LinkedHashSet<>();
        boolean hasImage = false;
        for (String mime : formats.getMimeTypes(null)) {
            if (isTextType(mime)) {
                result.add(Clipboard.TEXT_TYPE);
            } else if (mime.equals(Clipboard.URI_TYPE)) {
                addUriTypes(result, readUris(source));
            } else {
                hasImage |= mime.startsWith("image/");
                result.add(mime);
            }
        }
        if (hasImage || formats.containGtype(GdkTexture.getGType())) {
            result.add(Clipboard.RAW_IMAGE_TYPE);
        }
        return result.toArray(new String[0]);
    }

    private static boolean isTextType(String mime) {
        return mime.startsWith("text/plain") || mime.equals("UTF8_STRING") || mime.equals("STRING")
                || mime.equals("TEXT");
    }

    /** A URI list is a file list of Glass when it has files, and a URI list when it has other URIs. */
    private static void addUriTypes(Set<String> result, List<String> uris) {
        if (uris.stream().anyMatch(u -> u.startsWith(FILE_PREFIX))) {
            result.add(Clipboard.FILE_LIST_TYPE);
        }
        if (uris.stream().anyMatch(u -> !u.startsWith(FILE_PREFIX))) {
            result.add(Clipboard.URI_TYPE);
        }
    }

    /**
     * Moves the raw image to the front of the mime types. JavaFX 27.0.1
     * answers whether there is an image from the first of text/html and the
     * raw image it finds, so an HTML entry listed first hides the image.
     */
    static String[] rawImageFirst(String[] mimes) {
        if (mimes == null) {
            return null;
        }
        List<String> result = new ArrayList<>(mimes.length);
        for (String mime : mimes) {
            if (Clipboard.RAW_IMAGE_TYPE.equals(mime)) {
                result.add(0, mime);
            } else {
                result.add(mime);
            }
        }
        return result.toArray(new String[0]);
    }

    /**
     * Reads the content of a source as the Glass value for a mime type.
     */
    static Object read(Source source, String mime) {
        try {
            return switch (mime) {
                case Clipboard.TEXT_TYPE -> readText(source);
                case Clipboard.URI_TYPE -> readNonFileUris(source);
                case Clipboard.FILE_LIST_TYPE -> readFiles(source);
                case Clipboard.RAW_IMAGE_TYPE -> readImage(source);
                default -> readCustom(source, mime);
            };
        } catch (GErrorException | IllegalArgumentException e) {
            Gtk4Log.log(CLIPBOARD, "read %s failed: %s", mime, e.getMessage());
            return null;
        }
    }

    private static String readText(Source source) {
        byte[] bytes = readBytes(source, TEXT_UTF8, Clipboard.TEXT_TYPE, "UTF8_STRING", "STRING");
        return bytes != null ? new String(bytes, StandardCharsets.UTF_8) : null;
    }

    /** The URIs that are not files, CRLF separated. */
    private static String readNonFileUris(Source source) {
        List<String> uris = readUris(source).stream().filter(u -> !u.startsWith(FILE_PREFIX)).toList();
        return uris.isEmpty() ? null : String.join("\r\n", uris);
    }

    /** The paths of the URIs that are files. */
    private static String[] readFiles(Source source) {
        String[] files = readUris(source).stream()
                .filter(u -> u.startsWith(FILE_PREFIX))
                .map(u -> Path.of(URI.create(u)).toString())
                .toArray(String[]::new);
        return files.length == 0 ? null : files;
    }

    /** A custom format: a text when its type is text, or else a buffer of its bytes. */
    private static Object readCustom(Source source, String mime) {
        byte[] bytes = readBytes(source, mime);
        if (bytes == null) {
            return null;
        }
        return mime.startsWith("text/") ? new String(bytes, StandardCharsets.UTF_8) : ByteBuffer.wrap(bytes);
    }

    private static List<String> readUris(Source source) {
        byte[] bytes = readBytes(source, Clipboard.URI_TYPE);
        List<String> uris = new ArrayList<>();
        if (bytes != null) {
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\r?\n")) {
                // Lines starting with # are comments (RFC 2483)
                if (!line.isBlank() && !line.startsWith("#")) {
                    uris.add(line.trim());
                }
            }
        }
        return uris;
    }

    private static Pixels readImage(Source source) {
        byte[] bytes = readBytes(source, IMAGE_MIMES);
        if (bytes == null) {
            return null;
        }
        GBytes gbytes = GBytes.create(bytes);
        try {
            GdkTexture texture = GdkTexture.createFromBytes(gbytes);
            try {
                return toPixels(texture);
            } finally {
                texture.unref();
            }
        } finally {
            gbytes.unref();
        }
    }

    /**
     * Downloads a texture as premultiplied BGRA pixels.
     */
    static Pixels toPixels(GdkTexture texture) {
        int width = texture.getWidth();
        int height = texture.getHeight();
        GdkTextureDownloader downloader = GdkTextureDownloader.create(texture);
        downloader.setFormat(Gdk.MEMORY_B8G8R8A8_PREMULTIPLIED);
        long[] stride = new long[1];
        GBytes bytes = downloader.downloadBytes(stride);
        downloader.free();
        byte[] data = bytes.getData();
        bytes.unref();

        int rowBytes = width * 4;
        byte[] pixels = data;
        if (stride[0] != rowBytes) {
            pixels = new byte[rowBytes * height];
            for (int y = 0; y < height; y++) {
                System.arraycopy(data, (int) (y * stride[0]), pixels, y * rowBytes, rowBytes);
            }
        }
        return new Gtk4Pixels(width, height, ByteBuffer.wrap(pixels));
    }

    /**
     * Reads the content in the first available of the given formats.
     */
    /** The content of the first of the types that the source has, or null. */
    private static byte[] readBytes(Source source, String... mimeTypes) {
        GdkContentFormats formats = source.getFormats();
        String[] available = Arrays.stream(mimeTypes).filter(formats::containMimeType).toArray(String[]::new);
        if (available.length == 0) {
            return null;
        }
        GInputStream input = Gtk4Async.await(cb -> source.readAsync(available, cb), source::readFinish);
        if (input.isNull()) {
            return null;
        }
        try {
            return readAll(input);
        } finally {
            input.unref();
        }
    }

    /**
     * Reads a stream to its end, asynchronously: when the application owns
     * the content, the data is written from this same main loop.
     */
    private static byte[] readAll(GInputStream input) {
        GMemoryOutputStream output = GMemoryOutputStream.createResizable();
        try {
            Gtk4Async.await(
                    cb -> output.spliceAsync(input,
                            G_OUTPUT_STREAM_SPLICE_CLOSE_SOURCE | G_OUTPUT_STREAM_SPLICE_CLOSE_TARGET,
                            GLib.PRIORITY_DEFAULT, null, cb, null),
                    output::spliceFinish);
            GBytes bytes = output.stealAsBytes();
            byte[] data = bytes.getData();
            bytes.unref();
            return data;
        } finally {
            output.unref();
        }
    }
}
