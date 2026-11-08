/*
 * Copyright (c) 2013, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.sun.prism.es2egl;

import com.sun.prism.Graphics;
import com.sun.prism.Material;
import com.sun.prism.impl.BaseMeshView;

/**
 * TODO: 3D - Need documentation
 */
class ES2MeshView extends BaseMeshView {

    static int count = 0;
    private final ES2Context context;
    private int cullingMode = com.sun.prism.MeshView.CULL_BACK;
    private boolean wireframe;
    private float ambientLightRed = 0;
    private float ambientLightBlue = 0;
    private float ambientLightGreen = 0;

    // NOTE: We only support up to 3 point lights at the present
    private ES2Light[] lights = new ES2Light[3];

    // TODO: 3D - Need a mechanism to "decRefCount" Mesh and Material
    //            if we need to do eager clean up
    final private ES2Mesh mesh;
    private ES2PhongMaterial material;

    private ES2MeshView(ES2Context context, ES2Mesh mesh) {
        // The mesh view has no resource of its own
        super(() -> { });
        this.context = context;
        this.mesh = mesh;
        count++;
    }

    static ES2MeshView create(ES2Context context, ES2Mesh mesh) {
        return new ES2MeshView(context, mesh);
    }

    @Override
    public void setCullingMode(int cullingMode) {
        if (cullingMode != CULL_NONE && cullingMode != CULL_BACK && cullingMode != CULL_FRONT) {
            throw new IllegalArgumentException("illegal value for CullMode: " + cullingMode);
        }
        this.cullingMode = cullingMode;
    }

    int getCullingMode() {
        return cullingMode;
    }

    @Override
    public void setMaterial(Material material) {
        this.material = (ES2PhongMaterial) material;
    }

    @Override
    public void setWireframe(boolean wireframe) {
        this.wireframe = wireframe;
    }

    boolean isWireframe() {
        return wireframe;
    }

    ES2Mesh getMesh() {
        return mesh;
    }

    @Override
    public void setAmbientLight(float r, float g, float b) {
        ambientLightRed = r;
        ambientLightGreen = g;
        ambientLightBlue = b;
    }

    float getAmbientLightRed() {
        return ambientLightRed;
    }

    float getAmbientLightGreen() {
        return ambientLightGreen;
    }

    float getAmbientLightBlue() {
        return ambientLightBlue;
    }

    @Override
    public void setLight(int index, float x, float y, float z, float r, float g, float b, float w,
            float ca, float la, float qa, float isAttenuated, float maxRange, float dirX, float dirY, float dirZ,
            float innerAngle, float outerAngle, float falloff) {
        // NOTE: We only support up to 3 point lights at the present
        if (index >= 0 && index <= 2) {
            lights[index] = new ES2Light(x, y, z, r, g, b, w, ca, la, qa, isAttenuated,
                    maxRange, dirX, dirY, dirZ, innerAngle, outerAngle, falloff);
        }
    }

    ES2Light[] getLights() {
        return lights;
    }

    @Override
    public void render(Graphics g) {
        material.lockTextureMaps();
        context.renderMeshView(g, this);
        material.unlockTextureMaps();
    }

    ES2PhongMaterial getMaterial() {
        return material;
    }

    @Override
    public void dispose() {
        // TODO: 3D - Need a mechanism to "decRefCount" Mesh and Material
        material = null;
        lights = null;
        disposerRecord.dispose();
        count--;
    }

    public int getCount() {
        return count;
    }
}
