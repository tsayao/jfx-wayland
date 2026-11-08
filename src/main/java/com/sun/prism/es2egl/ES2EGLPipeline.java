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

package com.sun.prism.es2egl;

import com.sun.glass.ui.Screen;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.ResourceFactory;
import com.sun.prism.impl.PrismSettings;

import java.util.HashMap;
import java.util.List;

/**
 * The ES2 pipeline of JavaFX on EGL, with the natives of the egl branch
 * written over the foreign function and memory API: OpenGL 2.1, of the
 * compatibility profile, on the render node of the GPU, without a window
 * surface. Selected with -Dprism.order=es2egl.
 */
public final class ES2EGLPipeline extends GraphicsPipeline {

    private static final ES2EGLPipeline INSTANCE = new ES2EGLPipeline();

    private GLFactory factory;
    private GLContext glContext;
    private ES2ResourceFactory resourceFactory;

    private ES2EGLPipeline() {
    }

    public static ES2EGLPipeline getInstance() {
        return INSTANCE;
    }

    /** Opens the display and creates the context, on the render thread. */
    @Override
    public boolean init() {
        try {
            factory = GLFactory.create(GLPixelFormat.fromSettings(), PrismSettings.verbose);
        } catch (IllegalStateException | UnsupportedOperationException e) {
            System.err.println("ES2EGLPipeline: OpenGL is not available: " + e.getMessage());
            return false;
        } catch (Throwable t) {
            // No EGL library, or native access is not enabled
            System.err.println("ES2EGLPipeline: cannot load EGL, GBM or OpenGL: " + t);
            return false;
        }
        glContext = new GLContext(factory);
        setDeviceDetails(new HashMap<>());
        return true;
    }

    @Override
    public int getAdapterOrdinal(Screen screen) {
        return 0;
    }

    @Override
    public ResourceFactory getDefaultResourceFactory(List<Screen> screens) {
        return getResourceFactory(Screen.getMainScreen());
    }

    /**
     * The one resource factory: one context renders for all the screens,
     * which are known once Glass is initialized, after the pipeline.
     */
    @Override
    public ResourceFactory getResourceFactory(Screen screen) {
        if (resourceFactory == null && glContext != null) {
            resourceFactory = new ES2ResourceFactory(screen, glContext);
            if (PrismSettings.verbose) {
                factory.printDriverInformation();
            }
        }
        return resourceFactory;
    }

    @Override
    public void dispose() {
        if (resourceFactory != null) {
            resourceFactory.dispose();
            resourceFactory = null;
        }
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        glContext = null;
        super.dispose();
    }

    @Override
    public boolean is3DSupported() {
        return factory != null && factory.isNPOTSupported();
    }

    @Override
    public boolean isMSAASupported() {
        return factory != null && factory.isMultisampleSupported();
    }

    @Override
    public boolean isVsyncSupported() {
        // The compositor paces the frames
        return false;
    }

    @Override
    public boolean supportsShaderType(ShaderType type) {
        return type == ShaderType.GLSL;
    }

    @Override
    public boolean supportsShaderModel(ShaderModel model) {
        return model == ShaderModel.SM3;
    }
}
