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

package com.sun.prism.gl;

import com.sun.glass.ui.Screen;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.ResourceFactory;
import com.sun.prism.impl.PrismSettings;

import java.util.HashMap;
import java.util.List;

/**
 * The Prism pipeline for OpenGL 4.5 core, which uses EGL, GBM and OpenGL
 * through the foreign function and memory API.
 */
public class GLPipeline extends GraphicsPipeline {

    private static final GLPipeline theInstance = new GLPipeline();
    private static GLDevice device;
    private static GLResourceFactory factory;

    private GLPipeline() {
    }

    public static GLPipeline getInstance() {
        return theInstance;
    }

    @Override
    public boolean init() {
        try {
            device = GLContext.createDevice();
        } catch (Throwable t) {
            // No OpenGL loader, or native access is not enabled
            System.err.println("GLPipeline: cannot load EGL, GBM or OpenGL: " + t);
            return false;
        }
        if (device == null) {
            String reason = GLDevice.getFailure();
            System.err.println("GLPipeline: OpenGL is not available: "
                    + (reason != null ? reason : "no OpenGL 4.5 core device"));
            return false;
        }
        setDeviceDetails(new HashMap<String, Object>());
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

    @Override
    public ResourceFactory getResourceFactory(Screen screen) {
        // One device renders for all the screens; the screens are known
        // once Glass is initialized, after the pipeline
        if (factory == null && device != null) {
            factory = new GLResourceFactory(screen, device);
            if (PrismSettings.verbose) {
                System.err.println("GLPipeline: using " + factory.getContext().getDeviceName());
            }
        }
        return factory;
    }

    @Override
    public void dispose() {
        if (factory != null) {
            // Destroys the device
            factory.dispose();
            factory = null;
        } else if (device != null) {
            device.destroy();
        }
        device = null;
        super.dispose();
    }

    @Override
    public boolean is3DSupported() {
        return device != null && device.is3DSupported();
    }

    @Override
    public boolean isMSAASupported() {
        return device != null && device.getMSAASamples() > 1;
    }

    @Override
    public boolean isVsyncSupported() {
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
