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

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The native functions of the ES2 pipeline, through the foreign function and
 * memory API, in place of the JNI library prism_es2: EGL and GBM from their
 * libraries, the OpenGL functions from eglGetProcAddress, and a few functions
 * of libc. Generated from a list of signatures; the functions that read or
 * write Java arrays are critical downcalls with heap access.
 * <p>
 * The handles are resolved when the classes are initialized: a function that
 * is not found throws UnsupportedOperationException when it is called.
 */
final class ES2Native {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final Arena ARENA = Arena.global();
    private static final SymbolLookup LIBEGL = library("libEGL.so.1");
    private static final SymbolLookup LIBGBM = library("libgbm.so.1");

    private ES2Native() {
    }

    private static SymbolLookup library(String name) {
        try {
            return SymbolLookup.libraryLookup(name, ARENA);
        } catch (IllegalArgumentException e) {
            return _ -> Optional.empty();
        }
    }

    private static MethodHandle bind(Optional<MemorySegment> address, FunctionDescriptor descriptor,
                                     Linker.Option... options) {
        return address.filter(a -> a.address() != 0)
                .map(a -> LINKER.downcallHandle(a, descriptor, options))
                .orElse(null);
    }

    private static MethodHandle egl(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return bind(LIBEGL.find(name), descriptor, options);
    }

    private static MethodHandle gbm(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return bind(LIBGBM.find(name), descriptor, options);
    }

    private static MethodHandle libc(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return bind(LINKER.defaultLookup().find(name), descriptor, options);
    }

    /** The extensions and the OpenGL functions, from eglGetProcAddress. */
    private static MethodHandle proc(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        return bind(Optional.of(EGL.eglGetProcAddress(ARENA.allocateFrom(name))), descriptor, options);
    }

    private static RuntimeException failure(String name, Throwable t) {
        if (t instanceof NullPointerException) {
            return new UnsupportedOperationException(name + " is not available");
        }
        return t instanceof RuntimeException re ? re : new IllegalStateException(name, t);
    }

    /** EGL 1.5, from libEGL. */
    static final class EGL {
        private EGL() {
        }
        private static final MethodHandle EGLGETPROCADDRESS = egl("eglGetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS));
        private static final MethodHandle EGLGETPLATFORMDISPLAY = egl("eglGetPlatformDisplay", FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle EGLINITIALIZE = egl("eglInitialize", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle EGLTERMINATE = egl("eglTerminate", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle EGLBINDAPI = egl("eglBindAPI", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        private static final MethodHandle EGLCHOOSECONFIG = egl("eglChooseConfig", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle EGLCREATECONTEXT = egl("eglCreateContext", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle EGLDESTROYCONTEXT = egl("eglDestroyContext", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle EGLMAKECURRENT = egl("eglMakeCurrent", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle EGLQUERYSTRING = egl("eglQueryString", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle EGLGETERROR = egl("eglGetError", FunctionDescriptor.of(JAVA_INT));

        static MemorySegment eglGetProcAddress(MemorySegment name) {
            try {
                return (MemorySegment) EGLGETPROCADDRESS.invokeExact(name);
            } catch (Throwable t) {
                throw failure("eglGetProcAddress", t);
            }
        }

        static MemorySegment eglGetPlatformDisplay(int platform, MemorySegment nativeDisplay, MemorySegment attribs) {
            try {
                return (MemorySegment) EGLGETPLATFORMDISPLAY.invokeExact(platform, nativeDisplay, attribs);
            } catch (Throwable t) {
                throw failure("eglGetPlatformDisplay", t);
            }
        }

        static int eglInitialize(MemorySegment display, MemorySegment major, MemorySegment minor) {
            try {
                return (int) EGLINITIALIZE.invokeExact(display, major, minor);
            } catch (Throwable t) {
                throw failure("eglInitialize", t);
            }
        }

        static int eglTerminate(MemorySegment display) {
            try {
                return (int) EGLTERMINATE.invokeExact(display);
            } catch (Throwable t) {
                throw failure("eglTerminate", t);
            }
        }

        static int eglBindAPI(int api) {
            try {
                return (int) EGLBINDAPI.invokeExact(api);
            } catch (Throwable t) {
                throw failure("eglBindAPI", t);
            }
        }

        static int eglChooseConfig(MemorySegment display, MemorySegment attribs, MemorySegment configs, int size, MemorySegment count) {
            try {
                return (int) EGLCHOOSECONFIG.invokeExact(display, attribs, configs, size, count);
            } catch (Throwable t) {
                throw failure("eglChooseConfig", t);
            }
        }

        static MemorySegment eglCreateContext(MemorySegment display, MemorySegment config, MemorySegment share, MemorySegment attribs) {
            try {
                return (MemorySegment) EGLCREATECONTEXT.invokeExact(display, config, share, attribs);
            } catch (Throwable t) {
                throw failure("eglCreateContext", t);
            }
        }

        static int eglDestroyContext(MemorySegment display, MemorySegment context) {
            try {
                return (int) EGLDESTROYCONTEXT.invokeExact(display, context);
            } catch (Throwable t) {
                throw failure("eglDestroyContext", t);
            }
        }

        static int eglMakeCurrent(MemorySegment display, MemorySegment draw, MemorySegment read, MemorySegment context) {
            try {
                return (int) EGLMAKECURRENT.invokeExact(display, draw, read, context);
            } catch (Throwable t) {
                throw failure("eglMakeCurrent", t);
            }
        }

        static MemorySegment eglQueryString(MemorySegment display, int name) {
            try {
                return (MemorySegment) EGLQUERYSTRING.invokeExact(display, name);
            } catch (Throwable t) {
                throw failure("eglQueryString", t);
            }
        }

        static int eglGetError() {
            try {
                return (int) EGLGETERROR.invokeExact();
            } catch (Throwable t) {
                throw failure("eglGetError", t);
            }
        }
    }

    /** The EGL extensions, from eglGetProcAddress. */
    static final class EGLX {
        private EGLX() {
        }
        private static final MethodHandle EGLCREATEIMAGEKHR = proc("eglCreateImageKHR", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle EGLDESTROYIMAGEKHR = proc("eglDestroyImageKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle EGLCREATESYNCKHR = proc("eglCreateSyncKHR", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle EGLDESTROYSYNCKHR = proc("eglDestroySyncKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle EGLDUPNATIVEFENCEFDANDROID = proc("eglDupNativeFenceFDANDROID", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle EGLQUERYDISPLAYATTRIBEXT = proc("eglQueryDisplayAttribEXT", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        private static final MethodHandle EGLQUERYDEVICESTRINGEXT = proc("eglQueryDeviceStringEXT", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));

        static MemorySegment eglCreateImageKHR(MemorySegment display, MemorySegment context, int target, MemorySegment buffer, MemorySegment attribs) {
            try {
                return (MemorySegment) EGLCREATEIMAGEKHR.invokeExact(display, context, target, buffer, attribs);
            } catch (Throwable t) {
                throw failure("eglCreateImageKHR", t);
            }
        }

        static int eglDestroyImageKHR(MemorySegment display, MemorySegment image) {
            try {
                return (int) EGLDESTROYIMAGEKHR.invokeExact(display, image);
            } catch (Throwable t) {
                throw failure("eglDestroyImageKHR", t);
            }
        }

        static MemorySegment eglCreateSyncKHR(MemorySegment display, int type, MemorySegment attribs) {
            try {
                return (MemorySegment) EGLCREATESYNCKHR.invokeExact(display, type, attribs);
            } catch (Throwable t) {
                throw failure("eglCreateSyncKHR", t);
            }
        }

        static int eglDestroySyncKHR(MemorySegment display, MemorySegment sync) {
            try {
                return (int) EGLDESTROYSYNCKHR.invokeExact(display, sync);
            } catch (Throwable t) {
                throw failure("eglDestroySyncKHR", t);
            }
        }

        static int eglDupNativeFenceFDANDROID(MemorySegment display, MemorySegment sync) {
            try {
                return (int) EGLDUPNATIVEFENCEFDANDROID.invokeExact(display, sync);
            } catch (Throwable t) {
                throw failure("eglDupNativeFenceFDANDROID", t);
            }
        }

        static int eglQueryDisplayAttribEXT(MemorySegment display, int attribute, MemorySegment value) {
            try {
                return (int) EGLQUERYDISPLAYATTRIBEXT.invokeExact(display, attribute, value);
            } catch (Throwable t) {
                throw failure("eglQueryDisplayAttribEXT", t);
            }
        }

        static MemorySegment eglQueryDeviceStringEXT(MemorySegment device, int name) {
            try {
                return (MemorySegment) EGLQUERYDEVICESTRINGEXT.invokeExact(device, name);
            } catch (Throwable t) {
                throw failure("eglQueryDeviceStringEXT", t);
            }
        }
    }

    /** GBM, from libgbm. */
    static final class GBM {
        private GBM() {
        }
        private static final MethodHandle GBM_CREATE_DEVICE = gbm("gbm_create_device", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        private static final MethodHandle GBM_DEVICE_DESTROY = gbm("gbm_device_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        private static final MethodHandle GBM_BO_CREATE = gbm("gbm_bo_create", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GBM_BO_DESTROY = gbm("gbm_bo_destroy", FunctionDescriptor.ofVoid(ADDRESS));
        private static final MethodHandle GBM_BO_GET_FD = gbm("gbm_bo_get_fd", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle GBM_BO_GET_STRIDE = gbm("gbm_bo_get_stride", FunctionDescriptor.of(JAVA_INT, ADDRESS));

        static MemorySegment gbm_create_device(int fd) {
            try {
                return (MemorySegment) GBM_CREATE_DEVICE.invokeExact(fd);
            } catch (Throwable t) {
                throw failure("gbm_create_device", t);
            }
        }

        static void gbm_device_destroy(MemorySegment gbm) {
            try {
                GBM_DEVICE_DESTROY.invokeExact(gbm);
            } catch (Throwable t) {
                throw failure("gbm_device_destroy", t);
            }
        }

        static MemorySegment gbm_bo_create(MemorySegment gbm, int width, int height, int format, int flags) {
            try {
                return (MemorySegment) GBM_BO_CREATE.invokeExact(gbm, width, height, format, flags);
            } catch (Throwable t) {
                throw failure("gbm_bo_create", t);
            }
        }

        static void gbm_bo_destroy(MemorySegment bo) {
            try {
                GBM_BO_DESTROY.invokeExact(bo);
            } catch (Throwable t) {
                throw failure("gbm_bo_destroy", t);
            }
        }

        static int gbm_bo_get_fd(MemorySegment bo) {
            try {
                return (int) GBM_BO_GET_FD.invokeExact(bo);
            } catch (Throwable t) {
                throw failure("gbm_bo_get_fd", t);
            }
        }

        static int gbm_bo_get_stride(MemorySegment bo) {
            try {
                return (int) GBM_BO_GET_STRIDE.invokeExact(bo);
            } catch (Throwable t) {
                throw failure("gbm_bo_get_stride", t);
            }
        }
    }

    /** The functions of libc. */
    static final class LibC {
        private LibC() {
        }
        private static final MethodHandle OPEN = libc("open", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), Linker.Option.firstVariadicArg(2));
        private static final MethodHandle CLOSE = libc("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        private static final MethodHandle IOCTL = libc("ioctl", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS), Linker.Option.firstVariadicArg(2));

        static int open(MemorySegment path, int flags) {
            try {
                return (int) OPEN.invokeExact(path, flags);
            } catch (Throwable t) {
                throw failure("open", t);
            }
        }

        static int close(int fd) {
            try {
                return (int) CLOSE.invokeExact(fd);
            } catch (Throwable t) {
                throw failure("close", t);
            }
        }

        static int ioctl(int fd, long request, MemorySegment arg) {
            try {
                return (int) IOCTL.invokeExact(fd, request, arg);
            } catch (Throwable t) {
                throw failure("ioctl", t);
            }
        }
    }

    /** OpenGL 2.1 with framebuffer objects, from eglGetProcAddress. */
    static final class GL {
        private GL() {
        }
        private static final MethodHandle GLGETERROR = proc("glGetError", FunctionDescriptor.of(JAVA_INT));
        private static final MethodHandle GLGETSTRING = proc("glGetString", FunctionDescriptor.of(ADDRESS, JAVA_INT));
        private static final MethodHandle GLGETINTEGERV = proc("glGetIntegerv", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLENABLE = proc("glEnable", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLDISABLE = proc("glDisable", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLBLENDFUNC = proc("glBlendFunc", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLDEPTHMASK = proc("glDepthMask", FunctionDescriptor.ofVoid(JAVA_BYTE));
        private static final MethodHandle GLDEPTHFUNC = proc("glDepthFunc", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLCULLFACE = proc("glCullFace", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLFRONTFACE = proc("glFrontFace", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLPOLYGONMODE = proc("glPolygonMode", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLSCISSOR = proc("glScissor", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLVIEWPORT = proc("glViewport", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLCLEARCOLOR = proc("glClearColor", FunctionDescriptor.ofVoid(JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        private static final MethodHandle GLCLEAR = proc("glClear", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLPIXELSTOREI = proc("glPixelStorei", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLFINISH = proc("glFinish", FunctionDescriptor.ofVoid());
        private static final MethodHandle GLFLUSH = proc("glFlush", FunctionDescriptor.ofVoid());
        private static final MethodHandle GLGENTEXTURES = proc("glGenTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLDELETETEXTURES = proc("glDeleteTextures", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLBINDTEXTURE = proc("glBindTexture", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLACTIVETEXTURE = proc("glActiveTexture", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLTEXIMAGE2D = proc("glTexImage2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLTEXSUBIMAGE2D = proc("glTexSubImage2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLTEXPARAMETERI = proc("glTexParameteri", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLGENERATEMIPMAP = proc("glGenerateMipmap", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLREADPIXELS = proc("glReadPixels", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLGENFRAMEBUFFERS = proc("glGenFramebuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLDELETEFRAMEBUFFERS = proc("glDeleteFramebuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLBINDFRAMEBUFFER = proc("glBindFramebuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLFRAMEBUFFERTEXTURE2D = proc("glFramebufferTexture2D", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLFRAMEBUFFERRENDERBUFFER = proc("glFramebufferRenderbuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLCHECKFRAMEBUFFERSTATUS = proc("glCheckFramebufferStatus", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLBLITFRAMEBUFFER = proc("glBlitFramebuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLGENRENDERBUFFERS = proc("glGenRenderbuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLDELETERENDERBUFFERS = proc("glDeleteRenderbuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLBINDRENDERBUFFER = proc("glBindRenderbuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLRENDERBUFFERSTORAGE = proc("glRenderbufferStorage", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLRENDERBUFFERSTORAGEMULTISAMPLE = proc("glRenderbufferStorageMultisample", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLCREATESHADER = proc("glCreateShader", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLSHADERSOURCE = proc("glShaderSource", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GLCOMPILESHADER = proc("glCompileShader", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLGETSHADERIV = proc("glGetShaderiv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle GLGETSHADERINFOLOG = proc("glGetShaderInfoLog", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GLDELETESHADER = proc("glDeleteShader", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLCREATEPROGRAM = proc("glCreateProgram", FunctionDescriptor.of(JAVA_INT));
        private static final MethodHandle GLATTACHSHADER = proc("glAttachShader", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLDETACHSHADER = proc("glDetachShader", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLBINDATTRIBLOCATION = proc("glBindAttribLocation", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle GLLINKPROGRAM = proc("glLinkProgram", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLGETPROGRAMIV = proc("glGetProgramiv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle GLGETPROGRAMINFOLOG = proc("glGetProgramInfoLog", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GLUSEPROGRAM = proc("glUseProgram", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLDELETEPROGRAM = proc("glDeleteProgram", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLGETUNIFORMLOCATION = proc("glGetUniformLocation", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
        private static final MethodHandle GLUNIFORM1F = proc("glUniform1f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT));
        private static final MethodHandle GLUNIFORM2F = proc("glUniform2f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT));
        private static final MethodHandle GLUNIFORM3F = proc("glUniform3f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        private static final MethodHandle GLUNIFORM4F = proc("glUniform4f", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT));
        private static final MethodHandle GLUNIFORM4FV = proc("glUniform4fv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLUNIFORM1I = proc("glUniform1i", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLUNIFORM2I = proc("glUniform2i", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLUNIFORM3I = proc("glUniform3i", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLUNIFORM4I = proc("glUniform4i", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle GLUNIFORM4IV = proc("glUniform4iv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLUNIFORMMATRIX4FV = proc("glUniformMatrix4fv", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_BYTE, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLGENBUFFERS = proc("glGenBuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLDELETEBUFFERS = proc("glDeleteBuffers", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));
        private static final MethodHandle GLBINDBUFFER = proc("glBindBuffer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT));
        private static final MethodHandle GLBUFFERDATA = proc("glBufferData", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT), Linker.Option.critical(true));
        private static final MethodHandle GLBUFFERSUBDATA = proc("glBufferSubData", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS), Linker.Option.critical(true));
        private static final MethodHandle GLENABLEVERTEXATTRIBARRAY = proc("glEnableVertexAttribArray", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLDISABLEVERTEXATTRIBARRAY = proc("glDisableVertexAttribArray", FunctionDescriptor.ofVoid(JAVA_INT));
        private static final MethodHandle GLVERTEXATTRIBPOINTER = proc("glVertexAttribPointer", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_BYTE, JAVA_INT, JAVA_LONG));
        private static final MethodHandle GLDRAWELEMENTS = proc("glDrawElements", FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
        private static final MethodHandle GLEGLIMAGETARGETTEXTURE2DOES = proc("glEGLImageTargetTexture2DOES", FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS));

        static int glGetError() {
            try {
                return (int) GLGETERROR.invokeExact();
            } catch (Throwable t) {
                throw failure("glGetError", t);
            }
        }

        static MemorySegment glGetString(int name) {
            try {
                return (MemorySegment) GLGETSTRING.invokeExact(name);
            } catch (Throwable t) {
                throw failure("glGetString", t);
            }
        }

        static void glGetIntegerv(int name, MemorySegment data) {
            try {
                GLGETINTEGERV.invokeExact(name, data);
            } catch (Throwable t) {
                throw failure("glGetIntegerv", t);
            }
        }

        static void glEnable(int cap) {
            try {
                GLENABLE.invokeExact(cap);
            } catch (Throwable t) {
                throw failure("glEnable", t);
            }
        }

        static void glDisable(int cap) {
            try {
                GLDISABLE.invokeExact(cap);
            } catch (Throwable t) {
                throw failure("glDisable", t);
            }
        }

        static void glBlendFunc(int sfactor, int dfactor) {
            try {
                GLBLENDFUNC.invokeExact(sfactor, dfactor);
            } catch (Throwable t) {
                throw failure("glBlendFunc", t);
            }
        }

        static void glDepthMask(byte flag) {
            try {
                GLDEPTHMASK.invokeExact(flag);
            } catch (Throwable t) {
                throw failure("glDepthMask", t);
            }
        }

        static void glDepthFunc(int func) {
            try {
                GLDEPTHFUNC.invokeExact(func);
            } catch (Throwable t) {
                throw failure("glDepthFunc", t);
            }
        }

        static void glCullFace(int mode) {
            try {
                GLCULLFACE.invokeExact(mode);
            } catch (Throwable t) {
                throw failure("glCullFace", t);
            }
        }

        static void glFrontFace(int mode) {
            try {
                GLFRONTFACE.invokeExact(mode);
            } catch (Throwable t) {
                throw failure("glFrontFace", t);
            }
        }

        static void glPolygonMode(int face, int mode) {
            try {
                GLPOLYGONMODE.invokeExact(face, mode);
            } catch (Throwable t) {
                throw failure("glPolygonMode", t);
            }
        }

        static void glScissor(int x, int y, int width, int height) {
            try {
                GLSCISSOR.invokeExact(x, y, width, height);
            } catch (Throwable t) {
                throw failure("glScissor", t);
            }
        }

        static void glViewport(int x, int y, int width, int height) {
            try {
                GLVIEWPORT.invokeExact(x, y, width, height);
            } catch (Throwable t) {
                throw failure("glViewport", t);
            }
        }

        static void glClearColor(float red, float green, float blue, float alpha) {
            try {
                GLCLEARCOLOR.invokeExact(red, green, blue, alpha);
            } catch (Throwable t) {
                throw failure("glClearColor", t);
            }
        }

        static void glClear(int mask) {
            try {
                GLCLEAR.invokeExact(mask);
            } catch (Throwable t) {
                throw failure("glClear", t);
            }
        }

        static void glPixelStorei(int name, int param) {
            try {
                GLPIXELSTOREI.invokeExact(name, param);
            } catch (Throwable t) {
                throw failure("glPixelStorei", t);
            }
        }

        static void glFinish() {
            try {
                GLFINISH.invokeExact();
            } catch (Throwable t) {
                throw failure("glFinish", t);
            }
        }

        static void glFlush() {
            try {
                GLFLUSH.invokeExact();
            } catch (Throwable t) {
                throw failure("glFlush", t);
            }
        }

        static void glGenTextures(int n, MemorySegment textures) {
            try {
                GLGENTEXTURES.invokeExact(n, textures);
            } catch (Throwable t) {
                throw failure("glGenTextures", t);
            }
        }

        static void glDeleteTextures(int n, MemorySegment textures) {
            try {
                GLDELETETEXTURES.invokeExact(n, textures);
            } catch (Throwable t) {
                throw failure("glDeleteTextures", t);
            }
        }

        static void glBindTexture(int target, int texture) {
            try {
                GLBINDTEXTURE.invokeExact(target, texture);
            } catch (Throwable t) {
                throw failure("glBindTexture", t);
            }
        }

        static void glActiveTexture(int texture) {
            try {
                GLACTIVETEXTURE.invokeExact(texture);
            } catch (Throwable t) {
                throw failure("glActiveTexture", t);
            }
        }

        static void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, MemorySegment pixels) {
            try {
                GLTEXIMAGE2D.invokeExact(target, level, internalFormat, width, height, border, format, type, pixels);
            } catch (Throwable t) {
                throw failure("glTexImage2D", t);
            }
        }

        static void glTexSubImage2D(int target, int level, int x, int y, int width, int height, int format, int type, MemorySegment pixels) {
            try {
                GLTEXSUBIMAGE2D.invokeExact(target, level, x, y, width, height, format, type, pixels);
            } catch (Throwable t) {
                throw failure("glTexSubImage2D", t);
            }
        }

        static void glTexParameteri(int target, int name, int param) {
            try {
                GLTEXPARAMETERI.invokeExact(target, name, param);
            } catch (Throwable t) {
                throw failure("glTexParameteri", t);
            }
        }

        static void glGenerateMipmap(int target) {
            try {
                GLGENERATEMIPMAP.invokeExact(target);
            } catch (Throwable t) {
                throw failure("glGenerateMipmap", t);
            }
        }

        static void glReadPixels(int x, int y, int width, int height, int format, int type, MemorySegment pixels) {
            try {
                GLREADPIXELS.invokeExact(x, y, width, height, format, type, pixels);
            } catch (Throwable t) {
                throw failure("glReadPixels", t);
            }
        }

        static void glGenFramebuffers(int n, MemorySegment framebuffers) {
            try {
                GLGENFRAMEBUFFERS.invokeExact(n, framebuffers);
            } catch (Throwable t) {
                throw failure("glGenFramebuffers", t);
            }
        }

        static void glDeleteFramebuffers(int n, MemorySegment framebuffers) {
            try {
                GLDELETEFRAMEBUFFERS.invokeExact(n, framebuffers);
            } catch (Throwable t) {
                throw failure("glDeleteFramebuffers", t);
            }
        }

        static void glBindFramebuffer(int target, int framebuffer) {
            try {
                GLBINDFRAMEBUFFER.invokeExact(target, framebuffer);
            } catch (Throwable t) {
                throw failure("glBindFramebuffer", t);
            }
        }

        static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
            try {
                GLFRAMEBUFFERTEXTURE2D.invokeExact(target, attachment, textarget, texture, level);
            } catch (Throwable t) {
                throw failure("glFramebufferTexture2D", t);
            }
        }

        static void glFramebufferRenderbuffer(int target, int attachment, int renderbuffertarget, int renderbuffer) {
            try {
                GLFRAMEBUFFERRENDERBUFFER.invokeExact(target, attachment, renderbuffertarget, renderbuffer);
            } catch (Throwable t) {
                throw failure("glFramebufferRenderbuffer", t);
            }
        }

        static int glCheckFramebufferStatus(int target) {
            try {
                return (int) GLCHECKFRAMEBUFFERSTATUS.invokeExact(target);
            } catch (Throwable t) {
                throw failure("glCheckFramebufferStatus", t);
            }
        }

        static void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) {
            try {
                GLBLITFRAMEBUFFER.invokeExact(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
            } catch (Throwable t) {
                throw failure("glBlitFramebuffer", t);
            }
        }

        static void glGenRenderbuffers(int n, MemorySegment renderbuffers) {
            try {
                GLGENRENDERBUFFERS.invokeExact(n, renderbuffers);
            } catch (Throwable t) {
                throw failure("glGenRenderbuffers", t);
            }
        }

        static void glDeleteRenderbuffers(int n, MemorySegment renderbuffers) {
            try {
                GLDELETERENDERBUFFERS.invokeExact(n, renderbuffers);
            } catch (Throwable t) {
                throw failure("glDeleteRenderbuffers", t);
            }
        }

        static void glBindRenderbuffer(int target, int renderbuffer) {
            try {
                GLBINDRENDERBUFFER.invokeExact(target, renderbuffer);
            } catch (Throwable t) {
                throw failure("glBindRenderbuffer", t);
            }
        }

        static void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
            try {
                GLRENDERBUFFERSTORAGE.invokeExact(target, internalFormat, width, height);
            } catch (Throwable t) {
                throw failure("glRenderbufferStorage", t);
            }
        }

        static void glRenderbufferStorageMultisample(int target, int samples, int internalFormat, int width, int height) {
            try {
                GLRENDERBUFFERSTORAGEMULTISAMPLE.invokeExact(target, samples, internalFormat, width, height);
            } catch (Throwable t) {
                throw failure("glRenderbufferStorageMultisample", t);
            }
        }

        static int glCreateShader(int type) {
            try {
                return (int) GLCREATESHADER.invokeExact(type);
            } catch (Throwable t) {
                throw failure("glCreateShader", t);
            }
        }

        static void glShaderSource(int shader, int count, MemorySegment strings, MemorySegment lengths) {
            try {
                GLSHADERSOURCE.invokeExact(shader, count, strings, lengths);
            } catch (Throwable t) {
                throw failure("glShaderSource", t);
            }
        }

        static void glCompileShader(int shader) {
            try {
                GLCOMPILESHADER.invokeExact(shader);
            } catch (Throwable t) {
                throw failure("glCompileShader", t);
            }
        }

        static void glGetShaderiv(int shader, int name, MemorySegment params) {
            try {
                GLGETSHADERIV.invokeExact(shader, name, params);
            } catch (Throwable t) {
                throw failure("glGetShaderiv", t);
            }
        }

        static void glGetShaderInfoLog(int shader, int size, MemorySegment length, MemorySegment log) {
            try {
                GLGETSHADERINFOLOG.invokeExact(shader, size, length, log);
            } catch (Throwable t) {
                throw failure("glGetShaderInfoLog", t);
            }
        }

        static void glDeleteShader(int shader) {
            try {
                GLDELETESHADER.invokeExact(shader);
            } catch (Throwable t) {
                throw failure("glDeleteShader", t);
            }
        }

        static int glCreateProgram() {
            try {
                return (int) GLCREATEPROGRAM.invokeExact();
            } catch (Throwable t) {
                throw failure("glCreateProgram", t);
            }
        }

        static void glAttachShader(int program, int shader) {
            try {
                GLATTACHSHADER.invokeExact(program, shader);
            } catch (Throwable t) {
                throw failure("glAttachShader", t);
            }
        }

        static void glDetachShader(int program, int shader) {
            try {
                GLDETACHSHADER.invokeExact(program, shader);
            } catch (Throwable t) {
                throw failure("glDetachShader", t);
            }
        }

        static void glBindAttribLocation(int program, int index, MemorySegment name) {
            try {
                GLBINDATTRIBLOCATION.invokeExact(program, index, name);
            } catch (Throwable t) {
                throw failure("glBindAttribLocation", t);
            }
        }

        static void glLinkProgram(int program) {
            try {
                GLLINKPROGRAM.invokeExact(program);
            } catch (Throwable t) {
                throw failure("glLinkProgram", t);
            }
        }

        static void glGetProgramiv(int program, int name, MemorySegment params) {
            try {
                GLGETPROGRAMIV.invokeExact(program, name, params);
            } catch (Throwable t) {
                throw failure("glGetProgramiv", t);
            }
        }

        static void glGetProgramInfoLog(int program, int size, MemorySegment length, MemorySegment log) {
            try {
                GLGETPROGRAMINFOLOG.invokeExact(program, size, length, log);
            } catch (Throwable t) {
                throw failure("glGetProgramInfoLog", t);
            }
        }

        static void glUseProgram(int program) {
            try {
                GLUSEPROGRAM.invokeExact(program);
            } catch (Throwable t) {
                throw failure("glUseProgram", t);
            }
        }

        static void glDeleteProgram(int program) {
            try {
                GLDELETEPROGRAM.invokeExact(program);
            } catch (Throwable t) {
                throw failure("glDeleteProgram", t);
            }
        }

        static int glGetUniformLocation(int program, MemorySegment name) {
            try {
                return (int) GLGETUNIFORMLOCATION.invokeExact(program, name);
            } catch (Throwable t) {
                throw failure("glGetUniformLocation", t);
            }
        }

        static void glUniform1f(int location, float v0) {
            try {
                GLUNIFORM1F.invokeExact(location, v0);
            } catch (Throwable t) {
                throw failure("glUniform1f", t);
            }
        }

        static void glUniform2f(int location, float v0, float v1) {
            try {
                GLUNIFORM2F.invokeExact(location, v0, v1);
            } catch (Throwable t) {
                throw failure("glUniform2f", t);
            }
        }

        static void glUniform3f(int location, float v0, float v1, float v2) {
            try {
                GLUNIFORM3F.invokeExact(location, v0, v1, v2);
            } catch (Throwable t) {
                throw failure("glUniform3f", t);
            }
        }

        static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
            try {
                GLUNIFORM4F.invokeExact(location, v0, v1, v2, v3);
            } catch (Throwable t) {
                throw failure("glUniform4f", t);
            }
        }

        static void glUniform4fv(int location, int count, MemorySegment value) {
            try {
                GLUNIFORM4FV.invokeExact(location, count, value);
            } catch (Throwable t) {
                throw failure("glUniform4fv", t);
            }
        }

        static void glUniform1i(int location, int v0) {
            try {
                GLUNIFORM1I.invokeExact(location, v0);
            } catch (Throwable t) {
                throw failure("glUniform1i", t);
            }
        }

        static void glUniform2i(int location, int v0, int v1) {
            try {
                GLUNIFORM2I.invokeExact(location, v0, v1);
            } catch (Throwable t) {
                throw failure("glUniform2i", t);
            }
        }

        static void glUniform3i(int location, int v0, int v1, int v2) {
            try {
                GLUNIFORM3I.invokeExact(location, v0, v1, v2);
            } catch (Throwable t) {
                throw failure("glUniform3i", t);
            }
        }

        static void glUniform4i(int location, int v0, int v1, int v2, int v3) {
            try {
                GLUNIFORM4I.invokeExact(location, v0, v1, v2, v3);
            } catch (Throwable t) {
                throw failure("glUniform4i", t);
            }
        }

        static void glUniform4iv(int location, int count, MemorySegment value) {
            try {
                GLUNIFORM4IV.invokeExact(location, count, value);
            } catch (Throwable t) {
                throw failure("glUniform4iv", t);
            }
        }

        static void glUniformMatrix4fv(int location, int count, byte transpose, MemorySegment value) {
            try {
                GLUNIFORMMATRIX4FV.invokeExact(location, count, transpose, value);
            } catch (Throwable t) {
                throw failure("glUniformMatrix4fv", t);
            }
        }

        static void glGenBuffers(int n, MemorySegment buffers) {
            try {
                GLGENBUFFERS.invokeExact(n, buffers);
            } catch (Throwable t) {
                throw failure("glGenBuffers", t);
            }
        }

        static void glDeleteBuffers(int n, MemorySegment buffers) {
            try {
                GLDELETEBUFFERS.invokeExact(n, buffers);
            } catch (Throwable t) {
                throw failure("glDeleteBuffers", t);
            }
        }

        static void glBindBuffer(int target, int buffer) {
            try {
                GLBINDBUFFER.invokeExact(target, buffer);
            } catch (Throwable t) {
                throw failure("glBindBuffer", t);
            }
        }

        static void glBufferData(int target, long size, MemorySegment data, int usage) {
            try {
                GLBUFFERDATA.invokeExact(target, size, data, usage);
            } catch (Throwable t) {
                throw failure("glBufferData", t);
            }
        }

        static void glBufferSubData(int target, long offset, long size, MemorySegment data) {
            try {
                GLBUFFERSUBDATA.invokeExact(target, offset, size, data);
            } catch (Throwable t) {
                throw failure("glBufferSubData", t);
            }
        }

        static void glEnableVertexAttribArray(int index) {
            try {
                GLENABLEVERTEXATTRIBARRAY.invokeExact(index);
            } catch (Throwable t) {
                throw failure("glEnableVertexAttribArray", t);
            }
        }

        static void glDisableVertexAttribArray(int index) {
            try {
                GLDISABLEVERTEXATTRIBARRAY.invokeExact(index);
            } catch (Throwable t) {
                throw failure("glDisableVertexAttribArray", t);
            }
        }

        static void glVertexAttribPointer(int index, int size, int type, byte normalized, int stride, long offset) {
            try {
                GLVERTEXATTRIBPOINTER.invokeExact(index, size, type, normalized, stride, offset);
            } catch (Throwable t) {
                throw failure("glVertexAttribPointer", t);
            }
        }

        static void glDrawElements(int mode, int count, int type, long offset) {
            try {
                GLDRAWELEMENTS.invokeExact(mode, count, type, offset);
            } catch (Throwable t) {
                throw failure("glDrawElements", t);
            }
        }

        static void glEGLImageTargetTexture2DOES(int target, MemorySegment image) {
            try {
                GLEGLIMAGETARGETTEXTURE2DOES.invokeExact(target, image);
            } catch (Throwable t) {
                throw failure("glEGLImageTargetTexture2DOES", t);
            }
        }
    }
}

