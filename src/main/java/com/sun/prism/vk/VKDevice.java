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

    // DRM_FORMAT_ARGB8888: B, G, R, A in memory; XRGB8888 is the same with
    // the alpha ignored, which GTK knows to be opaque
    static final long DRM_FORMAT_ARGB8888 = 0x34325241L;
    static final long DRM_FORMAT_XRGB8888 = 0x34325258L;
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

    /**
     * Chooses the physical device and its graphics queue, and creates the
     * device with the extensions the pipeline uses; returns false, with the
     * reason in getFailure(), when no device is usable.
     */
    private boolean createDevice() {
        long mark = scratch.mark();
        try {
            if (!choosePhysicalDevice() || !chooseQueueFamily()) {
                return false;
            }
            List<String> extensions = chooseExtensions(availableExtensions());
            if (extensions == null) {
                return false;
            }
            createLogicalDevice(extensions);
            if (!VK.isAvailable("vkCmdPushDescriptorSetKHR")) {
                return fail("cannot resolve vkCmdPushDescriptorSetKHR");
            }
            exportSupported &= VK.isAvailable("vkGetMemoryFdKHR");
            syncFileSupported &= VK.isAvailable("vkGetSemaphoreFdKHR");
            chooseDepthFormat();
            readLimits();
            log("using %s (export %s, sync file %s, msaa %dx)", deviceName,
                    exportSupported ? "yes" : "no", syncFileSupported ? "yes" : "no", msaaSamples);
            return true;
        } finally {
            scratch.reset(mark);
        }
    }

    /**
     * Chooses the device of PRISM_VK_DEVICE, or else the best one: discrete,
     * then integrated, then virtual GPUs, but no software rasterizer.
     */
    private boolean choosePhysicalDevice() {
        MemorySegment count = scratch.allocate(JAVA_INT);
        vkEnumeratePhysicalDevices(instance, count, MemorySegment.NULL);
        int n = count.get(JAVA_INT, 0);
        if (n == 0) {
            return fail("no Vulkan devices");
        }
        MemorySegment devices = scratch.allocate(JAVA_LONG, n);
        vkEnumeratePhysicalDevices(instance, count, devices);
        int best = bestDevice(devices, n, System.getenv("PRISM_VK_DEVICE"));
        if (best < 0) {
            return fail("no Vulkan 1.3 hardware device");
        }
        physicalDevice = devices.getAtIndex(JAVA_LONG, best);
        vkGetPhysicalDeviceProperties(physicalDevice, properties);
        vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);
        deviceName = VkPhysicalDeviceProperties.deviceName(properties).getString(0);
        return true;
    }

    private int bestDevice(MemorySegment devices, int n, String requested) {
        int best = -1;
        int bestScore = -1;
        MemorySegment props = VkPhysicalDeviceProperties.allocate(scratch);
        for (int i = 0; i < n; i++) {
            vkGetPhysicalDeviceProperties(devices.getAtIndex(JAVA_LONG, i), props);
            String name = VkPhysicalDeviceProperties.deviceName(props).getString(0);
            int score = deviceScore(props);
            log("device %d: %s (score %d)", i, name, score);
            if (requested != null && name.contains(requested) && score >= 0) {
                return i;
            }
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean chooseQueueFamily() {
        MemorySegment count = scratch.allocate(JAVA_INT);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, MemorySegment.NULL);
        int familyCount = count.get(JAVA_INT, 0);
        MemorySegment families = VkQueueFamilyProperties.allocateArray(scratch, familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, count, families);
        for (int i = 0; i < familyCount; i++) {
            int flags = VkQueueFamilyProperties.queueFlags(VkQueueFamilyProperties.at(families, i));
            if ((flags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                queueFamily = i;
                return true;
            }
        }
        return fail("no graphics queue");
    }

    private List<String> availableExtensions() {
        MemorySegment count = scratch.allocate(JAVA_INT);
        vkEnumerateDeviceExtensionProperties(physicalDevice, MemorySegment.NULL, count, MemorySegment.NULL);
        int extCount = count.get(JAVA_INT, 0);
        MemorySegment exts = VkExtensionProperties.allocateArray(scratch, Math.max(extCount, 1));
        vkEnumerateDeviceExtensionProperties(physicalDevice, MemorySegment.NULL, count, exts);
        List<String> available = new ArrayList<>();
        for (int i = 0; i < extCount; i++) {
            available.add(VkExtensionProperties.extensionName(VkExtensionProperties.at(exts, i)).getString(0));
        }
        return available;
    }

    /**
     * The extensions to enable: push descriptors, which are required, and
     * those of the dma-buf export and of the sync files, when available.
     */
    private List<String> chooseExtensions(List<String> available) {
        // Push descriptors are core in Vulkan 1.4, whose drivers keep advertising the extension
        if (!available.contains(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME)) {
            fail("the device does not support VK_KHR_push_descriptor");
            return null;
        }
        List<String> enabled = new ArrayList<>(List.of(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
        if (available.contains(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)
                && available.contains(VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME)) {
            enabled.add(VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
            enabled.add(VK_EXT_EXTERNAL_MEMORY_DMA_BUF_EXTENSION_NAME);
            exportSupported = true;
            if (available.contains(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME)) {
                enabled.add(VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME);
                foreignQueueSupported = true;
            }
            if (available.contains(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME) && canExportSyncFiles()) {
                enabled.add(VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
                syncFileSupported = true;
            }
        }
        return enabled;
    }

    private boolean canExportSyncFiles() {
        MemorySegment semInfo = VkPhysicalDeviceExternalSemaphoreInfo.allocate(scratch);
        VkPhysicalDeviceExternalSemaphoreInfo.handleType(semInfo, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
        MemorySegment semProps = VkExternalSemaphoreProperties.allocate(scratch);
        vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, semInfo, semProps);
        return (VkExternalSemaphoreProperties.externalSemaphoreFeatures(semProps)
                & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;
    }

    /** Creates the device with one graphics queue, dynamic rendering and the extensions. */
    private void createLogicalDevice(List<String> extensions) {
        MemorySegment info = VkDeviceCreateInfo.allocate(scratch);
        VkDeviceCreateInfo.pNext(info, vulkan13Features());
        VkDeviceCreateInfo.queueCreateInfoCount(info, 1);
        VkDeviceCreateInfo.pQueueCreateInfos(info, queueCreateInfo());
        VkDeviceCreateInfo.enabledExtensionCount(info, extensions.size());
        VkDeviceCreateInfo.ppEnabledExtensionNames(info, cStrings(extensions));
        VkDeviceCreateInfo.pEnabledFeatures(info, deviceFeatures());
        MemorySegment p = scratch.allocate(JAVA_LONG);
        check(vkCreateDevice(physicalDevice, info, MemorySegment.NULL, p), "vkCreateDevice");
        device = handle(p);
        vkGetDeviceQueue(device, queueFamily, 0, p);
        queue = handle(p);
        VK.loadDeviceCommands(device);
    }

    private MemorySegment vulkan13Features() {
        MemorySegment features13 = VkPhysicalDeviceVulkan13Features.allocate(scratch);
        VkPhysicalDeviceVulkan13Features.dynamicRendering(features13, VK_TRUE);
        return features13;
    }

    /** The features of Vulkan 1.0: the wireframe of the meshes, when supported. */
    private MemorySegment deviceFeatures() {
        MemorySegment supported = VkPhysicalDeviceFeatures.allocate(scratch);
        vkGetPhysicalDeviceFeatures(physicalDevice, supported);
        fillModeNonSolid = VkPhysicalDeviceFeatures.fillModeNonSolid(supported) != 0;
        MemorySegment features = VkPhysicalDeviceFeatures.allocate(scratch);
        VkPhysicalDeviceFeatures.fillModeNonSolid(features, fillModeNonSolid ? VK_TRUE : VK_FALSE);
        return features;
    }

    private MemorySegment queueCreateInfo() {
        MemorySegment queueInfo = VkDeviceQueueCreateInfo.allocate(scratch);
        VkDeviceQueueCreateInfo.queueFamilyIndex(queueInfo, queueFamily);
        VkDeviceQueueCreateInfo.queueCount(queueInfo, 1);
        VkDeviceQueueCreateInfo.pQueuePriorities(queueInfo, scratch.allocateFrom(JAVA_FLOAT, 1.0f));
        return queueInfo;
    }

    /** An array of C strings, allocated in the scratch memory. */
    private MemorySegment cStrings(List<String> strings) {
        MemorySegment names = scratch.allocate(ADDRESS, Math.max(strings.size(), 1));
        for (int i = 0; i < strings.size(); i++) {
            names.setAtIndex(ADDRESS, i, scratch.allocateFrom(strings.get(i)));
        }
        return names;
    }

    /** The multisampling and the alignment of the uniform blocks of the device. */
    private void readLimits() {
        MemorySegment limits = VkPhysicalDeviceProperties.limits(properties);
        int counts = VkPhysicalDeviceLimits.framebufferColorSampleCounts(limits)
                & VkPhysicalDeviceLimits.framebufferDepthSampleCounts(limits);
        msaaSamples = (counts & VK_SAMPLE_COUNT_4_BIT) != 0 ? 4 : (counts & VK_SAMPLE_COUNT_2_BIT) != 0 ? 2 : 1;
        minUniformAlignment = Math.max(VkPhysicalDeviceLimits.minUniformBufferOffsetAlignment(limits), 16);
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
            for (int i = 0; i < FRAMES; i++) {
                // Known before it is complete, so that destroy() releases it on a failure
                frames[i] = new Frame();
                initFrame(frames[i]);
            }
            if (syncFileSupported) {
                createPresentSemaphore();
            }
            createRing();
            createQuadIndices();
            createPipelineLayout();
            vertexModule = createShaderModule(vertexShader);
            createSamplers();
            setTransform(IDENTITY);
        } finally {
            scratch.reset(mark);
        }
    }

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};

    /** Creates the command pool and buffer, and the fence, of a frame in flight. */
    private void initFrame(Frame frame) {
        MemorySegment p = scratch.allocate(JAVA_LONG);
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
        check(vkCreateFence(device, VkFenceCreateInfo.allocate(scratch), MemorySegment.NULL, p), "vkCreateFence");
        frame.fence = handle(p);
    }

    /** The semaphore that the presented frames signal, exported as sync files. */
    private void createPresentSemaphore() {
        MemorySegment exportInfo = VkExportSemaphoreCreateInfo.allocate(scratch);
        VkExportSemaphoreCreateInfo.handleTypes(exportInfo, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
        MemorySegment semInfo = VkSemaphoreCreateInfo.allocate(scratch);
        VkSemaphoreCreateInfo.pNext(semInfo, exportInfo);
        MemorySegment p = scratch.allocate(JAVA_LONG);
        if (vkCreateSemaphore(device, semInfo, MemorySegment.NULL, p) == VK_SUCCESS) {
            presentSemaphore = handle(p);
        } else {
            syncFileSupported = false;
        }
    }

    /** The ring of the host data (vertices, uniforms and staging), half for each frame. */
    private void createRing() {
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
    }

    /** The indices of the quads: 0 1 2, 1 2 3 for each quad. */
    private void createQuadIndices() {
        long indexSize = (long) MAX_QUADS * 6 * 2;
        long[] index = createBuffer(indexSize, VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        quadIndexBuffer = index[0];
        quadIndexMemory = index[1];
        MemorySegment indices = map(quadIndexMemory, indexSize);
        for (int q = 0; q < MAX_QUADS; q++) {
            short v = (short) (q * 4);
            long base = q * 12L;
            short[] quad = {v, (short) (v + 1), (short) (v + 2), (short) (v + 1), (short) (v + 2), (short) (v + 3)};
            MemorySegment.copy(quad, 0, indices, ValueLayout.JAVA_SHORT, base, quad.length);
        }
        vkUnmapMemory(device, quadIndexMemory);
    }

    /**
     * The layout of all the pipelines: one push descriptor set, with the
     * uniform block and the samplers, and the transform as a push constant.
     */
    private void createPipelineLayout() {
        MemorySegment p = scratch.allocate(JAVA_LONG);
        MemorySegment setInfo = VkDescriptorSetLayoutCreateInfo.allocate(scratch);
        VkDescriptorSetLayoutCreateInfo.flags(setInfo, VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR);
        VkDescriptorSetLayoutCreateInfo.bindingCount(setInfo, 1 + MAX_TEXTURE_UNITS);
        VkDescriptorSetLayoutCreateInfo.pBindings(setInfo, descriptorBindings());
        check(vkCreateDescriptorSetLayout(device, setInfo, MemorySegment.NULL, p), "vkCreateDescriptorSetLayout");
        setLayout = handle(p);
        MemorySegment range = VkPushConstantRange.allocate(scratch);
        VkPushConstantRange.stageFlags(range, VK_SHADER_STAGE_VERTEX_BIT);
        VkPushConstantRange.size(range, 64);
        MemorySegment layoutInfo = VkPipelineLayoutCreateInfo.allocate(scratch);
        VkPipelineLayoutCreateInfo.setLayoutCount(layoutInfo, 1);
        VkPipelineLayoutCreateInfo.pSetLayouts(layoutInfo, scratch.allocateFrom(JAVA_LONG, setLayout));
        VkPipelineLayoutCreateInfo.pushConstantRangeCount(layoutInfo, 1);
        VkPipelineLayoutCreateInfo.pPushConstantRanges(layoutInfo, range);
        check(vkCreatePipelineLayout(device, layoutInfo, MemorySegment.NULL, p), "vkCreatePipelineLayout");
        pipelineLayout = handle(p);
    }

    private MemorySegment descriptorBindings() {
        MemorySegment bindings = VkDescriptorSetLayoutBinding.allocateArray(scratch, 1 + MAX_TEXTURE_UNITS);
        descriptorBinding(VkDescriptorSetLayoutBinding.at(bindings, 0), UNIFORM_BINDING,
                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            descriptorBinding(VkDescriptorSetLayoutBinding.at(bindings, 1 + i), FIRST_SAMPLER_BINDING + i,
                    VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_SHADER_STAGE_FRAGMENT_BIT);
        }
        return bindings;
    }

    private static void descriptorBinding(MemorySegment b, int binding, int type, int stages) {
        VkDescriptorSetLayoutBinding.binding(b, binding);
        VkDescriptorSetLayoutBinding.descriptorType(b, type);
        VkDescriptorSetLayoutBinding.descriptorCount(b, 1);
        VkDescriptorSetLayoutBinding.stageFlags(b, stages);
    }

    /** A sampler for each filter and address mode. */
    private void createSamplers() {
        for (int linear = 0; linear < 2; linear++) {
            for (int wrap = 0; wrap < WRAP_COUNT; wrap++) {
                samplers[linear][wrap] = createSampler(linear == 1, wrap);
            }
        }
    }

    private long createSampler(boolean linear, int wrap) {
        int mode = switch (wrap) {
            case WRAP_REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case WRAP_CLAMP_TO_ZERO -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            default -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        };
        int filter = linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        MemorySegment s = VkSamplerCreateInfo.allocate(scratch);
        VkSamplerCreateInfo.magFilter(s, filter);
        VkSamplerCreateInfo.minFilter(s, filter);
        VkSamplerCreateInfo.mipmapMode(s, VK_SAMPLER_MIPMAP_MODE_NEAREST);
        VkSamplerCreateInfo.addressModeU(s, mode);
        VkSamplerCreateInfo.addressModeV(s, mode);
        VkSamplerCreateInfo.addressModeW(s, mode);
        VkSamplerCreateInfo.maxLod(s, VK_LOD_CLAMP_NONE);
        VkSamplerCreateInfo.borderColor(s, VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
        MemorySegment p = scratch.allocate(JAVA_LONG);
        check(vkCreateSampler(device, s, MemorySegment.NULL, p), "vkCreateSampler");
        return handle(p);
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
            destroyRenderingObjects();
            destroyBuffers();
            if (presentSemaphore != 0L) {
                vkDestroySemaphore(device, presentSemaphore, MemorySegment.NULL);
            }
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

    /** The pipelines, the shaders, the samplers and the layouts. */
    private void destroyRenderingObjects() {
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
        if (pipelineLayout != 0L) {
            vkDestroyPipelineLayout(device, pipelineLayout, MemorySegment.NULL);
        }
        if (setLayout != 0L) {
            vkDestroyDescriptorSetLayout(device, setLayout, MemorySegment.NULL);
        }
    }

    /** The quad indices, the ring and the frames with their staging buffers. */
    private void destroyBuffers() {
        destroyBuffer(quadIndexBuffer, quadIndexMemory);
        destroyBuffer(ringBuffer, ringMemory);
        for (Frame frame : frames) {
            if (frame != null) {
                destroyBuffer(frame.stagingBuffer, frame.stagingMemory);
                if (frame.fence != 0L) {
                    vkDestroyFence(device, frame.fence, MemorySegment.NULL);
                }
                if (frame.commandPool != 0L) {
                    vkDestroyCommandPool(device, frame.commandPool, MemorySegment.NULL);
                }
            }
        }
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
            try {
                long memory = allocateBufferMemory(buffer, size, flags);
                vkBindBufferMemory(device, buffer, memory, 0);
                return new long[] {buffer, memory};
            } catch (VKException e) {
                vkDestroyBuffer(device, buffer, MemorySegment.NULL);
                throw e;
            }
        } finally {
            scratch.reset(mark);
        }
    }

    private long allocateBufferMemory(long buffer, long size, int flags) {
        MemorySegment req = VkMemoryRequirements.allocate(scratch);
        vkGetBufferMemoryRequirements(device, buffer, req);
        int type = findMemoryType(VkMemoryRequirements.memoryTypeBits(req), flags);
        if (type < 0) {
            throw new VKException("no memory type for a buffer");
        }
        MemorySegment alloc = VkMemoryAllocateInfo.allocate(scratch);
        VkMemoryAllocateInfo.allocationSize(alloc, VkMemoryRequirements.size(req));
        VkMemoryAllocateInfo.memoryTypeIndex(alloc, type);
        MemorySegment p = scratch.allocate(JAVA_LONG);
        if (vkAllocateMemory(device, alloc, MemorySegment.NULL, p) != VK_SUCCESS) {
            throw new VKException("cannot allocate " + size + " bytes for a buffer");
        }
        return handle(p);
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
        if (recording) {
            submitRecorded(frames[frameIndex], signal);
        } else if (signal != 0L) {
            signalEmpty(signal);
        }
        beginNextFrame();
    }

    /** Ends and submits the command buffer of a frame, which signals its fence. */
    private void submitRecorded(Frame frame, long signal) {
        endRendering();
        check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        recording = false;
        long mark = scratch.mark();
        try {
            MemorySegment info = VkSubmitInfo.allocate(scratch);
            VkSubmitInfo.commandBufferCount(info, 1);
            VkSubmitInfo.pCommandBuffers(info, scratch.allocateFrom(JAVA_LONG, cmd));
            signalOnSubmit(info, signal);
            check(vkQueueSubmit(queue, 1, info, frame.fence), "vkQueueSubmit");
            frame.pending = true;
            statFlushes++;
        } finally {
            scratch.reset(mark);
        }
    }

    /** Nothing was recorded: signals the semaphore on an empty submission. */
    private void signalEmpty(long signal) {
        long mark = scratch.mark();
        try {
            MemorySegment info = VkSubmitInfo.allocate(scratch);
            signalOnSubmit(info, signal);
            check(vkQueueSubmit(queue, 1, info, 0L), "vkQueueSubmit");
        } finally {
            scratch.reset(mark);
        }
    }

    private void signalOnSubmit(MemorySegment info, long signal) {
        if (signal != 0L) {
            VkSubmitInfo.signalSemaphoreCount(info, 1);
            VkSubmitInfo.pSignalSemaphores(info, scratch.allocateFrom(JAVA_LONG, signal));
        }
    }

    /**
     * Starts recording the next frame once the frame that last used its
     * command buffer and its half of the ring has executed.
     */
    private void beginNextFrame() {
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
            MemorySegment barrier = VkImageMemoryBarrier.allocate(scratch);
            int srcStage = layoutBarrier(barrier, img, layout);
            vkCmdPipelineBarrier(cmd, srcStage, stageOf(layout), 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                    1, barrier);
            img.layout = layout;
            statTransitions++;
        } finally {
            scratch.reset(mark);
        }
    }

    /**
     * Fills the barrier of a layout transition; an exported image is also
     * acquired back from the compositor.
     *
     * @return the source stage of the barrier
     */
    private int layoutBarrier(MemorySegment barrier, VKImage img, int layout) {
        // Only writes need to be made available; reads only need to complete
        VkImageMemoryBarrier.srcAccessMask(barrier, writesOf(img.layout));
        VkImageMemoryBarrier.dstAccessMask(barrier, accessesOf(layout));
        VkImageMemoryBarrier.oldLayout(barrier, img.layout);
        VkImageMemoryBarrier.newLayout(barrier, layout);
        VkImageMemoryBarrier.srcQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
        VkImageMemoryBarrier.dstQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
        VkImageMemoryBarrier.image(barrier, img.image);
        subresourceRange(VkImageMemoryBarrier.subresourceRange(barrier), VK_IMAGE_ASPECT_COLOR_BIT);
        if (!img.foreign) {
            return stageOf(img.layout);
        }
        VkImageMemoryBarrier.srcQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_FOREIGN_EXT);
        VkImageMemoryBarrier.dstQueueFamilyIndex(barrier, queueFamily);
        VkImageMemoryBarrier.srcAccessMask(barrier, 0);
        img.foreign = false;
        return VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
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
        boolean newDepth = prepareDepth(target);
        ensureRecording();
        if (resumed) {
            resumeBarrier(target, target.hasDepth() && !newDepth);
        }
        long mark = scratch.mark();
        try {
            vkCmdBeginRendering(cmd, renderingInfo(target, newDepth));
            rendering = true;
            statRenderPasses++;
            return true;
        } finally {
            scratch.reset(mark);
        }
    }

    /**
     * Makes the depth buffer of a target an attachment.
     *
     * @return whether the depth buffer is new, and so is to be cleared
     */
    private boolean prepareDepth(VKImage img) {
        if (!img.hasDepth() || img.depthLayout == VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL) {
            return false;
        }
        transitionDepth(img);
        return true;
    }

    private MemorySegment renderingInfo(VKImage img, boolean newDepth) {
        MemorySegment info = VkRenderingInfo.allocate(scratch);
        MemorySegment extent = VkRect2D.extent(VkRenderingInfo.renderArea(info));
        VkExtent2D.width(extent, img.width);
        VkExtent2D.height(extent, img.height);
        VkRenderingInfo.layerCount(info, 1);
        VkRenderingInfo.colorAttachmentCount(info, 1);
        VkRenderingInfo.pColorAttachments(info, colorAttachment(img));
        if (img.hasDepth()) {
            VkRenderingInfo.pDepthAttachment(info, depthAttachment(img, newDepth));
            img.depthLayout = VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL;
        }
        return info;
    }

    /** The color attachment, whose content is kept: Prism draws over it. */
    private MemorySegment colorAttachment(VKImage img) {
        MemorySegment color = VkRenderingAttachmentInfo.allocate(scratch);
        VkRenderingAttachmentInfo.imageView(color, img.view);
        VkRenderingAttachmentInfo.imageLayout(color, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkRenderingAttachmentInfo.loadOp(color, VK_ATTACHMENT_LOAD_OP_LOAD);
        VkRenderingAttachmentInfo.storeOp(color, VK_ATTACHMENT_STORE_OP_STORE);
        return color;
    }

    /** The depth attachment; a new depth buffer starts cleared to the far plane. */
    private MemorySegment depthAttachment(VKImage img, boolean clear) {
        MemorySegment depth = VkRenderingAttachmentInfo.allocate(scratch);
        VkRenderingAttachmentInfo.imageView(depth, img.depthView);
        VkRenderingAttachmentInfo.imageLayout(depth, VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL);
        VkRenderingAttachmentInfo.loadOp(depth, clear ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD);
        VkRenderingAttachmentInfo.storeOp(depth, VK_ATTACHMENT_STORE_OP_STORE);
        VkClearDepthStencilValue.depth(VkClearValue.depthStencil(VkRenderingAttachmentInfo.clearValue(depth)), 1.0f);
        return depth;
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
            attachmentBarrier(VkImageMemoryBarrier.at(barriers, 0), img.image,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            int stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            if (depth) {
                attachmentBarrier(VkImageMemoryBarrier.at(barriers, 1), img.depthImage,
                        VK_IMAGE_LAYOUT_DEPTH_ATTACHMENT_OPTIMAL, depthAspect(),
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                stages |= VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            }
            vkCmdPipelineBarrier(cmd, stages, stages, 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                    depth ? 2 : 1, barriers);
        } finally {
            scratch.reset(mark);
        }
    }

    /** A barrier on an attachment that keeps its layout. */
    private static void attachmentBarrier(MemorySegment barrier, long image, int layout, int aspect,
                                          int srcAccess, int dstAccess) {
        VkImageMemoryBarrier.srcAccessMask(barrier, srcAccess);
        VkImageMemoryBarrier.dstAccessMask(barrier, dstAccess);
        VkImageMemoryBarrier.oldLayout(barrier, layout);
        VkImageMemoryBarrier.newLayout(barrier, layout);
        VkImageMemoryBarrier.srcQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
        VkImageMemoryBarrier.dstQueueFamilyIndex(barrier, VK_QUEUE_FAMILY_IGNORED);
        VkImageMemoryBarrier.image(barrier, image);
        subresourceRange(VkImageMemoryBarrier.subresourceRange(barrier), aspect);
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
        setViewportAndScissor(clip);
        setDepthTest(depthTest && target.hasDepth() ? 1 : 0);
        setCullMode(cull);
    }

    private void setViewportAndScissor(boolean clip) {
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
    }

    /** The depth test and writes, set again only when they change in the command buffer. */
    private void setDepthTest(int depth) {
        if (depth == dynDepth) {
            return;
        }
        vkCmdSetDepthTestEnable(cmd, depth);
        vkCmdSetDepthWriteEnable(cmd, depth);
        if (dynDepth < 0) {
            vkCmdSetDepthCompareOp(cmd, VK_COMPARE_OP_LESS_OR_EQUAL);
            vkCmdSetFrontFace(cmd, VK_FRONT_FACE_COUNTER_CLOCKWISE);
        }
        dynDepth = depth;
    }

    private void setCullMode(int cull) {
        if (cull == dynCull) {
            return;
        }
        vkCmdSetCullMode(cmd, switch (cull) {
            case CULL_BACK -> VK_CULL_MODE_BACK_BIT;
            case CULL_FRONT -> VK_CULL_MODE_FRONT_BIT;
            default -> VK_CULL_MODE_NONE;
        });
        dynCull = cull;
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

    /** Pushes the uniform block, at an offset of the ring, and the sampled images. */
    private void pushDescriptors(long uniformOffset, long uniformSize, VKImage[] images, long[] imageSamplers) {
        MemorySegment writes = VkWriteDescriptorSet.allocateArray(scratch, 1 + MAX_TEXTURE_UNITS);
        int count = 0;
        if (uniformSize > 0) {
            uniformWrite(VkWriteDescriptorSet.at(writes, count++), uniformOffset, uniformSize);
        }
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            if (images[i] != null) {
                imageWrite(VkWriteDescriptorSet.at(writes, count++), FIRST_SAMPLER_BINDING + i,
                        images[i], imageSamplers[i]);
            }
        }
        if (count > 0) {
            vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, count, writes);
        }
    }

    private void uniformWrite(MemorySegment w, long offset, long size) {
        MemorySegment bufferInfo = VkDescriptorBufferInfo.allocate(scratch);
        VkDescriptorBufferInfo.buffer(bufferInfo, ringBuffer);
        VkDescriptorBufferInfo.offset(bufferInfo, offset);
        VkDescriptorBufferInfo.range(bufferInfo, size);
        VkWriteDescriptorSet.dstBinding(w, UNIFORM_BINDING);
        VkWriteDescriptorSet.descriptorCount(w, 1);
        VkWriteDescriptorSet.descriptorType(w, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
        VkWriteDescriptorSet.pBufferInfo(w, bufferInfo);
    }

    private void imageWrite(MemorySegment w, int binding, VKImage img, long sampler) {
        MemorySegment imageInfo = VkDescriptorImageInfo.allocate(scratch);
        VkDescriptorImageInfo.sampler(imageInfo, sampler);
        VkDescriptorImageInfo.imageView(imageInfo, img.view);
        VkDescriptorImageInfo.imageLayout(imageInfo, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkWriteDescriptorSet.dstBinding(w, binding);
        VkWriteDescriptorSet.descriptorCount(w, 1);
        VkWriteDescriptorSet.descriptorType(w, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        VkWriteDescriptorSet.pImageInfo(w, imageInfo);
    }

    void drawQuads(float[] coords, byte[] colors, int numVertices) {
        VKShader s = shader;
        if (s == null || target == null || numVertices < 4) {
            return;
        }
        // The sampled textures must be readable; this ends the rendering
        makeTexturesReadable();
        QuadData data = uploadQuads(s, coords, colors, numVertices);
        if (data == null || !beginRendering()) {
            return;
        }
        long pipeline = s.getPipeline(compositeMode, target.hasDepth(), target.sampleIndex());
        if (pipeline != 0L) {
            recordQuads(pipeline, data, s.getBlockSize(), numVertices);
        }
    }

    /** Where the data of a draw of quads is in the ring. */
    private record QuadData(long vertexOffset, long colorOffset, long uniformOffset) {}

    private void makeTexturesReadable() {
        for (VKImage tex : textures) {
            if (tex != null && tex != target) {
                transition(tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
    }

    /**
     * Copies the vertices, the colors and the uniform block of a draw into the
     * ring, after submitting the frame when its half of the ring is full.
     *
     * @return where they are, or null when they do not fit even an empty ring
     */
    private QuadData uploadQuads(VKShader s, float[] coords, byte[] colors, int numVertices) {
        long vertexBytes = (long) numVertices * VERTEX_SIZE;
        long colorBytes = (long) numVertices * 4;
        int blockSize = s.getBlockSize();
        QuadData data = allocateQuads(vertexBytes, colorBytes, blockSize);
        if (data == null) {
            submit(0L);
            data = allocateQuads(vertexBytes, colorBytes, blockSize);
        }
        if (data == null) {
            System.err.println("Prism VK error: draw data does not fit the ring buffer");
            return null;
        }
        MemorySegment.copy(coords, 0, ringMapped, JAVA_FLOAT, data.vertexOffset(), numVertices * VERTEX_FLOATS);
        MemorySegment.copy(colors, 0, ringMapped, JAVA_BYTE, data.colorOffset(), numVertices * 4);
        if (blockSize > 0) {
            MemorySegment.copy(s.getUniforms(), 0, ringMapped, data.uniformOffset(), blockSize);
        }
        return data;
    }

    private QuadData allocateQuads(long vertexBytes, long colorBytes, int blockSize) {
        long saved = ringOffset;
        long vertexOffset = ringAlloc(vertexBytes, 4);
        long colorOffset = ringAlloc(colorBytes, 4);
        long uniformOffset = blockSize > 0 ? ringAlloc(blockSize, minUniformAlignment) : 0;
        if (vertexOffset < 0 || colorOffset < 0 || uniformOffset < 0) {
            ringOffset = saved;
            return null;
        }
        return new QuadData(vertexOffset, colorOffset, uniformOffset);
    }

    private void recordQuads(long pipeline, QuadData data, int blockSize, int numVertices) {
        long mark = scratch.mark();
        try {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            pushDescriptors(data.uniformOffset(), blockSize, textures, textureSamplers);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, 64, transform);
            setDrawState(true, CULL_NONE);
            MemorySegment buffers = scratch.allocateFrom(JAVA_LONG, ringBuffer, ringBuffer);
            MemorySegment offsets = scratch.allocateFrom(JAVA_LONG, data.vertexOffset(), data.colorOffset());
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
            vkCmdClearAttachments(cmd, depth ? 2 : 1, clearAttachments(r, g, b, a, depth), 1, rect);
        } finally {
            scratch.reset(mark);
        }
    }

    /** The color to clear to, and the far plane for the depth buffer. */
    private MemorySegment clearAttachments(float r, float g, float b, float a, boolean depth) {
        MemorySegment attachments = VkClearAttachment.allocateArray(scratch, depth ? 2 : 1);
        MemorySegment c = VkClearAttachment.at(attachments, 0);
        VkClearAttachment.aspectMask(c, VK_IMAGE_ASPECT_COLOR_BIT);
        MemorySegment color = VkClearValue.color(VkClearAttachment.clearValue(c));
        float[] rgba = {r, g, b, a};
        for (int i = 0; i < rgba.length; i++) {
            VkClearColorValue.float32(color, i, rgba[i]);
        }
        if (depth) {
            MemorySegment d = VkClearAttachment.at(attachments, 1);
            VkClearAttachment.aspectMask(d, VK_IMAGE_ASPECT_DEPTH_BIT);
            VkClearDepthStencilValue.depth(VkClearValue.depthStencil(VkClearAttachment.clearValue(d)), 1.0f);
        }
        return attachments;
    }

    void blit(VKImage src, VKImage dst, int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        if (src == null || dst == null || src == dst) {
            return;
        }
        if (src.samples > 1) {
            blitMultisampled(src, dst, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
            return;
        }
        transition(src, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        transition(dst, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        long mark = scratch.mark();
        try {
            boolean sameSize = Math.abs(sx1 - sx0) == Math.abs(dx1 - dx0)
                    && Math.abs(sy1 - sy0) == Math.abs(dy1 - dy0);
            vkCmdBlitImage(cmd, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                    blitRegion(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1),
                    sameSize ? VK_FILTER_NEAREST : VK_FILTER_LINEAR);
        } finally {
            scratch.reset(mark);
        }
        transition(src, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        transition(dst, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /**
     * Blits a multisampled image: a resolve when nothing moves or scales, or
     * else a resolve into a temporary image which is then blitted.
     */
    private void blitMultisampled(VKImage src, VKImage dst, int sx0, int sy0, int sx1, int sy1,
                                  int dx0, int dy0, int dx1, int dy1) {
        boolean exact = sx0 == dx0 && sy0 == dy0 && sx1 == dx1 && sy1 == dy1
                && sx1 > sx0 && sy1 > sy0 && dst.samples == 1;
        if (exact) {
            resolve(src, dst, sx0, sy0, sx1 - sx0, sy1 - sy0);
            return;
        }
        VKImage resolved = createImage(VKImage.FORMAT_BGRA8, src.width, src.height, 1, true, false);
        if (resolved == null) {
            return;
        }
        resolve(src, resolved, 0, 0, src.width, src.height);
        blit(resolved, dst, sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1);
        resolved.dispose();
    }

    private MemorySegment blitRegion(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1) {
        MemorySegment region = VkImageBlit.allocate(scratch);
        subresourceLayers(VkImageBlit.srcSubresource(region));
        subresourceLayers(VkImageBlit.dstSubresource(region));
        MemorySegment so = VkImageBlit.srcOffsets(region);
        offset3D(VkOffset3D.at(so, 0), sx0, sy0, 0);
        offset3D(VkOffset3D.at(so, 1), sx1, sy1, 1);
        MemorySegment d = VkImageBlit.dstOffsets(region);
        offset3D(VkOffset3D.at(d, 0), dx0, dy0, 0);
        offset3D(VkOffset3D.at(d, 1), dx1, dy1, 1);
        return region;
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
        copyImage(src, dst, Math.min(Math.min(width, src.width), dst.width),
                Math.min(Math.min(height, src.height), dst.height));
        transition(src, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        releaseToForeign(dst);
        if (!presentWithSyncFile(dst)) {
            // The compositor cannot wait for the GPU; finish before handing the buffer over
            flush();
        }
        if (stats) {
            reportStats();
        }
    }

    private void copyImage(VKImage src, VKImage dst, int width, int height) {
        long mark = scratch.mark();
        try {
            MemorySegment region = VkImageCopy.allocate(scratch);
            subresourceLayers(VkImageCopy.srcSubresource(region));
            subresourceLayers(VkImageCopy.dstSubresource(region));
            extent3D(VkImageCopy.extent(region), width, height);
            vkCmdCopyImage(cmd, src.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dst.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, region);
        } finally {
            scratch.reset(mark);
        }
    }

    private static void extent3D(MemorySegment extent, int width, int height) {
        VkExtent3D.width(extent, width);
        VkExtent3D.height(extent, height);
        VkExtent3D.depth(extent, 1);
    }

    private void reportStats() {
        System.err.printf("Prism VK frame: draws %d render passes %d transitions %d uploads %d flushes %d%n",
                statDraws, statRenderPasses, statTransitions, statUploads, statFlushes);
        statDraws = statRenderPasses = statTransitions = statUploads = statFlushes = 0;
    }

    /**
     * Submits the frame, signaling the present semaphore, and attaches the
     * semaphore, exported as a sync file, to the dma-buf of the frame, which
     * the compositor waits for.
     *
     * @return whether the compositor can wait for the frame; when it cannot,
     *     sync files are not used any more
     */
    private boolean presentWithSyncFile(VKImage exported) {
        if (!syncFileSupported || exported.fd < 0) {
            return false;
        }
        submit(presentSemaphore);
        int syncFd = exportSyncFile();
        if (syncFd == SYNC_FILE_FAILED) {
            System.err.println("Prism VK error: cannot export the frame sync file");
            syncFileSupported = false;
            return false;
        }
        if (syncFd == SYNC_FILE_SIGNALED) {
            return true;
        }
        boolean attached = attachSyncFile(exported.fd, syncFd);
        closeFd(syncFd);
        if (!attached) {
            log("cannot attach the sync file to the dma-buf, waiting for the GPU");
            syncFileSupported = false;
        }
        return attached;
    }

    private static final int SYNC_FILE_SIGNALED = -1;
    private static final int SYNC_FILE_FAILED = -2;

    /**
     * The present semaphore as a sync file, or SYNC_FILE_SIGNALED when it is
     * signaled already, or SYNC_FILE_FAILED.
     */
    private int exportSyncFile() {
        long mark = scratch.mark();
        try {
            MemorySegment info = VkSemaphoreGetFdInfoKHR.allocate(scratch);
            VkSemaphoreGetFdInfoKHR.semaphore(info, presentSemaphore);
            VkSemaphoreGetFdInfoKHR.handleType(info, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
            MemorySegment pFd = scratch.allocate(JAVA_INT);
            pFd.set(JAVA_INT, 0, -1);
            if (vkGetSemaphoreFdKHR(device, info, pFd) != VK_SUCCESS) {
                return SYNC_FILE_FAILED;
            }
            int fd = pFd.get(JAVA_INT, 0);
            return fd < 0 ? SYNC_FILE_SIGNALED : fd;
        } finally {
            scratch.reset(mark);
        }
    }

    /** Attaches a sync file to a dma-buf as the fence of a write (DMA_BUF_IOCTL_IMPORT_SYNC_FILE). */
    private boolean attachSyncFile(int dmabufFd, int syncFd) {
        long mark = scratch.mark();
        try {
            MemorySegment request = scratch.allocate(8, 4);
            request.set(JAVA_INT, 0, DMA_BUF_SYNC_WRITE);
            request.set(JAVA_INT, 4, syncFd);
            return (int) IOCTL.invokeExact(dmabufFd, DMA_BUF_IOCTL_IMPORT_SYNC_FILE, request) == 0;
        } catch (Throwable t) {
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
            img.image = createImageHandle(img);
            if (img.image == 0L) {
                System.err.println("Prism VK error: cannot create a " + width + "x" + height + " image");
                return null;
            }
            img.memory = allocateImageMemory(img.image, exportable);
            if (img.memory == 0L) {
                vkDestroyImage(device, img.image, MemorySegment.NULL);
                return null;
            }
            if (exportable) {
                return export(img) ? img : null;
            }
            img.view = createView(img.image, img.format, VK_IMAGE_ASPECT_COLOR_BIT,
                    renderTarget ? VK_COMPONENT_SWIZZLE_IDENTITY : -1, texFormat);
            return img;
        } finally {
            scratch.reset(mark);
        }
    }

    /** The VkImage of an image, or 0 when it cannot be created. */
    private long createImageHandle(VKImage img) {
        MemorySegment info = VkImageCreateInfo.allocate(scratch);
        if (img.exportable) {
            MemorySegment external = VkExternalMemoryImageCreateInfo.allocate(scratch);
            VkExternalMemoryImageCreateInfo.handleTypes(external, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
            VkImageCreateInfo.pNext(info, external);
        }
        VkImageCreateInfo.imageType(info, VK_IMAGE_TYPE_2D);
        VkImageCreateInfo.format(info, img.format);
        extent3D(VkImageCreateInfo.extent(info), img.width, img.height);
        VkImageCreateInfo.mipLevels(info, 1);
        VkImageCreateInfo.arrayLayers(info, 1);
        VkImageCreateInfo.samples(info, img.samples);
        VkImageCreateInfo.tiling(info, img.exportable ? VK_IMAGE_TILING_LINEAR : VK_IMAGE_TILING_OPTIMAL);
        VkImageCreateInfo.usage(info, imageUsage(img));
        VkImageCreateInfo.sharingMode(info, VK_SHARING_MODE_EXCLUSIVE);
        VkImageCreateInfo.initialLayout(info, VK_IMAGE_LAYOUT_UNDEFINED);
        MemorySegment p = scratch.allocate(JAVA_LONG);
        return vkCreateImage(device, info, MemorySegment.NULL, p) == VK_SUCCESS ? handle(p) : 0L;
    }

    /** Copies from and to every image; sampled unless exported or multisampled; drawn into if a target. */
    private static int imageUsage(VKImage img) {
        int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        if (!img.exportable && img.samples == 1) {
            usage |= VK_IMAGE_USAGE_SAMPLED_BIT;
        }
        if (img.renderTarget) {
            usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        }
        return usage;
    }

    /**
     * Exports the memory of a linear image as a dma-buf, with the layout of
     * its rows; destroys the image when it cannot.
     */
    private boolean export(VKImage img) {
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
            return false;
        }
        img.fd = pFd.get(JAVA_INT, 0);
        return true;
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
            MemorySegment p = scratch.allocate(JAVA_LONG);
            if (vkCreateImage(device, depthImageInfo(img), MemorySegment.NULL, p) != VK_SUCCESS) {
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

    /** A depth buffer of the size and the samples of a target. */
    private MemorySegment depthImageInfo(VKImage img) {
        MemorySegment info = VkImageCreateInfo.allocate(scratch);
        VkImageCreateInfo.imageType(info, VK_IMAGE_TYPE_2D);
        VkImageCreateInfo.format(info, depthFormat);
        extent3D(VkImageCreateInfo.extent(info), img.width, img.height);
        VkImageCreateInfo.mipLevels(info, 1);
        VkImageCreateInfo.arrayLayers(info, 1);
        VkImageCreateInfo.samples(info, img.samples);
        VkImageCreateInfo.tiling(info, VK_IMAGE_TILING_OPTIMAL);
        VkImageCreateInfo.usage(info, VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
        VkImageCreateInfo.sharingMode(info, VK_SHARING_MODE_EXCLUSIVE);
        return info;
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
        Staging staging = stage((long) rowBytes * height);
        long srcBase = (long) srcy * stride + (long) srcx * img.bytesPerPixel;
        copyRows(src, srcBase, stride, staging.mapped(), rowBytes, height);
        transition(img, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        long mark = scratch.mark();
        try {
            vkCmdCopyBufferToImage(cmd, staging.buffer(), img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1,
                    bufferImageRegion(staging.offset(), dstx, dsty, width, height));
            statUploads++;
            // The next draw that samples the image makes it readable
        } finally {
            scratch.reset(mark);
        }
    }

    /** Host memory for an upload, in a buffer at an offset. */
    private record Staging(long buffer, long offset, MemorySegment mapped) {}

    /**
     * Host memory for an upload: in the ring, after submitting the frame when
     * its half is full, or in the staging buffer of the frame when larger.
     */
    private Staging stage(long size) {
        boolean fitsRing = size <= RING_SIZE / FRAMES;
        long offset = fitsRing ? ringAlloc(size, 16) : -1;
        if (offset < 0 && fitsRing) {
            submit(0L);
            offset = ringAlloc(size, 16);
        }
        if (offset >= 0) {
            return new Staging(ringBuffer, offset, ringMapped.asSlice(offset, size));
        }
        Frame frame = frames[frameIndex];
        offset = stagingAlloc(frame, size);
        return new Staging(frame.stagingBuffer, offset, frame.stagingMapped.asSlice(offset, size));
    }

    /** Copies rows that are stride bytes apart into packed rows. */
    private static void copyRows(MemorySegment src, long srcBase, int stride, MemorySegment dst,
                                 int rowBytes, int height) {
        if (stride == rowBytes) {
            MemorySegment.copy(src, srcBase, dst, 0, (long) rowBytes * height);
            return;
        }
        for (int y = 0; y < height; y++) {
            MemorySegment.copy(src, srcBase + (long) y * stride, dst, (long) y * rowBytes, rowBytes);
        }
    }

    /** A copy between packed rows of a buffer and a rectangle of an image. */
    private MemorySegment bufferImageRegion(long bufferOffset, int x, int y, int width, int height) {
        MemorySegment region = VkBufferImageCopy.allocate(scratch);
        VkBufferImageCopy.bufferOffset(region, bufferOffset);
        VkBufferImageCopy.bufferRowLength(region, width);
        VkBufferImageCopy.bufferImageHeight(region, height);
        subresourceLayers(VkBufferImageCopy.imageSubresource(region));
        offset3D(VkBufferImageCopy.imageOffset(region), x, y, 0);
        extent3D(VkBufferImageCopy.imageExtent(region), width, height);
        return region;
    }

    /** Reads a rectangle of pixels from an image, waiting for the rendering. */
    boolean readImage(VKImage img, MemorySegment dst, int x, int y, int width, int height) {
        if (img.samples > 1) {
            return readMultisampled(img, dst, x, y, width, height);
        }
        long size = (long) width * height * img.bytesPerPixel;
        long[] host = createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        try {
            MemorySegment mapped = map(host[1], size);
            int previous = img.layout == VK_IMAGE_LAYOUT_UNDEFINED
                    ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : img.layout;
            transition(img, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            copyToBuffer(img, host[0], x, y, width, height);
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

    /** Reads a multisampled image through a resolved copy. */
    private boolean readMultisampled(VKImage img, MemorySegment dst, int x, int y, int width, int height) {
        VKImage resolved = createImage(VKImage.FORMAT_BGRA8, img.width, img.height, 1, true, false);
        if (resolved == null) {
            return false;
        }
        resolve(img, resolved, 0, 0, img.width, img.height);
        boolean ok = readImage(resolved, dst, x, y, width, height);
        resolved.dispose();
        return ok;
    }

    private void copyToBuffer(VKImage img, long buffer, int x, int y, int width, int height) {
        long mark = scratch.mark();
        try {
            vkCmdCopyImageToBuffer(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1,
                    bufferImageRegion(0, x, y, width, height));
        } finally {
            scratch.reset(mark);
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
            MemorySegment info = VkGraphicsPipelineCreateInfo.allocate(scratch);
            VkGraphicsPipelineCreateInfo.pNext(info, renderingFormats(depth));
            VkGraphicsPipelineCreateInfo.stageCount(info, 2);
            VkGraphicsPipelineCreateInfo.pStages(info, shaderStages(vertex, fragment, specialization));
            VkGraphicsPipelineCreateInfo.pVertexInputState(info, mesh ? meshVertexInput() : quadVertexInput());
            VkGraphicsPipelineCreateInfo.pInputAssemblyState(info, triangleList());
            VkGraphicsPipelineCreateInfo.pViewportState(info, oneViewport());
            VkGraphicsPipelineCreateInfo.pRasterizationState(info, rasterization(wireframe));
            VkGraphicsPipelineCreateInfo.pMultisampleState(info, multisample(sampleIndex));
            VkGraphicsPipelineCreateInfo.pDepthStencilState(info, depthStencil());
            VkGraphicsPipelineCreateInfo.pColorBlendState(info, colorBlend(mode));
            VkGraphicsPipelineCreateInfo.pDynamicState(info, dynamicStates());
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

    /** The vertex and fragment stages, the fragment one with its specialization constants. */
    private MemorySegment shaderStages(long vertex, long fragment, int[] specialization) {
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
            VkPipelineShaderStageCreateInfo.pSpecializationInfo(fs, specializationInfo(specialization));
        }
        return stages;
    }

    /** Specialization constants 0, 1, 2...: one int each. */
    private MemorySegment specializationInfo(int[] constants) {
        MemorySegment entries = VkSpecializationMapEntry.allocateArray(scratch, constants.length);
        for (int i = 0; i < constants.length; i++) {
            MemorySegment e = VkSpecializationMapEntry.at(entries, i);
            VkSpecializationMapEntry.constantID(e, i);
            VkSpecializationMapEntry.offset(e, i * 4);
            VkSpecializationMapEntry.size(e, 4);
        }
        MemorySegment spec = VkSpecializationInfo.allocate(scratch);
        VkSpecializationInfo.mapEntryCount(spec, constants.length);
        VkSpecializationInfo.pMapEntries(spec, entries);
        VkSpecializationInfo.dataSize(spec, constants.length * 4L);
        VkSpecializationInfo.pData(spec, scratch.allocateFrom(JAVA_INT, constants));
        return spec;
    }

    /** Mesh vertices: position, texture coordinates and the tangent frame quaternion. */
    private MemorySegment meshVertexInput() {
        MemorySegment bindings = VkVertexInputBindingDescription.allocateArray(scratch, 1);
        vertexBinding(bindings, 0, MESH_VERTEX_FLOATS * 4);
        MemorySegment attrs = VkVertexInputAttributeDescription.allocateArray(scratch, 3);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 0), 0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 1), 1, 0, VK_FORMAT_R32G32_SFLOAT, 12);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 2), 2, 0, VK_FORMAT_R32G32B32A32_SFLOAT, 20);
        return vertexInput(bindings, 1, attrs, 3);
    }

    /** Quad vertices: position and two texture coordinates, and a color from a second buffer. */
    private MemorySegment quadVertexInput() {
        MemorySegment bindings = VkVertexInputBindingDescription.allocateArray(scratch, 2);
        vertexBinding(VkVertexInputBindingDescription.at(bindings, 0), 0, VERTEX_SIZE);
        vertexBinding(VkVertexInputBindingDescription.at(bindings, 1), 1, 4);
        MemorySegment attrs = VkVertexInputAttributeDescription.allocateArray(scratch, 4);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 0), 0, 0, VK_FORMAT_R32G32B32_SFLOAT, 0);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 1), 1, 0, VK_FORMAT_R32G32_SFLOAT, 12);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 2), 2, 0, VK_FORMAT_R32G32_SFLOAT, 20);
        vertexAttribute(VkVertexInputAttributeDescription.at(attrs, 3), 3, 1, VK_FORMAT_R8G8B8A8_UNORM, 0);
        return vertexInput(bindings, 2, attrs, 4);
    }

    private MemorySegment vertexInput(MemorySegment bindings, int bindingCount, MemorySegment attrs, int attrCount) {
        MemorySegment vertexInput = VkPipelineVertexInputStateCreateInfo.allocate(scratch);
        VkPipelineVertexInputStateCreateInfo.vertexBindingDescriptionCount(vertexInput, bindingCount);
        VkPipelineVertexInputStateCreateInfo.pVertexBindingDescriptions(vertexInput, bindings);
        VkPipelineVertexInputStateCreateInfo.vertexAttributeDescriptionCount(vertexInput, attrCount);
        VkPipelineVertexInputStateCreateInfo.pVertexAttributeDescriptions(vertexInput, attrs);
        return vertexInput;
    }

    private MemorySegment triangleList() {
        MemorySegment inputAssembly = VkPipelineInputAssemblyStateCreateInfo.allocate(scratch);
        VkPipelineInputAssemblyStateCreateInfo.topology(inputAssembly, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        return inputAssembly;
    }

    /** One viewport and one scissor, both dynamic. */
    private MemorySegment oneViewport() {
        MemorySegment viewport = VkPipelineViewportStateCreateInfo.allocate(scratch);
        VkPipelineViewportStateCreateInfo.viewportCount(viewport, 1);
        VkPipelineViewportStateCreateInfo.scissorCount(viewport, 1);
        return viewport;
    }

    /** Filled triangles, or their edges for a wireframe when the device can draw them. */
    private MemorySegment rasterization(boolean wireframe) {
        MemorySegment raster = VkPipelineRasterizationStateCreateInfo.allocate(scratch);
        VkPipelineRasterizationStateCreateInfo.polygonMode(raster,
                wireframe && fillModeNonSolid ? VK_POLYGON_MODE_LINE : VK_POLYGON_MODE_FILL);
        VkPipelineRasterizationStateCreateInfo.cullMode(raster, VK_CULL_MODE_NONE);
        VkPipelineRasterizationStateCreateInfo.frontFace(raster, VK_FRONT_FACE_COUNTER_CLOCKWISE);
        VkPipelineRasterizationStateCreateInfo.lineWidth(raster, 1.0f);
        return raster;
    }

    private MemorySegment multisample(int sampleIndex) {
        MemorySegment multisample = VkPipelineMultisampleStateCreateInfo.allocate(scratch);
        VkPipelineMultisampleStateCreateInfo.rasterizationSamples(multisample, 1 << sampleIndex);
        return multisample;
    }

    /** The depth comparison; the test and the writes are dynamic. */
    private MemorySegment depthStencil() {
        MemorySegment depthStencil = VkPipelineDepthStencilStateCreateInfo.allocate(scratch);
        VkPipelineDepthStencilStateCreateInfo.depthCompareOp(depthStencil, VK_COMPARE_OP_LESS_OR_EQUAL);
        VkPipelineDepthStencilStateCreateInfo.maxDepthBounds(depthStencil, 1.0f);
        return depthStencil;
    }

    private MemorySegment colorBlend(int mode) {
        MemorySegment blendAttachment = VkPipelineColorBlendAttachmentState.allocate(scratch);
        blendState(blendAttachment, mode);
        MemorySegment blend = VkPipelineColorBlendStateCreateInfo.allocate(scratch);
        VkPipelineColorBlendStateCreateInfo.attachmentCount(blend, 1);
        VkPipelineColorBlendStateCreateInfo.pAttachments(blend, blendAttachment);
        return blend;
    }

    private static final int[] DYNAMIC_STATES = {
        VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR, VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE,
        VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE, VK_DYNAMIC_STATE_DEPTH_COMPARE_OP,
        VK_DYNAMIC_STATE_CULL_MODE, VK_DYNAMIC_STATE_FRONT_FACE
    };

    private MemorySegment dynamicStates() {
        MemorySegment dynamic = VkPipelineDynamicStateCreateInfo.allocate(scratch);
        VkPipelineDynamicStateCreateInfo.dynamicStateCount(dynamic, DYNAMIC_STATES.length);
        VkPipelineDynamicStateCreateInfo.pDynamicStates(dynamic, scratch.allocateFrom(JAVA_INT, DYNAMIC_STATES));
        return dynamic;
    }

    /** The formats of the attachments of dynamic rendering: BGRA, and the depth buffer if any. */
    private MemorySegment renderingFormats(boolean depth) {
        MemorySegment rendering = VkPipelineRenderingCreateInfo.allocate(scratch);
        VkPipelineRenderingCreateInfo.colorAttachmentCount(rendering, 1);
        VkPipelineRenderingCreateInfo.pColorAttachmentFormats(rendering,
                scratch.allocateFrom(JAVA_INT, VK_FORMAT_B8G8R8A8_UNORM));
        VkPipelineRenderingCreateInfo.depthAttachmentFormat(rendering, depth ? depthFormat : VK_FORMAT_UNDEFINED);
        return rendering;
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
        MeshBuffers mesh = new MeshBuffers();
        try {
            long[] vb = createFilledBuffer(vertexCount * 4L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    m -> MemorySegment.copy(vertices, 0, m, JAVA_FLOAT, 0, vertexCount));
            mesh.vertexBuffer = vb[0];
            mesh.vertexMemory = vb[1];
            long[] ib = createFilledBuffer(indexCount * (shortIndices ? 2L : 4L), VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    m -> copyIndices(indices, m, indexCount));
            mesh.indexBuffer = ib[0];
            mesh.indexMemory = ib[1];
            mesh.indexType = shortIndices ? VK_INDEX_TYPE_UINT16 : VK_INDEX_TYPE_UINT32;
            mesh.indexCount = indexCount;
            return mesh;
        } catch (VKException e) {
            destroyMeshBuffers(mesh);
            return null;
        }
    }

    private static void copyIndices(Object indices, MemorySegment dst, int count) {
        switch (indices) {
            case short[] s -> MemorySegment.copy(s, 0, dst, ValueLayout.JAVA_SHORT, 0, count);
            case int[] i -> MemorySegment.copy(i, 0, dst, JAVA_INT, 0, count);
            default -> throw new IllegalArgumentException("Indices must be short[] or int[]");
        }
    }

    /** A host-visible buffer, filled once by a writer, returned as {buffer, memory}. */
    private long[] createFilledBuffer(long size, int usage, java.util.function.Consumer<MemorySegment> writer) {
        long[] buffer = createBuffer(size, usage,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        writer.accept(map(buffer[1], size));
        vkUnmapMemory(device, buffer[1]);
        return buffer;
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
        VKImage[] images = meshImages(maps);
        long[] imageSamplers = meshSamplers(linear);
        long uniformOffset = uploadUniforms(uniforms);
        if (!beginRendering()) {
            return;
        }
        long pipeline = phongPipeline(variant, wireframe);
        if (pipeline != 0L) {
            recordMesh(mesh, pipeline, cull, uniformOffset, uniforms.length * 4L, images, imageSamplers);
        }
    }

    /** The maps of a material, readable, with the white texture for the missing ones. */
    private VKImage[] meshImages(VKImage[] maps) {
        VKImage[] images = new VKImage[MAX_TEXTURE_UNITS];
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            VKImage img = maps[i] != null && !maps[i].disposed ? maps[i] : dummyTexture();
            transition(img, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            images[i] = img;
        }
        return images;
    }

    private long[] meshSamplers(boolean[] linear) {
        long[] imageSamplers = new long[MAX_TEXTURE_UNITS];
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            imageSamplers[i] = samplers[linear[i] ? 1 : 0][WRAP_REPEAT];
        }
        return imageSamplers;
    }

    /** Copies a uniform block into the ring, after submitting the frame when its half is full. */
    private long uploadUniforms(float[] uniforms) {
        long bytes = uniforms.length * 4L;
        long offset = ringAlloc(bytes, minUniformAlignment);
        if (offset < 0) {
            submit(0L);
            offset = ringAlloc(bytes, minUniformAlignment);
        }
        MemorySegment.copy(uniforms, 0, ringMapped, JAVA_FLOAT, offset, uniforms.length);
        return offset;
    }

    /** The Phong pipeline of a material variant and of the target, created on first use. */
    private long phongPipeline(int[] variant, boolean wireframe) {
        int key = 0;
        for (int v : variant) {
            key = key * 4 + v;
        }
        key = ((key * 2 + (wireframe ? 1 : 0)) * 2 + (target.hasDepth() ? 1 : 0)) * 4 + target.sampleIndex();
        return phongPipelines.computeIfAbsent(key, k -> createPipeline(phongVertexModule, phongFragmentModule,
                true, variant, COMP_SRCOVER, target.hasDepth(), target.sampleIndex(), wireframe));
    }

    private void recordMesh(MeshBuffers mesh, long pipeline, int cull, long uniformOffset, long uniformBytes,
                            VKImage[] images, long[] imageSamplers) {
        long mark = scratch.mark();
        try {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            pushDescriptors(uniformOffset, uniformBytes, images, imageSamplers);
            // Meshes are not clipped
            setDrawState(false, cull);
            vkCmdBindVertexBuffers(cmd, 0, 1, scratch.allocateFrom(JAVA_LONG, mesh.vertexBuffer),
                    scratch.allocateFrom(JAVA_LONG, 0L));
            vkCmdBindIndexBuffer(cmd, mesh.indexBuffer, 0, mesh.indexType);
            vkCmdDrawIndexed(cmd, mesh.indexCount, 1, 0, 0, 0);
            statDraws++;
        } finally {
            scratch.reset(mark);
        }
    }
}
