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

import com.sun.prism.vk.bindings.*;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.sun.prism.vk.bindings.VK.*;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The Vulkan device of the Prism VK pipeline, used through the foreign
 * function and memory API.
 * <p>
 * Rendering is recorded into the command buffer of the current frame, in the
 * order Prism issues the operations. Rendering into a target uses dynamic
 * rendering; operations that cannot happen inside it (uploads, copies, layout
 * transitions) end it, and the next draw starts it again, loading the target.
 * Two frames are in flight: the host data of a frame (vertices, uniforms,
 * staging) lives in its half of a ring buffer, and the resources it uses are
 * destroyed once its fence is signaled. A presented frame is synchronized
 * with the compositor through a sync file attached to the exported dma-buf.
 */
final class VKDevice {

    static final int MAX_TEXTURE_UNITS = 4;
    private static final int UNIFORM_BINDING = 0;
    private static final int FIRST_SAMPLER_BINDING = 1;
    // x, y, z, u0, v0, u1, v1
    private static final int VERTEX_FLOATS = 7;
    private static final int VERTEX_SIZE = VERTEX_FLOATS * 4;
    // x, y, z, u, v and the tangent frame quaternion
    static final int MESH_VERTEX_FLOATS = 9;
    private static final int MAX_QUADS = 16383;
    private static final long RING_SIZE = 16L * 1024 * 1024;
    private static final int FRAMES = 2;

    static final int COMP_CLEAR = 0;
    static final int COMP_SRC = 1;
    static final int COMP_SRCOVER = 2;
    static final int COMP_DSTOUT = 3;
    static final int COMP_ADD = 4;
    static final int COMP_COUNT = 5;

    static final int WRAP_CLAMP_TO_EDGE = 0;
    static final int WRAP_REPEAT = 1;
    static final int WRAP_CLAMP_TO_ZERO = 2;
    private static final int WRAP_COUNT = 3;

    static final int CULL_NONE = 0;
    static final int CULL_BACK = 1;
    static final int CULL_FRONT = 2;

    /** The size of the uniform block of the Phong shaders. */
    static final int PHONG_UNIFORM_FLOATS = 108;

    // DRM_FORMAT_ARGB8888: B, G, R, A in memory
    static final long DRM_FORMAT_ARGB8888 = 0x34325241L;
    static final long DRM_FORMAT_MOD_LINEAR = 0L;

    // struct dma_buf_import_sync_file and its ioctl
    private static final int DMA_BUF_SYNC_WRITE = 2;
    private static final long DMA_BUF_IOCTL_IMPORT_SYNC_FILE = 0x40086203L;

    private static final MethodHandle CLOSE;
    private static final MethodHandle IOCTL;

    static {
        Linker linker = Linker.nativeLinker();
        var libc = linker.defaultLookup();
        CLOSE = linker.downcallHandle(libc.find("close").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        IOCTL = linker.downcallHandle(libc.find("ioctl").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2));
    }

    private static boolean verbose;
    private static volatile String failure;

    private final VKScratch scratch = new VKScratch();
    private final Arena arena = Arena.ofShared();

    private long instance;
    private long physicalDevice;
    private long device;
    private long queue;
    private int queueFamily;
    private final MemorySegment properties = VkPhysicalDeviceProperties.allocate(arena);
    private final MemorySegment memoryProperties = VkPhysicalDeviceMemoryProperties.allocate(arena);
    private String deviceName = "";
    private boolean exportSupported;
    private boolean foreignQueueSupported;
    private boolean syncFileSupported;
    private boolean fillModeNonSolid;
    private int depthFormat = VK_FORMAT_UNDEFINED;
    private int msaaSamples = 1;

    private static final class Frame {
        long commandPool;
        long cmd;
        long fence;
        boolean pending;
        // Released when the frame has executed
        final List<Runnable> releases = new ArrayList<>();
        // Staging for the uploads larger than the ring, reused by the next frames
        long stagingBuffer;
        long stagingMemory;
        MemorySegment stagingMapped;
        long stagingSize;
        long stagingOffset;
    }

    private final Frame[] frames = new Frame[FRAMES];
    private int frameIndex;
    // The command buffer of the current frame
    private long cmd;
    private boolean recording;
    // Signaled by the presented frames, exported as sync files
    private long presentSemaphore;

    // Each frame uses its own half of the ring
    private long ringBuffer;
    private long ringMemory;
    private MemorySegment ringMapped;
    private long ringOffset;
    private long ringLimit;

    private long quadIndexBuffer;
    private long quadIndexMemory;
    private long setLayout;
    private long pipelineLayout;
    private long vertexModule;
    private final long[][] samplers = new long[2][WRAP_COUNT];
    private long minUniformAlignment = 256;

    private long phongVertexModule;
    private long phongFragmentModule;
    private final Map<Integer, Long> phongPipelines = new HashMap<>();
    private VKImage dummyTexture;

    // Rendering state
    private VKImage target;
    private boolean rendering;
    private boolean clipEnabled;
    private int clipX;
    private int clipY;
    private int clipW;
    private int clipH;
    private final MemorySegment transform = arena.allocate(64, 16);
    private int compositeMode = COMP_SRCOVER;
    private VKShader shader;
    private final VKImage[] textures = new VKImage[MAX_TEXTURE_UNITS];
    private final long[] textureSamplers = new long[MAX_TEXTURE_UNITS];
    private boolean depthTest;
    // The dynamic state set in the current command buffer
    private int dynDepth = -1;
    private int dynCull = -1;

    // Statistics, reported at each present when PRISM_VK_STATS is set
    private final boolean stats = System.getenv("PRISM_VK_STATS") != null;
    private int statDraws;
    private int statFlushes;
    private int statTransitions;
    private int statUploads;
    private int statRenderPasses;

    private VKDevice() {
    }

    private static boolean fail(String format, Object... args) {
        failure = String.format(format, args);
        log(format, args);
        return false;
    }

    static String getFailure() {
        return failure;
    }

    private static void log(String format, Object... args) {
        if (verbose) {
            System.err.println("Prism VK: " + String.format(format, args));
        }
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new VKException(call + " failed: " + result);
        }
    }

    static final class VKException extends RuntimeException {
        VKException(String message) {
            super(message);
        }
    }

    // ---------------------------------------------------------------------
    // Initialization

    /**
     * Creates the device, or returns null if Vulkan 1.3 is not usable.
     */
    static VKDevice create(byte[] vertexShader, boolean validation, boolean isVerbose) {
        verbose = isVerbose;
        VKDevice d = new VKDevice();
        try {
            d.createInstance(validation);
            if (!d.createDevice()) {
                d.destroy();
                return null;
            }
            d.createResources(vertexShader);
            return d;
        } catch (Throwable t) {
            fail("cannot initialize Vulkan: %s", t);
            d.destroy();
            return null;
        }
    }

    private long handle(MemorySegment p) {
        return p.get(JAVA_LONG, 0);
    }

    private void createInstance(boolean validation) {
        long mark = scratch.mark();
        try {
            MemorySegment app = VkApplicationInfo.allocate(scratch);
            VkApplicationInfo.pApplicationName(app, scratch.allocateFrom("JavaFX"));
            VkApplicationInfo.pEngineName(app, scratch.allocateFrom("Prism"));
            VkApplicationInfo.apiVersion(app, VK_API_VERSION_1_3);
            MemorySegment info = VkInstanceCreateInfo.allocate(scratch);
            VkInstanceCreateInfo.pApplicationInfo(info, app);
            if (validation) {
                String layer = "VK_LAYER_KHRONOS_validation";
                if (hasInstanceLayer(layer)) {
                    MemorySegment layers = scratch.allocate(ADDRESS);
                    layers.set(ADDRESS, 0, scratch.allocateFrom(layer));
                    VkInstanceCreateInfo.enabledLayerCount(info, 1);
                    VkInstanceCreateInfo.ppEnabledLayerNames(info, layers);
                } else {
                    System.err.println("Prism VK error: validation layer not available");
                }
            }
            MemorySegment p = scratch.allocate(JAVA_LONG);
            check(vkCreateInstance(info, MemorySegment.NULL, p), "vkCreateInstance");
            instance = handle(p);
        } finally {
            scratch.reset(mark);
        }
    }

    private boolean hasInstanceLayer(String name) {
        MemorySegment count = scratch.allocate(JAVA_INT);
        vkEnumerateInstanceLayerProperties(count, MemorySegment.NULL);
        int n = count.get(JAVA_INT, 0);
        MemorySegment props = VkLayerProperties.allocateArray(scratch, Math.max(n, 1));
        vkEnumerateInstanceLayerProperties(count, props);
        for (int i = 0; i < n; i++) {
            if (VkLayerProperties.layerName(VkLayerProperties.at(props, i)).getString(0).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private int deviceScore(MemorySegment props) {
        if (Integer.compareUnsigned(VkPhysicalDeviceProperties.apiVersion(props), VK_API_VERSION_1_3) < 0) {
            return -1;
        }
        return switch (VkPhysicalDeviceProperties.deviceType(props)) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> 4;
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> 3;
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> 2;
            // Software rasterizers such as lavapipe are slower than Prism sw
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> -1;
            default -> 1;
        };
    }

    private static boolean hasExtension(List<String> extensions, String name) {
        return extensions.contains(name);
    }

    private boolean createDevice() {
        long mark = scratch.mark();
        try {
            MemorySegment count = scratch.allocate(JAVA_INT);
            vkEnumeratePhysicalDevices(instance, count, MemorySegment.NULL);
            int n = count.get(JAVA_INT, 0);
            if (n == 0) {
                return fail("no Vulkan devices");
            }
            MemorySegment devices = scratch.allocate(JAVA_LONG, n);
            vkEnumeratePhysicalDevices(instance, count, devices);

            String requested = System.getenv("PRISM_VK_DEVICE");
            int best = -1;
            int bestScore = -1;
            MemorySegment props = VkPhysicalDeviceProperties.allocate(scratch);
            for (int i = 0; i < n; i++) {
                vkGetPhysicalDeviceProperties(devices.getAtIndex(JAVA_LONG, i), props);
                String name = VkPhysicalDeviceProperties.deviceName(props).getString(0);
                int score = deviceScore(props);
                log("device %d: %s (score %d)", i, name, score);
                if (requested != null && name.contains(requested) && score >= 0) {
                    best = i;
                    break;
                }
                if (score > bestScore) {
                    best = i;
                    bestScore = score;
                }
            }
            if (best < 0) {
                return fail("no Vulkan 1.3 hardware device");
            }
            physicalDevice = devices.getAtIndex(JAVA_LONG, best);
            vkGetPhysicalDeviceProperties(physicalDevice, properties);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
            deviceName = VkPhysicalDeviceProperties.deviceName(properties).getString(0);

            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, MemorySegment.NULL);
            int familyCount = count.get(JAVA_INT, 0);
            MemorySegment families = VkQueueFamilyProperties.allocateArray(scratch, familyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
            int family = -1;
            for (int i = 0; i < familyCount; i++) {
                if ((VkQueueFamilyProperties.queueFlags(VkQueueFamilyProperties.at(families, i))
                        & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    family = i;
                    break;
                }
            }
            if (family < 0) {
                return fail("no graphics queue");
            }
            queueFamily = family;

            vkEnumerateDeviceExtensionProperties(physicalDevice, MemorySegment.NULL, count, MemorySegment.NULL);
            int extCount = count.get(JAVA_INT, 0);
            MemorySegment exts = VkExtensionProperties.allocateArray(scratch, Math.max(extCount, 1));
            vkEnumerateDeviceExtensionProperties(physicalDevice, MemorySegment.NULL, count, exts);
            List<String> available = new ArrayList<>();
            for (int i = 0; i < extCount; i++) {
                available.add(VkExtensionProperties.extensionName(VkExtensionProperties.at(exts, i)).getString(0));
            }

            List<String> enabled = new ArrayList<>();
            // Push descriptors are core in Vulkan 1.4, whose drivers keep advertising the extension
            if (!hasExtension(available, VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)) {
                return fail("the device does not support VK_KHR_push_descriptor");
            }
            enabled.add(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
            if (hasExtension(available, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)
                    && hasExtension(available, VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME)) {
                enabled.add(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
                enabled.add(VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME);
                exportSupported = true;
                if (hasExtension(available, VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
                    enabled.add(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
                    foreignQueueSupported = true;
                }
                if (hasExtension(available, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)) {
                    MemorySegment semInfo = VkPhysicalDeviceExternalSemaphoreInfo.allocate(scratch);
                    VkPhysicalDeviceExternalSemaphoreInfo.handleType(semInfo,
                            VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
                    MemorySegment semProps = VkExternalSemaphoreProperties.allocate(scratch);
                    vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, semInfo, semProps);
                    if ((VkExternalSemaphoreProperties.externalSemaphoreFeatures(semProps)
                            & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0) {
                        enabled.add(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
                        syncFileSupported = true;
                    }
                }
            }

            MemorySegment supported = VkPhysicalDeviceFeatures.allocate(scratch);
            vkGetPhysicalDeviceFeatures(physicalDevice, supported);
            fillModeNonSolid = VkPhysicalDeviceFeatures.fillModeNonSolid(supported) != 0;
            MemorySegment features = VkPhysicalDeviceFeatures.allocate(scratch);
            VkPhysicalDeviceFeatures.fillModeNonSolid(features, fillModeNonSolid ? VK_TRUE : VK_FALSE);
            MemorySegment features13 = VkPhysicalDeviceVulkan13Features.allocate(scratch);
            VkPhysicalDeviceVulkan13Features.dynamicRendering(features13, VK_TRUE);

            MemorySegment priority = scratch.allocateFrom(JAVA_FLOAT, 1.0f);
            MemorySegment queueInfo = VkDeviceQueueCreateInfo.allocate(scratch);
            VkDeviceQueueCreateInfo.queueFamilyIndex(queueInfo, queueFamily);
            VkDeviceQueueCreateInfo.queueCount(queueInfo, 1);
            VkDeviceQueueCreateInfo.pQueuePriorities(queueInfo, priority);
            MemorySegment names = scratch.allocate(ADDRESS, enabled.size());
            for (int i = 0; i < enabled.size(); i++) {
                names.setAtIndex(ADDRESS, i, scratch.allocateFrom(enabled.get(i)));
            }
            MemorySegment info = VkDeviceCreateInfo.allocate(scratch);
            VkDeviceCreateInfo.pNext(info, features13);
            VkDeviceCreateInfo.queueCreateInfoCount(info, 1);
            VkDeviceCreateInfo.pQueueCreateInfos(info, queueInfo);
            VkDeviceCreateInfo.enabledExtensionCount(info, enabled.size());
            VkDeviceCreateInfo.ppEnabledExtensionNames(info, names);
            VkDeviceCreateInfo.pEnabledFeatures(info, features);
            MemorySegment p = scratch.allocate(JAVA_LONG);
            check(vkCreateDevice(physicalDevice, info, MemorySegment.NULL, p), "vkCreateDevice");
            device = handle(p);
            vkGetDeviceQueue(device, queueFamily, 0, p);
            queue = handle(p);

            VK.loadDeviceCommands(device);
            if (!VK.isAvailable("vkCmdPushDescriptorSetKHR")) {
                return fail("cannot resolve vkCmdPushDescriptorSetKHR");
            }
            exportSupported &= VK.isAvailable("vkGetMemoryFdKHR");
            syncFileSupported &= VK.isAvailable("vkGetSemaphoreFdKHR");

            chooseDepthFormat();
            MemorySegment limits = VkPhysicalDeviceProperties.limits(properties);
            int counts = VkPhysicalDeviceLimits.framebufferColorSampleCounts(limits)
                    & VkPhysicalDeviceLimits.framebufferDepthSampleCounts(limits);
            msaaSamples = (counts & VK_SAMPLE_COUNT_4_BIT) != 0 ? 4 : (counts & VK_SAMPLE_COUNT_2_BIT) != 0 ? 2 : 1;
            minUniformAlignment = Math.max(VkPhysicalDeviceLimits.minUniformBufferOffsetAlignment(limits), 16);

            log("using %s (export %s, sync file %s, msaa %dx)", deviceName,
                    exportSupported ? "yes" : "no", syncFileSupported ? "yes" : "no", msaaSamples);
            return true;
        } finally {
            scratch.reset(mark);
        }
    }

    private void chooseDepthFormat() {
        MemorySegment props = VkFormatProperties.allocate(scratch);
        for (int format : new int[] { VK_FORMAT_D32_SFLOAT, VK_FORMAT_X8_D24_UNORM_PACK32,
                                      VK_FORMAT_D24_UNORM_S8_UINT }) {
            vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);
            if ((VkFormatProperties.optimalTilingFeatures(props)
                    & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT) != 0) {
                depthFormat = format;
                return;
            }
        }
    }

    private void createResources(byte[] vertexShader) {
        long mark = scratch.mark();
        try {
            MemorySegment p = scratch.allocate(JAVA_LONG);
            for (int i = 0; i < FRAMES; i++) {
                Frame frame = new Frame();
                frames[i] = frame;
                MemorySegment poolInfo = VkCommandPoolCreateInfo.allocate(scratch);
                VkCommandPoolCreateInfo.flags(poolInfo, VK_COMMAND_POOL_CREATE_TRANSIENT_BIT);
                VkCommandPoolCreateInfo.queueFamilyIndex(poolInfo, queueFamily);
                check(vkCreateCommandPool(device, poolInfo, MemorySegment.NULL, p), "vkCreateCommandPool");
                frame.commandPool = handle(p);
                MemorySegment cmdInfo = VkCommandBufferAllocateInfo.allocate(scratch);
                VkCommandBufferAllocateInfo.commandPool(cmdInfo, frame.commandPool);
                VkCommandBufferAllocateInfo.level(cmdInfo, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
                VkCommandBufferAllocateInfo.commandBufferCount(cmdInfo, 1);
                check(vkAllocateCommandBuffers(device, cmdInfo, p), "vkAllocateCommandBuffers");
                frame.cmd = handle(p);
                MemorySegment fenceInfo = VkFenceCreateInfo.allocate(scratch);
                check(vkCreateFence(device, fenceInfo, MemorySegment.NULL, p), "vkCreateFence");
                frame.fence = handle(p);
            }
            if (syncFileSupported) {
                MemorySegment exportInfo = VkExportSemaphoreCreateInfo.allocate(scratch);
                VkExportSemaphoreCreateInfo.handleTypes(exportInfo, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
                MemorySegment semInfo = VkSemaphoreCreateInfo.allocate(scratch);
                VkSemaphoreCreateInfo.pNext(semInfo, exportInfo);
                if (vkCreateSemaphore(device, semInfo, MemorySegment.NULL, p) == VK_SUCCESS) {
                    presentSemaphore = handle(p);
                } else {
                    syncFileSupported = false;
                }
            }

            // Host data: vertices, uniforms and staging
            long[] ring = createBuffer(RING_SIZE,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                    | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            ringBuffer = ring[0];
            ringMemory = ring[1];
            ringMapped = map(ringMemory, RING_SIZE);
            frameIndex = 0;
            cmd = frames[0].cmd;
            ringOffset = 0;
            ringLimit = RING_SIZE / FRAMES;

            // Quad indices: 0 1 2, 1 2 3 for each quad
            long indexSize = (long) MAX_QUADS * 6 * 2;
            long[] index = createBuffer(indexSize, VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            quadIndexBuffer = index[0];
            quadIndexMemory = index[1];
            MemorySegment indices = map(quadIndexMemory, indexSize);
            for (int q = 0; q < MAX_QUADS; q++) {
                short v = (short) (q * 4);
                long base = q * 12L;
                indices.set(ValueLayout.JAVA_SHORT, base, v);
                indices.set(ValueLayout.JAVA_SHORT, base + 2, (short) (v + 1));
                indices.set(ValueLayout.JAVA_SHORT, base + 4, (short) (v + 2));
                indices.set(ValueLayout.JAVA_SHORT, base + 6, (short) (v + 1));
                indices.set(ValueLayout.JAVA_SHORT, base + 8, (short) (v + 2));
                indices.set(ValueLayout.JAVA_SHORT, base + 10, (short) (v + 3));
            }
            vkUnmapMemory(device, quadIndexMemory);

            // One push descriptor set: the uniform block and the samplers
            MemorySegment bindings = VkDescriptorSetLayoutBinding.allocateArray(scratch, 1 + MAX_TEXTURE_UNITS);
            MemorySegment b0 = VkDescriptorSetLayoutBinding.at(bindings, 0);
            VkDescriptorSetLayoutBinding.binding(b0, UNIFORM_BINDING);
            VkDescriptorSetLayoutBinding.descriptorType(b0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            VkDescriptorSetLayoutBinding.descriptorCount(b0, 1);
            VkDescriptorSetLayoutBinding.stageFlags(b0, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
                MemorySegment b = VkDescriptorSetLayoutBinding.at(bindings, 1 + i);
                VkDescriptorSetLayoutBinding.binding(b, FIRST_SAMPLER_BINDING + i);
                VkDescriptorSetLayoutBinding.descriptorType(b, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                VkDescriptorSetLayoutBinding.descriptorCount(b, 1);
                VkDescriptorSetLayoutBinding.stageFlags(b, VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            MemorySegment setInfo = VkDescriptorSetLayoutCreateInfo.allocate(scratch);
            VkDescriptorSetLayoutCreateInfo.flags(setInfo, VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR);
            VkDescriptorSetLayoutCreateInfo.bindingCount(setInfo, 1 + MAX_TEXTURE_UNITS);
            VkDescriptorSetLayoutCreateInfo.pBindings(setInfo, bindings);
            check(vkCreateDescriptorSetLayout(device, setInfo, MemorySegment.NULL, p), "vkCreateDescriptorSetLayout");
            setLayout = handle(p);
            MemorySegment range = VkPushConstantRange.allocate(scratch);
            VkPushConstantRange.stageFlags(range, VK_SHADER_STAGE_VERTEX_BIT);
            VkPushConstantRange.size(range, 64);
            MemorySegment layouts = scratch.allocateFrom(JAVA_LONG, setLayout);
            MemorySegment layoutInfo = VkPipelineLayoutCreateInfo.allocate(scratch);
            VkPipelineLayoutCreateInfo.setLayoutCount(layoutInfo, 1);
            VkPipelineLayoutCreateInfo.pSetLayouts(layoutInfo, layouts);
            VkPipelineLayoutCreateInfo.pushConstantRangeCount(layoutInfo, 1);
            VkPipelineLayoutCreateInfo.pPushConstantRanges(layoutInfo, range);
            check(vkCreatePipelineLayout(device, layoutInfo, MemorySegment.NULL, p), "vkCreatePipelineLayout");
            pipelineLayout = handle(p);

            vertexModule = createShaderModule(vertexShader);

            // Samplers for each filter and address mode
            for (int linear = 0; linear < 2; linear++) {
                for (int wrap = 0; wrap < WRAP_COUNT; wrap++) {
                    int mode = wrap == WRAP_REPEAT ? VK_SAMPLER_ADDRESS_MODE_REPEAT
                            : wrap == WRAP_CLAMP_TO_ZERO ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER
                            : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
                    MemorySegment s = VkSamplerCreateInfo.allocate(scratch);
                    VkSamplerCreateInfo.magFilter(s, linear == 1 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
                    VkSamplerCreateInfo.minFilter(s, linear == 1 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
                    VkSamplerCreateInfo.mipmapMode(s, VK_SAMPLER_MIPMAP_MODE_NEAREST);
                    VkSamplerCreateInfo.addressModeU(s, mode);
                    VkSamplerCreateInfo.addressModeV(s, mode);
                    VkSamplerCreateInfo.addressModeW(s, mode);
                    VkSamplerCreateInfo.maxLod(s, VK_LOD_CLAMP_NONE);
                    VkSamplerCreateInfo.borderColor(s, VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
                    check(vkCreateSampler(device, s, MemorySegment.NULL, p), "vkCreateSampler");
                    samplers[linear][wrap] = handle(p);
                }
            }
            for (int i = 0; i < 16; i++) {
                transform.setAtIndex(JAVA_FLOAT, i, i % 5 == 0 ? 1f : 0f);
            }
        } finally {
            scratch.reset(mark);
        }
    }

    long createShaderModule(byte[] spirv) {
        long mark = scratch.mark();
        try {
            MemorySegment code = scratch.allocate(spirv.length, 4);
            MemorySegment.copy(spirv, 0, code, JAVA_BYTE, 0, spirv.length);
            MemorySegment info = VkShaderModuleCreateInfo.allocate(scratch);
            VkShaderModuleCreateInfo.codeSize(info, spirv.length);
            VkShaderModuleCreateInfo.pCode(info, code);
            MemorySegment p = scratch.allocate(JAVA_LONG);
            check(vkCreateShaderModule(device, info, MemorySegment.NULL, p), "vkCreateShaderModule");
            return handle(p);
        } finally {
            scratch.reset(mark);
        }
    }

    void destroy() {
        if (device != 0L) {
            vkDeviceWaitIdle(device);
            if (frames[0] != null) {
                flush();
            }
            if (dummyTexture != null) {
                destroyImageNow(dummyTexture);
            }
            for (long pipeline : phongPipelines.values()) {
                vkDestroyPipeline(device, pipeline, MemorySegment.NULL);
            }
            destroyModule(phongVertexModule);
            destroyModule(phongFragmentModule);
            for (long[] row : samplers) {
                for (long s : row) {
                    if (s != 0L) {
                        vkDestroySampler(device, s, MemorySegment.NULL);
                    }
                }
            }
            destroyModule(vertexModule);
            if (pipelineLayout != 0L) vkDestroyPipelineLayout(device, pipelineLayout, MemorySegment.NULL);
            if (setLayout != 0L) vkDestroyDescriptorSetLayout(device, setLayout, MemorySegment.NULL);
            destroyBuffer(quadIndexBuffer, quadIndexMemory);
            destroyBuffer(ringBuffer, ringMemory);
            for (Frame frame : frames) {
                if (frame != null) {
                    destroyBuffer(frame.stagingBuffer, frame.stagingMemory);
                    if (frame.fence != 0L) vkDestroyFence(device, frame.fence, MemorySegment.NULL);
                    if (frame.commandPool != 0L) vkDestroyCommandPool(device, frame.commandPool, MemorySegment.NULL);
                }
            }
            if (presentSemaphore != 0L) vkDestroySemaphore(device, presentSemaphore, MemorySegment.NULL);
            vkDestroyDevice(device, MemorySegment.NULL);
            device = 0L;
        }
        if (instance != 0L) {
            vkDestroyInstance(instance, MemorySegment.NULL);
            instance = 0L;
        }
        scratch.close();
        arena.close();
    }

    private void destroyModule(long module) {
        if (module != 0L) {
            vkDestroyShaderModule(device, module, MemorySegment.NULL);
        }
    }

    String getDeviceName() {
        return deviceName;
    }

    int getMaxTextureSize() {
        return VkPhysicalDeviceLimits.maxImageDimension2D(VkPhysicalDeviceProperties.limits(properties));
    }

    boolean isExportSupported() {
        return exportSupported;
    }

    int getMSAASamples() {
        return depthFormat != VK_FORMAT_UNDEFINED ? msaaSamples : 1;
    }

    boolean is3DSupported() {
        return depthFormat != VK_FORMAT_UNDEFINED;
    }

    // ---------------------------------------------------------------------
    // Memory

    private int findMemoryType(int typeBits, int flags) {
        int count = VkPhysicalDeviceMemoryProperties.memoryTypeCount(memoryProperties);
        MemorySegment types = VkPhysicalDeviceMemoryProperties.memoryTypes(memoryProperties);
        for (int i = 0; i < count; i++) {
            int typeFlags = VkMemoryType.propertyFlags(VkMemoryType.at(types, i));
            if ((typeBits & (1 << i)) != 0 && (typeFlags & flags) == flags) {
                return i;
            }
        }
        return -1;
    }

    /** Creates a buffer and its memory, returned as {buffer, memory}. */
    private long[] createBuffer(long size, int usage, int flags) {
        long mark = scratch.mark();
        try {
            MemorySegment info = VkBufferCreateInfo.allocate(scratch);
            VkBufferCreateInfo.size(info, size);
            VkBufferCreateInfo.usage(info, usage);
            VkBufferCreateInfo.sharingMode(info, VK_SHARING_MODE_EXCLUSIVE);
            MemorySegment p = scratch.allocate(JAVA_LONG);
            check(vkCreateBuffer(device, info, MemorySegment.NULL, p), "vkCreateBuffer");
            long buffer = handle(p);
            MemorySegment req = VkMemoryRequirements.allocate(scratch);
            vkGetBufferMemoryRequirements(device, buffer, req);
            int type = findMemoryType(VkMemoryRequirements.memoryTypeBits(req), flags);
            if (type < 0) {
                vkDestroyBuffer(device, buffer, MemorySegment.NULL);
                throw new VKException("no memory type for a buffer");
            }
            MemorySegment alloc = VkMemoryAllocateInfo.allocate(scratch);
            VkMemoryAllocateInfo.allocationSize(alloc, VkMemoryRequirements.size(req));
            VkMemoryAllocateInfo.memoryTypeIndex(alloc, type);
            if (vkAllocateMemory(device, alloc, MemorySegment.NULL, p) != VK_SUCCESS) {
                vkDestroyBuffer(device, buffer, MemorySegment.NULL);
                throw new VKException("cannot allocate " + size + " bytes for a buffer");
            }
            long memory = handle(p);
            vkBindBufferMemory(device, buffer, memory, 0);
            return new long[] { buffer, memory };
        } finally {
            scratch.reset(mark);
        }
    }

    private MemorySegment map(long memory, long size) {
        long mark = scratch.mark();
        try {
            MemorySegment p = scratch.allocate(ADDRESS);
            check(vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, p), "vkMapMemory");
            return p.get(ADDRESS, 0).reinterpret(size);
        } finally {
            scratch.reset(mark);
        }
    }

    private void destroyBuffer(long buffer, long memory) {
        if (buffer != 0L) vkDestroyBuffer(device, buffer, MemorySegment.NULL);
        if (memory != 0L) vkFreeMemory(device, memory, MemorySegment.NULL);
    }

    private long ringAlloc(long size, long align) {
        long offset = (ringOffset + align - 1) & -align;
        if (offset + size > ringLimit) {
            return -1;
        }
        ringOffset = offset + size;
        return offset;
    }

    /**
     * Allocates from the staging buffer of a frame, which only grows; a buffer
     * that is too small is released once the frame has executed.
     */
    private long stagingAlloc(Frame frame, long size) {
        long offset = (frame.stagingOffset + 15) & -16L;
        if (offset + size > frame.stagingSize) {
            if (frame.stagingBuffer != 0L) {
                long buffer = frame.stagingBuffer;
                long memory = frame.stagingMemory;
                deferRelease(() -> destroyBuffer(buffer, memory));
            }
            long newSize = Math.max(size, frame.stagingSize * 2);
            long[] staging = createBuffer(newSize, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            frame.stagingBuffer = staging[0];
            frame.stagingMemory = staging[1];
            frame.stagingMapped = map(staging[1], newSize);
            frame.stagingSize = newSize;
            offset = 0;
        }
        frame.stagingOffset = offset + size;
        return offset;
    }

    /**
     * Releases a resource once the frames that may use it have executed: the
     * current frame executes after the previous ones.
     */
    void deferRelease(Runnable release) {
        frames[frameIndex].releases.add(release);
    }

    // ---------------------------------------------------------------------
    // Frames

    private void ensureRecording() {
        if (recording) {
            return;
        }
        long mark = scratch.mark();
        try {
            MemorySegment begin = VkCommandBufferBeginInfo.allocate(scratch);
            VkCommandBufferBeginInfo.flags(begin, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");
            recording = true;
            dynDepth = -1;
            dynCull = -1;
        } finally {
            scratch.reset(mark);
        }
    }

    /** Waits for a frame to execute and releases its resources. */
    private void waitFrame(Frame frame) {
        if (frame.pending) {
            long mark = scratch.mark();
            try {
                MemorySegment fence = scratch.allocateFrom(JAVA_LONG, frame.fence);
                check(vkWaitForFences(device, 1, fence, VK_TRUE, -1L), "vkWaitForFences");
                vkResetFences(device, 1, fence);
                vkResetCommandPool(device, frame.commandPool, 0);
                frame.pending = false;
            } finally {
                scratch.reset(mark);
            }
        }
        if (!frame.releases.isEmpty()) {
            List<Runnable> releases = new ArrayList<>(frame.releases);
            frame.releases.clear();
            for (Runnable r : releases) {
                r.run();
            }
        }
    }

    /**
     * Submits the current frame, signaling a semaphore if given, and starts
     * recording the next one once the frame that used its resources executed.
     */
    private void submit(long signal) {
        Frame frame = frames[frameIndex];
        long mark = scratch.mark();
        try {
            if (recording) {
                endRendering();
                check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
                recording = false;
                MemorySegment cmds = scratch.allocateFrom(JAVA_LONG, cmd);
                MemorySegment info = VkSubmitInfo.allocate(scratch);
                VkSubmitInfo.commandBufferCount(info, 1);
                VkSubmitInfo.pCommandBuffers(info, cmds);
                if (signal != 0L) {
                    VkSubmitInfo.signalSemaphoreCount(info, 1);
                    VkSubmitInfo.pSignalSemaphores(info, scratch.allocateFrom(JAVA_LONG, signal));
                }
                check(vkQueueSubmit(queue, 1, info, frame.fence), "vkQueueSubmit");
                frame.pending = true;
                statFlushes++;
            } else if (signal != 0L) {
                // Nothing recorded: signal on an empty submission
                MemorySegment info = VkSubmitInfo.allocate(scratch);
                VkSubmitInfo.signalSemaphoreCount(info, 1);
                VkSubmitInfo.pSignalSemaphores(info, scratch.allocateFrom(JAVA_LONG, signal));
                check(vkQueueSubmit(queue, 1, info, 0L), "vkQueueSubmit");
            }
        } finally {
            scratch.reset(mark);
        }
        frameIndex = (frameIndex + 1) % FRAMES;
        Frame next = frames[frameIndex];
        waitFrame(next);
        next.stagingOffset = 0;
        cmd = next.cmd;
        long base = frameIndex * (RING_SIZE / FRAMES);
        ringOffset = base;
        ringLimit = base + RING_SIZE / FRAMES;
    }

    /** Submits the recorded commands and waits until everything has executed. */
    void flush() {
        if (device == 0L) {
            return;
        }
        submit(0L);
        for (Frame frame : frames) {
            waitFrame(frame);
        }
    }

    // ---------------------------------------------------------------------
    // Layouts

    private static int stageOf(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL ->
                    VK_PIPELINE_STAGE_TRANSFER_BIT;
            default -> VK_PIPELINE_STAGE_ALL_COMMANDS_BIT;
        };
    }

    private static int writesOf(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                 VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> 0;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL -> VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            default -> VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    private static int accessesOf(int layout) {
        return switch (layout) {
            case VK_IMAGE_LAYOUT_UNDEFINED -> 0;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL -> VK_ACCESS_SHADER_READ_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL ->
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL -> VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL -> VK_ACCESS_TRANSFER_READ_BIT;
            default -> VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
        };
    }

    private static void subresourceRange(MemorySegment range, int aspect) {
        VkImageSubresourceRange.aspectMask(range, aspect);
        VkImageSubresourceRange.levelCount(range, 1);
        VkImageSubresourceRange.layerCount(range, 1);
    }

    private static void subresourceLayers(MemorySegment layers) {
        VkImageSubresourceLayers.aspectMask(layers, VK_IMAGE_ASPECT_COLOR_BIT);
        VkImageSubresourceLayers.layerCount(layers, 1);
    }

    void transition(VKImage img, int layout) {
        if (img.layout == layout && !img.foreign) {
            return;
        }
        endRendering();
        ensureRecording();
        long mark = scratch.mark();
        try {
            int srcStage = stageOf(img.layout);
            int dstStage = stageOf(layout);
            MemorySegment barrier = VkImageMemoryBarrier.allocate(scratch);
            // Only writes need to be made available; reads only need to complete
            VkImageMemoryBarrier.srcAccessMask(barrier, writesOf(img.layout));
            VkImageMemoryBarrier.dstAccessMask(barrier, accessesOf(layout));
            VkImageMemoryBarrier.oldLayout(barrier, img.layout);
            VkImageMemoryBarrier.newLayout(barrier, layout);
            VkImageMemoryBarrier.srcQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.dstQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.image(barrier, img.image);
            subresourceRange(VkImageMemoryBarrier.subresourceRange(barrier), VK_IMAGE_ASPECT_COLOR_BIT);
            if (img.foreign) {
                // Acquire an exported image back from the compositor
                VkImageMemoryBarrier.srcQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_FOREIGN_EXT);
                VkImageMemoryBarrier.dstQueueFamilyIndex(barrier, queueFamily);
                VkImageMemoryBarrier.srcAccessMask(barrier, 0);
                srcStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                img.foreign = false;
            }
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                    1, barrier);
            img.layout = layout;
            statTransitions++;
        } finally {
            scratch.reset(mark);
        }
    }

    private int depthAspect() {
        return depthFormat == VK_FORMAT_D24_UNORM_S8_UINT
                ? VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT : VK_IMAGE_ASPECT_DEPTH_BIT;
    }

    private void transitionDepth(VKImage img) {
        if (img.depthLayout == VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL) {
            return;
        }
        endRendering();
        ensureRecording();
        long mark = scratch.mark();
        try {
            int stages = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            MemorySegment barrier = VkImageMemoryBarrier.allocate(scratch);
            VkImageMemoryBarrier.dstAccessMask(barrier, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                    | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            VkImageMemoryBarrier.oldLayout(barrier, VK_IMAGE_LAYOUT_UNDEFINED);
            VkImageMemoryBarrier.newLayout(barrier, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
            VkImageMemoryBarrier.srcQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.dstQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.image(barrier, img.depthImage);
            subresourceRange(VkImageMemoryBarrier.subresourceRange(barrier), depthAspect());
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, stages, 0, 0, MemorySegment.NULL,
                    0, MemorySegment.NULL, 1, barrier);
        } finally {
            scratch.reset(mark);
        }
    }

    /**
     * Releases an exported image to the compositor, which reads it from another
     * process.
     */
    private void releaseToForeign(VKImage img) {
        long mark = scratch.mark();
        try {
            MemorySegment barrier = VkImageMemoryBarrier.allocate(scratch);
            VkImageMemoryBarrier.srcAccessMask(barrier, VK_ACCESS_TRANSFER_WRITE_BIT);
            VkImageMemoryBarrier.oldLayout(barrier, img.layout);
            VkImageMemoryBarrier.newLayout(barrier, VK_IMAGE_LAYOUT_GENERAL);
            VkImageMemoryBarrier.srcQueueFamilyIndex(barrier,
                    foreignQueueSupported ? queueFamily : VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.dstQueueFamilyIndex(barrier,
                    foreignQueueSupported ? VK_QUEUE_FAMILY_FOREIGN_EXT : VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.image(barrier, img.image);
            subresourceRange(VkImageMemoryBarrier.subresourceRange(barrier), VK_IMAGE_ASPECT_COLOR_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                    0, 0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, barrier);
            img.layout = VK_IMAGE_LAYOUT_GENERAL;
            img.foreign = foreignQueueSupported;
        } finally {
            scratch.reset(mark);
        }
    }

    // ---------------------------------------------------------------------
    // Rendering

    private void endRendering() {
        if (rendering) {
            vkCmdEndRendering(cmd);
            rendering = false;
        }
    }

    private boolean beginRendering() {
        if (rendering) {
            return true;
        }
        if (target == null) {
            return false;
        }
        // Rendering resumed into a target that is already an attachment
        boolean resumed = target.layout == VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL && !target.foreign;
        transition(target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        boolean newDepth = false;
        if (target.hasDepth() && target.depthLayout != VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL) {
            transitionDepth(target);
            newDepth = true;
        }
        ensureRecording();
        if (resumed) {
            resumeBarrier(target, target.hasDepth() && !newDepth);
        }
        long mark = scratch.mark();
        try {
            MemorySegment color = VkRenderingAttachmentInfo.allocate(scratch);
            VkRenderingAttachmentInfo.imageView(color, target.view);
            VkRenderingAttachmentInfo.imageLayout(color, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkRenderingAttachmentInfo.loadOp(color, VK_ATTACHMENT_LOAD_OP_LOAD);
            VkRenderingAttachmentInfo.storeOp(color, VK_ATTACHMENT_STORE_OP_STORE);
            MemorySegment info = VkRenderingInfo.allocate(scratch);
            MemorySegment extent = VkRect2D.extent(VkRenderingInfo.renderArea(info));
            VkExtent2D.width(extent, target.width);
            VkExtent2D.height(extent, target.height);
            VkRenderingInfo.layerCount(info, 1);
            VkRenderingInfo.colorAttachmentCount(info, 1);
            VkRenderingInfo.pColorAttachments(info, color);
            if (target.hasDepth()) {
                MemorySegment depth = VkRenderingAttachmentInfo.allocate(scratch);
                VkRenderingAttachmentInfo.imageView(depth, target.depthView);
                VkRenderingAttachmentInfo.imageLayout(depth, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
                // A new depth buffer starts cleared to the far plane
                VkRenderingAttachmentInfo.loadOp(depth, newDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR
                        : VK_ATTACHMENT_LOAD_OP_LOAD);
                VkRenderingAttachmentInfo.storeOp(depth, VK_ATTACHMENT_STORE_OP_STORE);
                VkClearDepthStencilValue.depth(VkClearValue.depthStencil(
                        VkRenderingAttachmentInfo.clearValue(depth)), 1.0f);
                VkRenderingInfo.pDepthAttachment(info, depth);
                target.depthLayout = VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
            }
            vkCmdBeginRendering(cmd, info);
            rendering = true;
            statRenderPasses++;
            return true;
        } finally {
            scratch.reset(mark);
        }
    }

    /**
     * Orders the load of the attachments of a new rendering after the stores
     * of the previous rendering into them, which keeps their layout and so
     * gets no layout transition.
     */
    private void resumeBarrier(VKImage img, boolean depth) {
        long mark = scratch.mark();
        try {
            MemorySegment barriers = VkImageMemoryBarrier.allocateArray(scratch, depth ? 2 : 1);
            MemorySegment color = VkImageMemoryBarrier.at(barriers, 0);
            VkImageMemoryBarrier.srcAccessMask(color, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            VkImageMemoryBarrier.dstAccessMask(color,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            VkImageMemoryBarrier.oldLayout(color, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkImageMemoryBarrier.newLayout(color, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkImageMemoryBarrier.srcQueueFamilyIndex(color, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.dstQueueFamilyIndex(color, VK_QUEUE_FAMILY_IGNORED);
            VkImageMemoryBarrier.image(color, img.image);
            subresourceRange(VkImageMemoryBarrier.subresourceRange(color), VK_IMAGE_ASPECT_COLOR_BIT);
            int stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            if (depth) {
                MemorySegment d = VkImageMemoryBarrier.at(barriers, 1);
                VkImageMemoryBarrier.srcAccessMask(d, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                VkImageMemoryBarrier.dstAccessMask(d, VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                        | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                VkImageMemoryBarrier.oldLayout(d, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
                VkImageMemoryBarrier.newLayout(d, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
                VkImageMemoryBarrier.srcQueueFamilyIndex(d, VK_QUEUE_FAMILY_IGNORED);
                VkImageMemoryBarrier.dstQueueFamilyIndex(d, VK_QUEUE_FAMILY_IGNORED);
                VkImageMemoryBarrier.image(d, img.depthImage);
                subresourceRange(VkImageMemoryBarrier.subresourceRange(d), depthAspect());
                stages |= VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            }
            vkCmdPipelineBarrier(cmd, stages, stages, 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                    depth ? 2 : 1, barriers);
        } finally {
            scratch.reset(mark);
        }
    }

    private void setScissor(MemorySegment rect, boolean clip) {
        int x0 = 0;
        int y0 = 0;
        int x1 = target.width;
        int y1 = target.height;
        if (clip && clipEnabled) {
            x0 = Math.max(clipX, 0);
            y0 = Math.max(clipY, 0);
            x1 = Math.min(clipX + clipW, target.width);
            y1 = Math.min(clipY + clipH, target.height);
        }
        MemorySegment offset = VkRect2D.offset(rect);
        VkOffset2D.x(offset, x0);
        VkOffset2D.y(offset, y0);
        MemorySegment extent = VkRect2D.extent(rect);
        VkExtent2D.width(extent, Math.max(x1 - x0, 0));
        VkExtent2D.height(extent, Math.max(y1 - y0, 0));
    }

    /** Sets the viewport, the scissor and the depth and culling state of a draw. */
    private void setDrawState(boolean clip, int cull) {
        MemorySegment viewport = VkViewport.allocate(scratch);
        // A negative height flips the viewport, so that +y in clip space is up as in D3D
        VkViewport.y(viewport, target.height);
        VkViewport.width(viewport, target.width);
        VkViewport.height(viewport, -target.height);
        VkViewport.maxDepth(viewport, 1.0f);
        vkCmdSetViewport(cmd, 0, 1, viewport);
        MemorySegment scissor = VkRect2D.allocate(scratch);
        setScissor(scissor, clip);
        vkCmdSetScissor(cmd, 0, 1, scissor);

        int depth = depthTest && target.hasDepth() ? 1 : 0;
        if (depth != dynDepth) {
            vkCmdSetDepthTestEnable(cmd, depth);
            vkCmdSetDepthWriteEnable(cmd, depth);
            if (dynDepth < 0) {
                vkCmdSetDepthCompareOp(cmd, VK_COMPARE_OP_LESS_OR_EQUAL);
                vkCmdSetFrontFace(cmd, VK_FRONT_FACE_COUNTER_CLOCKWISE);
            }
            dynDepth = depth;
        }
        if (cull != dynCull) {
            vkCmdSetCullMode(cmd, switch (cull) {
                case CULL_BACK -> VK_CULL_MODE_BACK_BIT;
                case CULL_FRONT -> VK_CULL_MODE_FRONT_BIT;
                default -> VK_CULL_MODE_NONE;
            });
            dynCull = cull;
        }
    }

    void setRenderTarget(VKImage img, boolean depth) {
        if (target != img) {
            endRendering();
            target = img;
        }
        depthTest = depth;
        if (depth && img != null && !img.hasDepth() && depthFormat != VK_FORMAT_UNDEFINED) {
            endRendering();
            createDepth(img);
        }
        // The context resets its clip state with the target and sets it again if needed
        clipEnabled = false;
    }

    void setTransform(float[] matrix) {
        MemorySegment.copy(matrix, 0, transform, JAVA_FLOAT, 0, 16);
    }

    void setClipRect(int x, int y, int width, int height) {
        clipEnabled = true;
        clipX = x;
        clipY = y;
        clipW = Math.max(width, 0);
        clipH = Math.max(height, 0);
    }

    void resetClipRect() {
        clipEnabled = false;
    }

    void setCompositeMode(int mode) {
        if (mode >= 0 && mode < COMP_COUNT) {
            compositeMode = mode;
        }
    }

    void setShader(VKShader s) {
        shader = s;
    }

    VKShader getShader() {
        return shader;
    }

    void setTexture(int unit, VKImage img, boolean linear, int wrap) {
        if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
            return;
        }
        textures[unit] = img;
        if (wrap < 0 || wrap >= WRAP_COUNT) {
            wrap = WRAP_CLAMP_TO_EDGE;
        }
        textureSamplers[unit] = samplers[linear ? 1 : 0][wrap];
    }

    long sampler(boolean linear, int wrap) {
        return samplers[linear ? 1 : 0][wrap];
    }

    private void pushDescriptors(long uniformOffset, long uniformSize, VKImage[] images, long[] imageSamplers) {
        MemorySegment writes = VkWriteDescriptorSet.allocateArray(scratch, 1 + MAX_TEXTURE_UNITS);
        int count = 0;
        if (uniformSize > 0) {
            MemorySegment bufferInfo = VkDescriptorBufferInfo.allocate(scratch);
            VkDescriptorBufferInfo.buffer(bufferInfo, ringBuffer);
            VkDescriptorBufferInfo.offset(bufferInfo, uniformOffset);
            VkDescriptorBufferInfo.range(bufferInfo, uniformSize);
            MemorySegment w = VkWriteDescriptorSet.at(writes, count++);
            VkWriteDescriptorSet.dstBinding(w, UNIFORM_BINDING);
            VkWriteDescriptorSet.descriptorCount(w, 1);
            VkWriteDescriptorSet.descriptorType(w, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            VkWriteDescriptorSet.pBufferInfo(w, bufferInfo);
        }
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            VKImage img = images[i];
            if (img == null) {
                continue;
            }
            MemorySegment imageInfo = VkDescriptorImageInfo.allocate(scratch);
            VkDescriptorImageInfo.sampler(imageInfo, imageSamplers[i]);
            VkDescriptorImageInfo.imageView(imageInfo, img.view);
            VkDescriptorImageInfo.imageLayout(imageInfo, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            MemorySegment w = VkWriteDescriptorSet.at(writes, count++);
            VkWriteDescriptorSet.dstBinding(w, FIRST_SAMPLER_BINDING + i);
            VkWriteDescriptorSet.descriptorCount(w, 1);
            VkWriteDescriptorSet.descriptorType(w, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            VkWriteDescriptorSet.pImageInfo(w, imageInfo);
        }
        if (count > 0) {
            vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, count, writes);
        }
    }

    void drawQuads(float[] coords, byte[] colors, int numVertices) {
        VKShader s = shader;
        if (s == null || target == null || numVertices < 4) {
            return;
        }
        // The sampled textures must be readable; this ends the rendering
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            VKImage tex = textures[i];
            if (tex != null && tex != target) {
                transition(tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }

        int blockSize = s.getBlockSize();
        long vertexBytes = (long) numVertices * VERTEX_SIZE;
        long colorBytes = (long) numVertices * 4;
        long vertexOffset = -1;
        long colorOffset = -1;
        long uniformOffset = 0;
        for (int attempt = 0; ; attempt++) {
            long saved = ringOffset;
            vertexOffset = ringAlloc(vertexBytes, 4);
            colorOffset = ringAlloc(colorBytes, 4);
            if (blockSize > 0) {
                uniformOffset = ringAlloc(blockSize, minUniformAlignment);
            }
            if (vertexOffset >= 0 && colorOffset >= 0 && uniformOffset >= 0) {
                break;
            }
            ringOffset = saved;
            if (attempt > 0) {
                System.err.println("Prism VK error: draw data does not fit the ring buffer");
                return;
            }
            submit(0L);
        }
        MemorySegment.copy(coords, 0, ringMapped, JAVA_FLOAT, vertexOffset, numVertices * VERTEX_FLOATS);
        MemorySegment.copy(colors, 0, ringMapped, JAVA_BYTE, colorOffset, numVertices * 4);
        if (blockSize > 0) {
            MemorySegment.copy(s.getUniforms(), 0, ringMapped, uniformOffset, blockSize);
        }

        if (!beginRendering()) {
            return;
        }
        long pipeline = s.getPipeline(compositeMode, target.hasDepth(), target.sampleIndex());
        if (pipeline == 0L) {
            return;
        }
        long mark = scratch.mark();
        try {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            pushDescriptors(uniformOffset, blockSize, textures, textureSamplers);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, 64, transform);
            setDrawState(true, CULL_NONE);
            MemorySegment buffers = scratch.allocate(JAVA_LONG, 2);
            buffers.setAtIndex(JAVA_LONG, 0, ringBuffer);
            buffers.setAtIndex(JAVA_LONG, 1, ringBuffer);
            MemorySegment offsets = scratch.allocate(JAVA_LONG, 2);
            offsets.setAtIndex(JAVA_LONG, 0, vertexOffset);
            offsets.setAtIndex(JAVA_LONG, 1, colorOffset);
            vkCmdBindVertexBuffers(cmd, 0, 2, buffers, offsets);
            vkCmdBindIndexBuffer(cmd, quadIndexBuffer, 0, VK_INDEX_TYPE_UINT16);
            int numQuads = Math.min(numVertices / 4, MAX_QUADS);
            vkCmdDrawIndexed(cmd, numQuads * 6, 1, 0, 0, 0);
            statDraws++;
        } finally {
            scratch.reset(mark);
        }
    }

    void clear(float r, float g, float b, float a, boolean clearDepth) {
        if (target == null || !beginRendering()) {
            return;
        }
        long mark = scratch.mark();
        try {
            MemorySegment rect = VkClearRect.allocate(scratch);
            setScissor(VkClearRect.rect(rect), true);
            VkClearRect.layerCount(rect, 1);
            MemorySegment extent = VkRect2D.extent(VkClearRect.rect(rect));
            if (VkExtent2D.width(extent) == 0 || VkExtent2D.height(extent) == 0) {
                return;
            }
            boolean depth = clearDepth && target.hasDepth();
            MemorySegment attachments = VkClearAttachment.allocateArray(scratch, depth ? 2 : 1);
            MemorySegment c = VkClearAttachment.at(attachments, 0);
            VkClearAttachment.aspectMask(c, VK_IMAGE_ASPECT_COLOR_BIT);
            MemorySegment color = VkClearValue.color(VkClearAttachment.clearValue(c));
            VkClearColorValue.float32(color, 0, r);
            VkClearColorValue.float32(color, 1, g);
            VkClearColorValue.float32(color, 2, b);
            VkClearColorValue.float32(color, 3, a);
            if (depth) {
                MemorySegment d = VkClearAttachment.at(attachments, 1);
                VkClearAttachment.aspectMask(d, VK_IMAGE_ASPECT_DEPTH_BIT);
                VkClearDepthStencilValue.depth(VkClearValue.depthStencil(VkClearAttachment.clearValue(d)), 1.0f);
            }
            vkCmdClearAttachments(cmd, depth ? 2 : 1, attachments, 1, rect);
        } finally {
            scratch.reset(mark);
        }
    }

    void blit(VKImage src, VKImage dst, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        if (src == null || dst == null || src == dst) {
            return;
        }
        if (src.samples > 1) {
            boolean exact = sx0 == dx0 && sy0 == dy0 && sx1 == dx1 && sy1 == dy1
                    && sx1 > sx0 && sy1 > sy0 && dst.samples == 1;
            if (exact) {
                resolve(src, dst, sx0, sy0, sx1 - sx0, sy1 - sy0);
                return;
            }
            // Resolve into a temporary image, then scale or move it
            VKImage resolved = createImage(VKImage.FORMAT_BGRA8, src.width, src.height, 1, true, false);
            if (resolved == null) {
                return;
            }
            resolve(src, resolved, 0, 0, src.width, src.height);
            blit(resolved, dst, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
            resolved.dispose();
            return;
        }
        transition(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        transition(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        long mark = scratch.mark();
        try {
            MemorySegment region = VkImageBlit.allocate(scratch);
            subresourceLayers(VkImageBlit.srcSubresource(region));
            subresourceLayers(VkImageBlit.dstSubresource(region));
            MemorySegment so = VkImageBlit.srcOffsets(region);
            offset3D(VkOffset3D.at(so, 0), sx0, sy0, 0);
            offset3D(VkOffset3D.at(so, 1), sx1, sy1, 1);
            MemorySegment d = VkImageBlit.dstOffsets(region);
            offset3D(VkOffset3D.at(d, 0), dx0, dy0, 0);
            offset3D(VkOffset3D.at(d, 1), dx1, dy1, 1);
            boolean sameSize = Math.abs(sx1 - sx0) == Math.abs(dx1 - dx0)
                    && Math.abs(sy1 - sy0) == Math.abs(dy1 - dy0);
            vkCmdBlitImage(cmd, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region,
                    sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        } finally {
            scratch.reset(mark);
        }
        transition(src, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        transition(dst, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static void offset3D(MemorySegment o, int x, int y, int z) {
        VkOffset3D.x(o, x);
        VkOffset3D.y(o, y);
        VkOffset3D.z(o, z);
    }

    private void resolve(VKImage src, VKImage dst, int x, int y, int w, int h) {
        transition(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        transition(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        long mark = scratch.mark();
        try {
            MemorySegment region = VkImageResolve.allocate(scratch);
            subresourceLayers(VkImageResolve.srcSubresource(region));
            subresourceLayers(VkImageResolve.dstSubresource(region));
            offset3D(VkImageResolve.srcOffset(region), x, y, 0);
            offset3D(VkImageResolve.dstOffset(region), x, y, 0);
            MemorySegment extent = VkImageResolve.extent(region);
            VkExtent3D.width(extent, Math.min(w, Math.min(src.width, dst.width) - x));
            VkExtent3D.height(extent, Math.min(h, Math.min(src.height, dst.height) - y));
            VkExtent3D.depth(extent, 1);
            vkCmdResolveImage(cmd, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
        } finally {
            scratch.reset(mark);
        }
        transition(dst, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * Copies a render target into an exported image, releases it to the
     * compositor and submits the frame. The compositor waits for the copy
     * through a sync file attached to the dma-buf; without sync files the copy
     * is waited for.
     */
    void copyToExported(VKImage src, VKImage dst, int width, int height) {
        if (src == null || dst == null) {
            return;
        }
        transition(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        transition(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        int w = Math.min(Math.min(width, src.width), dst.width);
        int h = Math.min(Math.min(height, src.height), dst.height);
        long mark = scratch.mark();
        try {
            MemorySegment region = VkImageCopy.allocate(scratch);
            subresourceLayers(VkImageCopy.srcSubresource(region));
            subresourceLayers(VkImageCopy.dstSubresource(region));
            MemorySegment extent = VkImageCopy.extent(region);
            VkExtent3D.width(extent, w);
            VkExtent3D.height(extent, h);
            VkExtent3D.depth(extent, 1);
            vkCmdCopyImage(cmd, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
        } finally {
            scratch.reset(mark);
        }
        transition(src, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        releaseToForeign(dst);
        if (!presentWithSyncFile(dst)) {
            // The compositor cannot wait for the GPU; finish before handing the buffer over
            flush();
        }
        if (stats) {
            System.err.printf("Prism VK frame: draws %d render passes %d transitions %d uploads %d flushes %d%n",
                    statDraws, statRenderPasses, statTransitions, statUploads, statFlushes);
            statDraws = statRenderPasses = statTransitions = statUploads = statFlushes = 0;
        }
    }

    private boolean presentWithSyncFile(VKImage exported) {
        if (!syncFileSupported || exported.fd < 0) {
            return false;
        }
        submit(presentSemaphore);
        long mark = scratch.mark();
        try {
            MemorySegment info = VkSemaphoreGetFdInfoKHR.allocate(scratch);
            VkSemaphoreGetFdInfoKHR.semaphore(info, presentSemaphore);
            VkSemaphoreGetFdInfoKHR.handleType(info, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
            MemorySegment pFd = scratch.allocate(JAVA_INT);
            pFd.set(JAVA_INT, 0, -1);
            if (vkGetSemaphoreFdKHR(device, info, pFd) != VK_SUCCESS) {
                System.err.println("Prism VK error: cannot export the frame sync file");
                syncFileSupported = false;
                return false;
            }
            int syncFd = pFd.get(JAVA_INT, 0);
            if (syncFd < 0) {
                // Already signaled
                return true;
            }
            MemorySegment request = scratch.allocate(8, 4);
            request.set(JAVA_INT, 0, DMA_BUF_SYNC_WRITE);
            request.set(JAVA_INT, 4, syncFd);
            int result = (int) IOCTL.invokeExact(exported.fd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, request);
            closeFd(syncFd);
            if (result != 0) {
                log("cannot attach the sync file to the dma-buf, waiting for the GPU");
                syncFileSupported = false;
                return false;
            }
            return true;
        } catch (Throwable t) {
            syncFileSupported = false;
            return false;
        } finally {
            scratch.reset(mark);
        }
    }

    private static void closeFd(int fd) {
        try {
            int ignored = (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            // Nothing to do
        }
    }

    // ---------------------------------------------------------------------
    // Images

    /**
     * Creates an image, or returns null if it cannot be created. Exported images
     * are linear, which every consumer can import.
     */
    VKImage createImage(int texFormat, int width, int height, int samples, boolean renderTarget, boolean exportable) {
        if (exportable && !exportSupported) {
            return null;
        }
        VKImage img = new VKImage(this, texFormat, width, height, samples, renderTarget, exportable);
        long mark = scratch.mark();
        try {
            int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            if (!exportable && samples == 1) {
                usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
            }
            if (renderTarget) {
                usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
            }
            MemorySegment info = VkImageCreateInfo.allocate(scratch);
            if (exportable) {
                MemorySegment external = VkExternalMemoryImageCreateInfo.allocate(scratch);
                VkExternalMemoryImageCreateInfo.handleTypes(external, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
                VkImageCreateInfo.pNext(info, external);
            }
            VkImageCreateInfo.imageType(info, VK_IMAGE_TYPE_2D);
            VkImageCreateInfo.format(info, img.format);
            MemorySegment extent = VkImageCreateInfo.extent(info);
            VkExtent3D.width(extent, width);
            VkExtent3D.height(extent, height);
            VkExtent3D.depth(extent, 1);
            VkImageCreateInfo.mipLevels(info, 1);
            VkImageCreateInfo.arrayLayers(info, 1);
            VkImageCreateInfo.samples(info, samples);
            VkImageCreateInfo.tiling(info, exportable ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL);
            VkImageCreateInfo.usage(info, usage);
            VkImageCreateInfo.sharingMode(info, VK_SHARING_MODE_EXCLUSIVE);
            VkImageCreateInfo.initialLayout(info, VK_IMAGE_LAYOUT_UNDEFINED);
            MemorySegment p = scratch.allocate(JAVA_LONG);
            if (vkCreateImage(device, info, MemorySegment.NULL, p) != VK_SUCCESS) {
                System.err.println("Prism VK error: cannot create a " + width + "x" + height + " image");
                return null;
            }
            img.image = handle(p);
            img.memory = allocateImageMemory(img.image, exportable);
            if (img.memory == 0L) {
                vkDestroyImage(device, img.image, MemorySegment.NULL);
                return null;
            }

            if (exportable) {
                MemorySegment sub = VkImageSubresource.allocate(scratch);
                VkImageSubresource.aspectMask(sub, VK_IMAGE_ASPECT_COLOR_BIT);
                MemorySegment layout = VkSubresourceLayout.allocate(scratch);
                vkGetImageSubresourceLayout(device, img.image, sub, layout);
                img.rowPitch = VkSubresourceLayout.rowPitch(layout);
                img.offset = VkSubresourceLayout.offset(layout);
                MemorySegment fdInfo = VkMemoryGetFdInfoKHR.allocate(scratch);
                VkMemoryGetFdInfoKHR.memory(fdInfo, img.memory);
                VkMemoryGetFdInfoKHR.handleType(fdInfo, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
                MemorySegment pFd = scratch.allocate(JAVA_INT);
                if (vkGetMemoryFdKHR(device, fdInfo, pFd) != VK_SUCCESS) {
                    System.err.println("Prism VK error: cannot export the image memory");
                    destroyImageNow(img);
                    return null;
                }
                img.fd = pFd.get(JAVA_INT, 0);
            } else {
                img.view = createView(img.image, img.format, VK_IMAGE_ASPECT_COLOR_BIT,
                        renderTarget ? VK_COMPONENT_SWIZZLE_IDENTITY : -1, texFormat);
            }
            return img;
        } finally {
            scratch.reset(mark);
        }
    }

    private long allocateImageMemory(long image, boolean exportable) {
        MemorySegment req = VkMemoryRequirements.allocate(scratch);
        vkGetImageMemoryRequirements(device, image, req);
        int bits = VkMemoryRequirements.memoryTypeBits(req);
        int type = findMemoryType(bits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type < 0) {
            type = findMemoryType(bits, 0);
        }
        MemorySegment alloc = VkMemoryAllocateInfo.allocate(scratch);
        if (exportable) {
            MemorySegment dedicated = VkMemoryDedicatedAllocateInfo.allocate(scratch);
            VkMemoryDedicatedAllocateInfo.image(dedicated, image);
            MemorySegment exportInfo = VkExportMemoryAllocateInfo.allocate(scratch);
            VkExportMemoryAllocateInfo.pNext(exportInfo, dedicated);
            VkExportMemoryAllocateInfo.handleTypes(exportInfo, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
            VkMemoryAllocateInfo.pNext(alloc, exportInfo);
        }
        VkMemoryAllocateInfo.allocationSize(alloc, VkMemoryRequirements.size(req));
        VkMemoryAllocateInfo.memoryTypeIndex(alloc, type);
        MemorySegment p = scratch.allocate(JAVA_LONG);
        if (type < 0 || vkAllocateMemory(device, alloc, MemorySegment.NULL, p) != VK_SUCCESS) {
            System.err.println("Prism VK error: cannot allocate " + VkMemoryRequirements.size(req)
                    + " bytes for an image");
            return 0L;
        }
        long memory = handle(p);
        vkBindImageMemory(device, image, memory, 0);
        return memory;
    }

    /**
     * Creates an image view. Alpha textures are sampled through their alpha
     * channel and gray textures through their color channels.
     */
    private long createView(long image, int format, int aspect, int swizzle, int texFormat) {
        MemorySegment viewInfo = VkImageViewCreateInfo.allocate(scratch);
        VkImageViewCreateInfo.image(viewInfo, image);
        VkImageViewCreateInfo.viewType(viewInfo, VK_IMAGE_VIEW_TYPE_2D);
        VkImageViewCreateInfo.format(viewInfo, format);
        if (swizzle < 0) {
            MemorySegment c = VkImageViewCreateInfo.components(viewInfo);
            if (texFormat == VKImage.FORMAT_ALPHA8) {
                VkComponentMapping.r(c, VK_COMPONENT_SWIZZLE_ZERO);
                VkComponentMapping.g(c, VK_COMPONENT_SWIZZLE_ZERO);
                VkComponentMapping.b(c, VK_COMPONENT_SWIZZLE_ZERO);
                VkComponentMapping.a(c, VK_COMPONENT_SWIZZLE_R);
            } else if (texFormat == VKImage.FORMAT_GRAY8) {
                VkComponentMapping.r(c, VK_COMPONENT_SWIZZLE_R);
                VkComponentMapping.g(c, VK_COMPONENT_SWIZZLE_R);
                VkComponentMapping.b(c, VK_COMPONENT_SWIZZLE_R);
                VkComponentMapping.a(c, VK_COMPONENT_SWIZZLE_ONE);
            }
        }
        subresourceRange(VkImageViewCreateInfo.subresourceRange(viewInfo), aspect);
        MemorySegment p = scratch.allocate(JAVA_LONG);
        check(vkCreateImageView(device, viewInfo, MemorySegment.NULL, p), "vkCreateImageView");
        return handle(p);
    }

    private void createDepth(VKImage img) {
        long mark = scratch.mark();
        try {
            MemorySegment info = VkImageCreateInfo.allocate(scratch);
            VkImageCreateInfo.imageType(info, VK_IMAGE_TYPE_2D);
            VkImageCreateInfo.format(info, depthFormat);
            MemorySegment extent = VkImageCreateInfo.extent(info);
            VkExtent3D.width(extent, img.width);
            VkExtent3D.height(extent, img.height);
            VkExtent3D.depth(extent, 1);
            VkImageCreateInfo.mipLevels(info, 1);
            VkImageCreateInfo.arrayLayers(info, 1);
            VkImageCreateInfo.samples(info, img.samples);
            VkImageCreateInfo.tiling(info, VK_IMAGE_TILING_OPTIMAL);
            VkImageCreateInfo.usage(info, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            VkImageCreateInfo.sharingMode(info, VK_SHARING_MODE_EXCLUSIVE);
            MemorySegment p = scratch.allocate(JAVA_LONG);
            if (vkCreateImage(device, info, MemorySegment.NULL, p) != VK_SUCCESS) {
                System.err.println("Prism VK error: cannot create a depth buffer");
                return;
            }
            img.depthImage = handle(p);
            img.depthMemory = allocateImageMemory(img.depthImage, false);
            if (img.depthMemory == 0L) {
                vkDestroyImage(device, img.depthImage, MemorySegment.NULL);
                img.depthImage = 0L;
                return;
            }
            img.depthView = createView(img.depthImage, depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT,
                    VK_COMPONENT_SWIZZLE_IDENTITY, VKImage.FORMAT_BGRA8);
            img.depthLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        } finally {
            scratch.reset(mark);
        }
    }

    /** Destroys an image once the frames that may use it have executed. */
    void destroyImage(VKImage img) {
        if (target == img) {
            endRendering();
            target = null;
        }
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            if (textures[i] == img) {
                textures[i] = null;
            }
        }
        if (device == 0L) {
            return;
        }
        long view = img.view;
        long image = img.image;
        long memory = img.memory;
        long depthView = img.depthView;
        long depthImage = img.depthImage;
        long depthMemory = img.depthMemory;
        deferRelease(() -> destroyHandles(view, image, memory, depthView, depthImage, depthMemory));
        if (img.fd >= 0) {
            closeFd(img.fd);
            img.fd = -1;
        }
    }

    private void destroyImageNow(VKImage img) {
        destroyHandles(img.view, img.image, img.memory, img.depthView, img.depthImage, img.depthMemory);
        if (img.fd >= 0) {
            closeFd(img.fd);
            img.fd = -1;
        }
    }

    private void destroyHandles(long view, long image, long memory, long depthView, long depthImage,
                                long depthMemory) {
        if (view != 0L) vkDestroyImageView(device, view, MemorySegment.NULL);
        if (image != 0L) vkDestroyImage(device, image, MemorySegment.NULL);
        if (memory != 0L) vkFreeMemory(device, memory, MemorySegment.NULL);
        if (depthView != 0L) vkDestroyImageView(device, depthView, MemorySegment.NULL);
        if (depthImage != 0L) vkDestroyImage(device, depthImage, MemorySegment.NULL);
        if (depthMemory != 0L) vkFreeMemory(device, depthMemory, MemorySegment.NULL);
    }

    /**
     * Copies a rectangle of pixels into an image. The source rows are stride
     * bytes apart.
     */
    void updateImage(VKImage img, MemorySegment src, int dstx, int dsty, int srcx, int srcy,
                     int width, int height, int stride) {
        if (width <= 0 || height <= 0) {
            return;
        }
        int rowBytes = width * img.bytesPerPixel;
        long size = (long) rowBytes * height;
        long buffer = ringBuffer;
        MemorySegment mapped;
        boolean fitsRing = size <= RING_SIZE / FRAMES;
        long offset = fitsRing ? ringAlloc(size, 16) : -1;
        if (offset < 0 && fitsRing) {
            submit(0L);
            offset = ringAlloc(size, 16);
        }
        if (offset < 0) {
            // Larger than the ring buffer
            Frame frame = frames[frameIndex];
            offset = stagingAlloc(frame, size);
            buffer = frame.stagingBuffer;
            mapped = frame.stagingMapped.asSlice(offset, size);
        } else {
            mapped = ringMapped.asSlice(offset, size);
        }
        MemorySegment dst = mapped;
        long srcBase = (long) srcy * stride + (long) srcx * img.bytesPerPixel;
        if (stride == rowBytes) {
            MemorySegment.copy(src, srcBase, dst, 0, size);
        } else {
            for (int y = 0; y < height; y++) {
                MemorySegment.copy(src, srcBase + (long) y * stride, dst, (long) y * rowBytes, rowBytes);
            }
        }

        transition(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        long mark = scratch.mark();
        try {
            MemorySegment region = VkBufferImageCopy.allocate(scratch);
            VkBufferImageCopy.bufferOffset(region, offset);
            VkBufferImageCopy.bufferRowLength(region, width);
            VkBufferImageCopy.bufferImageHeight(region, height);
            subresourceLayers(VkBufferImageCopy.imageSubresource(region));
            offset3D(VkBufferImageCopy.imageOffset(region), dstx, dsty, 0);
            MemorySegment extent = VkBufferImageCopy.imageExtent(region);
            VkExtent3D.width(extent, width);
            VkExtent3D.height(extent, height);
            VkExtent3D.depth(extent, 1);
            vkCmdCopyBufferToImage(cmd, buffer, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
            statUploads++;
            // The next draw that samples the image makes it readable
        } finally {
            scratch.reset(mark);
        }
    }

    /** Reads a rectangle of pixels from an image, waiting for the rendering. */
    boolean readImage(VKImage img, MemorySegment dst, int x, int y, int width, int height) {
        if (img.samples > 1) {
            VKImage resolved = createImage(VKImage.FORMAT_BGRA8, img.width, img.height, 1, true, false);
            if (resolved == null) {
                return false;
            }
            resolve(img, resolved, 0, 0, img.width, img.height);
            boolean ok = readImage(resolved, dst, x, y, width, height);
            resolved.dispose();
            return ok;
        }
        long size = (long) width * height * img.bytesPerPixel;
        long[] host = createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try {
            MemorySegment mapped = map(host[1], size);
            int previous = img.layout == VK_IMAGE_LAYOUT_UNDEFINED
                    ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : img.layout;
            transition(img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            long mark = scratch.mark();
            try {
                MemorySegment region = VkBufferImageCopy.allocate(scratch);
                VkBufferImageCopy.bufferRowLength(region, width);
                VkBufferImageCopy.bufferImageHeight(region, height);
                subresourceLayers(VkBufferImageCopy.imageSubresource(region));
                offset3D(VkBufferImageCopy.imageOffset(region), x, y, 0);
                MemorySegment extent = VkBufferImageCopy.imageExtent(region);
                VkExtent3D.width(extent, width);
                VkExtent3D.height(extent, height);
                VkExtent3D.depth(extent, 1);
                vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, host[0], 1, region);
            } finally {
                scratch.reset(mark);
            }
            transition(img, previous);
            flush();
            MemorySegment.copy(mapped, 0, dst, 0, size);
            return true;
        } catch (VKException e) {
            return false;
        } finally {
            destroyBuffer(host[0], host[1]);
        }
    }

    // ---------------------------------------------------------------------
    // Pipelines

    private static void blendState(MemorySegment state, int mode) {
        VkPipelineColorBlendAttachmentState.blendEnable(state, VK_TRUE);
        VkPipelineColorBlendAttachmentState.colorBlendOp(state, VK_BLEND_OP_ADD);
        VkPipelineColorBlendAttachmentState.alphaBlendOp(state, VK_BLEND_OP_ADD);
        VkPipelineColorBlendAttachmentState.colorWriteMask(state, VK_COLOR_COMPONENT_R_BIT
                | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
        int src;
        int dst;
        switch (mode) {
            case COMP_CLEAR -> { src = VK_BLEND_FACTOR_ZERO; dst = VK_BLEND_FACTOR_ZERO; }
            case COMP_SRC -> {
                VkPipelineColorBlendAttachmentState.blendEnable(state, VK_FALSE);
                src = VK_BLEND_FACTOR_ONE;
                dst = VK_BLEND_FACTOR_ZERO;
            }
            case COMP_DSTOUT -> { src = VK_BLEND_FACTOR_ZERO; dst = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA; }
            case COMP_ADD -> { src = VK_BLEND_FACTOR_ONE; dst = VK_BLEND_FACTOR_ONE; }
            default -> { src = VK_BLEND_FACTOR_ONE; dst = VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA; }
        }
        VkPipelineColorBlendAttachmentState.srcColorBlendFactor(state, src);
        VkPipelineColorBlendAttachmentState.dstColorBlendFactor(state, dst);
        VkPipelineColorBlendAttachmentState.srcAlphaBlendFactor(state, src);
        VkPipelineColorBlendAttachmentState.dstAlphaBlendFactor(state, dst);
    }

    private static void vertexBinding(MemorySegment b, int binding, int stride) {
        VkVertexInputBindingDescription.binding(b, binding);
        VkVertexInputBindingDescription.stride(b, stride);
        VkVertexInputBindingDescription.inputRate(b, VK_VERTEX_INPUT_RATE_VERTEX);
    }

    private static void vertexAttribute(MemorySegment a, int location, int binding, int format, int offset) {
        VkVertexInputAttributeDescription.location(a, location);
        VkVertexInputAttributeDescription.binding(a, binding);
        VkVertexInputAttributeDescription.format(a, format);
        VkVertexInputAttributeDescription.offset(a, offset);
    }

    /**
     * Creates a pipeline for dynamic rendering into a BGRA target, with or
     * without a depth attachment. The viewport, the scissor, the depth test and
     * the culling are dynamic.
     *
     * @param mesh whether the vertices are mesh vertices rather than quads
     * @param specialization the specialization constants of the fragment
     *     shader, or null
     */
    long createPipeline(long vertex, long fragment, boolean mesh, int[] specialization,
                        int mode, boolean depth, int sampleIndex, boolean wireframe) {
        long mark = scratch.mark();
        try {
            MemorySegment main = scratch.allocateFrom("main");
            MemorySegment stages = VkPipelineShaderStageCreateInfo.allocateArray(scratch, 2);
            MemorySegment vs = VkPipelineShaderStageCreateInfo.at(stages, 0);
            VkPipelineShaderStageCreateInfo.stage(vs, VK_SHADER_STAGE_VERTEX_BIT);
            VkPipelineShaderStageCreateInfo.module(vs, vertex);
            VkPipelineShaderStageCreateInfo.pName(vs, main);
            MemorySegment fs = VkPipelineShaderStageCreateInfo.at(stages, 1);
            VkPipelineShaderStageCreateInfo.stage(fs, VK_SHADER_STAGE_FRAGMENT_BIT);
            VkPipelineShaderStageCreateInfo.module(fs, fragment);
            VkPipelineShaderStageCreateInfo.pName(fs, main);
            if (specialization != null) {
                MemorySegment entries = VkSpecializationMapEntry.allocateArray(scratch, specialization.length);
                MemorySegment data = scratch.allocate(JAVA_INT, specialization.length);
                for (int i = 0; i < specialization.length; i++) {
                    MemorySegment e = VkSpecializationMapEntry.at(entries, i);
                    VkSpecializationMapEntry.constantID(e, i);
                    VkSpecializationMapEntry.offset(e, i * 4);
                    VkSpecializationMapEntry.size(e, 4);
                    data.setAtIndex(JAVA_INT, i, specialization[i]);
                }
                MemorySegment spec = VkSpecializationInfo.allocate(scratch);
                VkSpecializationInfo.mapEntryCount(spec, specialization.length);
                VkSpecializationInfo.pMapEntries(spec, entries);
                VkSpecializationInfo.dataSize(spec, specialization.length * 4L);
                VkSpecializationInfo.pData(spec, data);
                VkPipelineShaderStageCreateInfo.pSpecializationInfo(fs, spec);
            }

            MemorySegment vertexInput = VkPipelineVertexInputStateCreateInfo.allocate(scratch);
            if (mesh) {
                MemorySegment bindings = VkVertexInputBindingDescription.allocateArray(scratch, 1);
                vertexBinding(bindings, 0, MESH_VERTEX_FLOATS * 4);
                MemorySegment attrs = VkVertexInputAttributeDescription.allocateArray(scratch, 3);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 0), 0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 1), 1, 0, VK_FORMAT_R32G32_SFLOAT, 12);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 2), 2, 0,
                        VK_FORMAT_R32G32B32A32_SFLOAT, 20);
                VkPipelineVertexInputStateCreateInfo.vertexBindingDescriptionCount(vertexInput, 1);
                VkPipelineVertexInputStateCreateInfo.pVertexBindingDescriptions(vertexInput, bindings);
                VkPipelineVertexInputStateCreateInfo.vertexAttributeDescriptionCount(vertexInput, 3);
                VkPipelineVertexInputStateCreateInfo.pVertexAttributeDescriptions(vertexInput, attrs);
            } else {
                MemorySegment bindings = VkVertexInputBindingDescription.allocateArray(scratch, 2);
                vertexBinding(VkVertexInputBindingDescription.at(bindings, 0), 0, VERTEX_SIZE);
                vertexBinding(VkVertexInputBindingDescription.at(bindings, 1), 1, 4);
                MemorySegment attrs = VkVertexInputAttributeDescription.allocateArray(scratch, 4);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 0), 0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 1), 1, 0, VK_FORMAT_R32G32_SFLOAT, 12);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 2), 2, 0, VK_FORMAT_R32G32_SFLOAT, 20);
                vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 3), 3, 1, VK_FORMAT_R8G8B8A8_UNORM, 0);
                VkPipelineVertexInputStateCreateInfo.vertexBindingDescriptionCount(vertexInput, 2);
                VkPipelineVertexInputStateCreateInfo.pVertexBindingDescriptions(vertexInput, bindings);
                VkPipelineVertexInputStateCreateInfo.vertexAttributeDescriptionCount(vertexInput, 4);
                VkPipelineVertexInputStateCreateInfo.pVertexAttributeDescriptions(vertexInput, attrs);
            }
            MemorySegment inputAssembly = VkPipelineInputAssemblyStateCreateInfo.allocate(scratch);
            VkPipelineInputAssemblyStateCreateInfo.topology(inputAssembly, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            MemorySegment viewport = VkPipelineViewportStateCreateInfo.allocate(scratch);
            VkPipelineViewportStateCreateInfo.viewportCount(viewport, 1);
            VkPipelineViewportStateCreateInfo.scissorCount(viewport, 1);
            MemorySegment raster = VkPipelineRasterizationStateCreateInfo.allocate(scratch);
            VkPipelineRasterizationStateCreateInfo.polygonMode(raster,
                    wireframe && fillModeNonSolid ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL);
            VkPipelineRasterizationStateCreateInfo.cullMode(raster, VK_CULL_MODE_NONE);
            VkPipelineRasterizationStateCreateInfo.frontFace(raster, VK_FRONT_FACE_COUNTER_CLOCKWISE);
            VkPipelineRasterizationStateCreateInfo.lineWidth(raster, 1.0f);
            MemorySegment multisample = VkPipelineMultisampleStateCreateInfo.allocate(scratch);
            VkPipelineMultisampleStateCreateInfo.rasterizationSamples(multisample, 1 << sampleIndex);
            MemorySegment depthStencil = VkPipelineDepthStencilStateCreateInfo.allocate(scratch);
            VkPipelineDepthStencilStateCreateInfo.depthCompareOp(depthStencil, VK_COMPARE_OP_LESS_OR_EQUAL);
            VkPipelineDepthStencilStateCreateInfo.maxDepthBounds(depthStencil, 1.0f);
            MemorySegment blendAttachment = VkPipelineColorBlendAttachmentState.allocate(scratch);
            blendState(blendAttachment, mode);
            MemorySegment blend = VkPipelineColorBlendStateCreateInfo.allocate(scratch);
            VkPipelineColorBlendStateCreateInfo.attachmentCount(blend, 1);
            VkPipelineColorBlendStateCreateInfo.pAttachments(blend, blendAttachment);
            int[] states = {
                VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE,
                VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE, VK_DYNAMIC_STATE_DEPTH_COMPARE_OP,
                VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE
            };
            MemorySegment dynamicStates = scratch.allocateFrom(JAVA_INT, states);
            MemorySegment dynamic = VkPipelineDynamicStateCreateInfo.allocate(scratch);
            VkPipelineDynamicStateCreateInfo.dynamicStateCount(dynamic, states.length);
            VkPipelineDynamicStateCreateInfo.pDynamicStates(dynamic, dynamicStates);
            MemorySegment colorFormat = scratch.allocateFrom(JAVA_INT, VK_FORMAT_B8G8R8A8_UNORM);
            MemorySegment rendering = VkPipelineRenderingCreateInfo.allocate(scratch);
            VkPipelineRenderingCreateInfo.colorAttachmentCount(rendering, 1);
            VkPipelineRenderingCreateInfo.pColorAttachmentFormats(rendering, colorFormat);
            VkPipelineRenderingCreateInfo.depthAttachmentFormat(rendering, depth ? depthFormat : VK_FORMAT_UNDEFINED);

            MemorySegment info = VkGraphicsPipelineCreateInfo.allocate(scratch);
            VkGraphicsPipelineCreateInfo.pNext(info, rendering);
            VkGraphicsPipelineCreateInfo.stageCount(info, 2);
            VkGraphicsPipelineCreateInfo.pStages(info, stages);
            VkGraphicsPipelineCreateInfo.pVertexInputState(info, vertexInput);
            VkGraphicsPipelineCreateInfo.pInputAssemblyState(info, inputAssembly);
            VkGraphicsPipelineCreateInfo.pViewportState(info, viewport);
            VkGraphicsPipelineCreateInfo.pRasterizationState(info, raster);
            VkGraphicsPipelineCreateInfo.pMultisampleState(info, multisample);
            VkGraphicsPipelineCreateInfo.pDepthStencilState(info, depthStencil);
            VkGraphicsPipelineCreateInfo.pColorBlendState(info, blend);
            VkGraphicsPipelineCreateInfo.pDynamicState(info, dynamic);
            VkGraphicsPipelineCreateInfo.layout(info, pipelineLayout);
            MemorySegment p = scratch.allocate(JAVA_LONG);
            int result = vkCreateGraphicsPipelines(device, 0L, 1, info, MemorySegment.NULL, p);
            if (result != VK_SUCCESS) {
                System.err.println("Prism VK error: vkCreateGraphicsPipelines failed: " + result);
                return 0L;
            }
            return handle(p);
        } finally {
            scratch.reset(mark);
        }
    }

    long createQuadPipeline(long fragment, int mode, boolean depth, int sampleIndex) {
        return createPipeline(vertexModule, fragment, false, null, mode, depth, sampleIndex, false);
    }

    void destroyPipelines(long module, long[] pipelines) {
        deferRelease(() -> {
            for (long pipeline : pipelines) {
                if (pipeline != 0L) {
                    vkDestroyPipeline(device, pipeline, MemorySegment.NULL);
                }
            }
            destroyModule(module);
        });
    }

    // ---------------------------------------------------------------------
    // Meshes

    /** The vertex and index buffers of a mesh. */
    static final class MeshBuffers {
        long vertexBuffer;
        long vertexMemory;
        long indexBuffer;
        long indexMemory;
        int indexType;
        int indexCount;
    }

    MeshBuffers createMeshBuffers(float[] vertices, int vertexCount, Object indices, int indexCount) {
        if (vertexCount <= 0 || indexCount <= 0) {
            return null;
        }
        boolean shortIndices = indices instanceof short[];
        long vertexBytes = vertexCount * 4L;
        long indexBytes = indexCount * (shortIndices ? 2L : 4L);
        MeshBuffers mesh = new MeshBuffers();
        try {
            int flags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            long[] vb = createBuffer(vertexBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, flags);
            mesh.vertexBuffer = vb[0];
            mesh.vertexMemory = vb[1];
            MemorySegment vm = map(vb[1], vertexBytes);
            MemorySegment.copy(vertices, 0, vm, JAVA_FLOAT, 0, vertexCount);
            vkUnmapMemory(device, vb[1]);
            long[] ib = createBuffer(indexBytes, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, flags);
            mesh.indexBuffer = ib[0];
            mesh.indexMemory = ib[1];
            MemorySegment im = map(ib[1], indexBytes);
            if (shortIndices) {
                MemorySegment.copy((short[]) indices, 0, im, ValueLayout.JAVA_SHORT, 0, indexCount);
            } else {
                MemorySegment.copy((int[]) indices, 0, im, JAVA_INT, 0, indexCount);
            }
            vkUnmapMemory(device, ib[1]);
            mesh.indexType = shortIndices ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
            mesh.indexCount = indexCount;
            return mesh;
        } catch (VKException e) {
            destroyMeshBuffers(mesh);
            return null;
        }
    }

    void destroyMeshBuffers(MeshBuffers mesh) {
        if (mesh == null || device == 0L) {
            return;
        }
        long vb = mesh.vertexBuffer;
        long vm = mesh.vertexMemory;
        long ib = mesh.indexBuffer;
        long im = mesh.indexMemory;
        deferRelease(() -> {
            destroyBuffer(vb, vm);
            destroyBuffer(ib, im);
        });
    }

    /** Loads the Phong shaders; returns whether 3D rendering is possible. */
    boolean initPhong(byte[] vertexSpirv, byte[] fragmentSpirv) {
        if (phongVertexModule != 0L) {
            return true;
        }
        if (vertexSpirv == null || fragmentSpirv == null || depthFormat == VK_FORMAT_UNDEFINED) {
            return false;
        }
        phongVertexModule = createShaderModule(vertexSpirv);
        phongFragmentModule = createShaderModule(fragmentSpirv);
        return true;
    }

    /**
     * A white texture bound to the Phong samplers that a material does not use,
     * since every binding the shader declares needs a descriptor.
     */
    private VKImage dummyTexture() {
        if (dummyTexture == null) {
            dummyTexture = createImage(VKImage.FORMAT_BGRA8, 1, 1, 1, false, false);
            transition(dummyTexture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            long mark = scratch.mark();
            try {
                MemorySegment color = VkClearColorValue.allocate(scratch);
                for (int i = 0; i < 4; i++) {
                    VkClearColorValue.float32(color, i, 1f);
                }
                MemorySegment range = VkImageSubresourceRange.allocate(scratch);
                subresourceRange(range, VK_IMAGE_ASPECT_COLOR_BIT);
                vkCmdClearColorImage(cmd, dummyTexture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, 1, range);
            } finally {
                scratch.reset(mark);
            }
        }
        return dummyTexture;
    }

    /**
     * Draws a mesh with the Phong shader.
     *
     * @param variant the specialization constants of the material and lights
     * @param uniforms the uniform block of the Phong shaders
     * @param maps the diffuse, specular, bump and self-illumination maps, or nulls
     * @param linear whether each map is filtered linearly
     */
    void drawMesh(MeshBuffers mesh, int[] variant, int cull, boolean wireframe, float[] uniforms,
                  VKImage[] maps, boolean[] linear) {
        if (mesh == null || target == null || phongVertexModule == 0L) {
            return;
        }
        VKImage[] images = new VKImage[MAX_TEXTURE_UNITS];
        long[] imageSamplers = new long[MAX_TEXTURE_UNITS];
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            VKImage img = maps[i] != null && !maps[i].disposed ? maps[i] : dummyTexture();
            transition(img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images[i] = img;
            imageSamplers[i] = samplers[linear[i] ? 1 : 0][WRAP_REPEAT];
        }
        long uniformBytes = uniforms.length * 4L;
        long uniformOffset = ringAlloc(uniformBytes, minUniformAlignment);
        if (uniformOffset < 0) {
            submit(0L);
            uniformOffset = ringAlloc(uniformBytes, minUniformAlignment);
        }
        MemorySegment.copy(uniforms, 0, ringMapped, JAVA_FLOAT, uniformOffset, uniforms.length);

        if (!beginRendering()) {
            return;
        }
        int key = 0;
        for (int v : variant) {
            key = key * 4 + v;
        }
        key = ((key * 2 + (wireframe ? 1 : 0)) * 2 + (target.hasDepth() ? 1 : 0)) * 4 + target.sampleIndex();
        Long pipeline = phongPipelines.get(key);
        if (pipeline == null) {
            pipeline = createPipeline(phongVertexModule, phongFragmentModule, true, variant, COMP_SRCOVER,
                    target.hasDepth(), target.sampleIndex(), wireframe);
            phongPipelines.put(key, pipeline);
        }
        if (pipeline == 0L) {
            return;
        }
        long mark = scratch.mark();
        try {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            pushDescriptors(uniformOffset, uniformBytes, images, imageSamplers);
            // Meshes are not clipped
            setDrawState(false, cull);
            MemorySegment buffers = scratch.allocateFrom(JAVA_LONG, mesh.vertexBuffer);
            MemorySegment offsets = scratch.allocateFrom(JAVA_LONG, 0L);
            vkCmdBindVertexBuffers(cmd, 0, 1, buffers, offsets);
            vkCmdBindIndexBuffer(cmd, mesh.indexBuffer, 0, mesh.indexType);
            vkCmdDrawIndexed(cmd, mesh.indexCount, 1, 0, 0, 0);
            statDraws++;
        } finally {
            scratch.reset(mark);
        }
    }
}
