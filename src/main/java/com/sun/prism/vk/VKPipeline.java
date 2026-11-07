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

package com.sun.prism.vk;

import com.sun.glass.ui.Screen;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.ResourceFactory;
import com.sun.prism.impl.PrismSettings;

import java.util.HashMap;
import java.util.List;

/**
 * The Prism pipeline for Vulkan 1.3, which uses Vulkan through the foreign
 * function and memory API.
 */
public class VKPipeline extends GraphicsPipeline {

    private static final VKPipeline theInstance = new VKPipeline();
    private static VKDevice device;
    private static VKResourceFactory factory;

    private VKPipeline() {
    }

    public static VKPipeline getInstance() {
        return theInstance;
    }

    @Override
    public boolean init() {
        try {
            device = VKContext.createDevice();
        } catch (Throwable t) {
            // No Vulkan loader, or native access is not enabled
            System.err.println("VKPipeline: cannot load Vulkan (libvulkan.so.1): " + t);
            return false;
        }
        if (device == null) {
            String reason = VKDevice.getFailure();
            System.err.println("VKPipeline: Vulkan is not available: "
                    + (reason != null ? reason : "no usable Vulkan 1.3 device"));
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
            factory = new VKResourceFactory(screen, device);
            if (PrismSettings.verbose) {
                System.err.println("VKPipeline: using " + factory.getContext().getDeviceName());
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
