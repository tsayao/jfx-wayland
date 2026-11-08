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

import java.util.HashMap;
import java.util.Map;

/**
 * The Phong shaders of the meshes: a fragment shader for each combination
 * of the maps of the material and of the number of lights, made of the
 * shader parts in glsl/ (where an underscore denotes a part), and the
 * vertex shader main.vert.
 */
class ES2PhongShader {

    enum DiffuseState {
        NONE("diffuse_none"),
        DIFFUSECOLOR("diffuse_color"),
        TEXTURE("diffuse_texture");

        final String part;

        DiffuseState(String file) {
            part = readPart(file);
        }
    }

    enum SpecularState {
        NONE("specular_none"),
        TEXTURE("specular_texture"),
        COLOR("specular_color"),
        MIX("specular_mix");

        final String part;

        SpecularState(String file) {
            part = readPart(file);
        }
    }

    enum SelfIllumState {
        NONE("selfIllum_none"),
        TEXTURE("selfIllum_texture");

        final String part;

        SelfIllumState(String file) {
            part = readPart(file);
        }
    }

    enum BumpMapState {
        NONE("normalMap_none"),
        TEXTURE("normalMap_texture");

        final String part;

        BumpMapState(String file) {
            part = readPart(file);
        }
    }

    /** What a shader is made of. */
    private record Key(DiffuseState diffuse, SpecularState specular, SelfIllumState selfIllum,
                       BumpMapState bump, int lights) {

        String fragmentSource() {
            return LIGHTING_PARTS[lights]
                    .replace("vec4 apply_diffuse();", diffuse.part)
                    .replace("vec4 apply_specular();", specular.part)
                    .replace("vec3 apply_normal();", bump.part)
                    .replace("vec4 apply_selfIllum();", selfIllum.part);
        }
    }

    private static final String[] LIGHTING_PARTS = {
        readPart("main0Lights"), readPart("main1Light"), readPart("main2Lights"), readPart("main3Lights")
    };
    private static final String VERTEX_SOURCE = ES2Shader.readStreamIntoString(
            ES2ResourceFactory.class.getResourceAsStream("glsl/main.vert"));
    private static final Map<String, Integer> ATTRIBUTES = Map.of("pos", 0, "texCoords", 1, "tangent", 2);
    private static final Map<String, Integer> SAMPLERS = Map.of(
            "diffuseTexture", 0, "specularMap", 1, "normalMap", 2, "selfIllumTexture", 3);

    private static final Map<Key, ES2Shader> shaders = new HashMap<>();

    private static String readPart(String name) {
        return ES2Shader.readStreamIntoString(ES2ResourceFactory.class.getResourceAsStream("glsl/" + name + ".frag"));
    }

    static SpecularState getSpecularState(ES2PhongMaterial material) {
        boolean texture = material.maps[ES2PhongMaterial.SPECULAR].getTexture() != null;
        if (texture) {
            return material.specularColorSet ? SpecularState.MIX : SpecularState.TEXTURE;
        }
        return material.specularColorSet ? SpecularState.COLOR : SpecularState.NONE;
    }

    private static boolean hasMap(ES2PhongMaterial material, int type) {
        return material.maps[type].getTexture() != null;
    }

    private static Key keyOf(ES2MeshView meshView) {
        ES2PhongMaterial material = meshView.getMaterial();
        return new Key(
                hasMap(material, ES2PhongMaterial.DIFFUSE) ? DiffuseState.TEXTURE : DiffuseState.DIFFUSECOLOR,
                getSpecularState(material),
                hasMap(material, ES2PhongMaterial.SELF_ILLUM) ? SelfIllumState.TEXTURE : SelfIllumState.NONE,
                hasMap(material, ES2PhongMaterial.BUMP) ? BumpMapState.TEXTURE : BumpMapState.NONE,
                lightCount(meshView));
    }

    private static int lightCount(ES2MeshView meshView) {
        int count = 0;
        for (ES2Light light : meshView.getLights()) {
            if (isOn(light)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isOn(ES2Light light) {
        return light != null && light.w > 0;
    }

    static ES2Shader getShader(ES2MeshView meshView, ES2Context context) {
        return shaders.computeIfAbsent(keyOf(meshView), key -> ES2Shader.createFromSource(context, VERTEX_SOURCE,
                new String[] {key.fragmentSource()}, SAMPLERS, ATTRIBUTES, 1, false));
    }

    static void setShaderParamaters(ES2Shader shader, ES2MeshView meshView, ES2Context context) {
        ES2PhongMaterial material = meshView.getMaterial();
        shader.setConstant("diffuseColor", material.diffuseColor.getRed(),
                material.diffuseColor.getGreen(), material.diffuseColor.getBlue(),
                material.diffuseColor.getAlpha());
        shader.setConstant("specularColor", material.specularColor.getRed(),
                material.specularColor.getGreen(), material.specularColor.getBlue(),
                material.specularColor.getAlpha());
        context.updateTexture(0, material.maps[ES2PhongMaterial.DIFFUSE].getTexture());
        context.updateTexture(1, material.maps[ES2PhongMaterial.SPECULAR].getTexture());
        context.updateTexture(2, material.maps[ES2PhongMaterial.BUMP].getTexture());
        context.updateTexture(3, material.maps[ES2PhongMaterial.SELF_ILLUM].getTexture());
        shader.setConstant("ambientColor", meshView.getAmbientLightRed(),
                meshView.getAmbientLightGreen(), meshView.getAmbientLightBlue());
        ES2Light[] lights = meshView.getLights();
        for (int i = 0; i < lights.length; i++) {
            if (isOn(lights[i])) {
                setLightConstants(i, shader, lights[i]);
            }
        }
    }

    private static void setLightConstants(int i, ES2Shader shader, ES2Light light) {
        String prefix = "lights[" + i + "].";
        shader.setConstant(prefix + "pos", light.x, light.y, light.z, light.w);
        shader.setConstant(prefix + "color", light.r, light.g, light.b);
        shader.setConstant(prefix + "attn", light.ca, light.la, light.qa, light.isAttenuated);
        shader.setConstant(prefix + "range", light.maxRange);
        setLightDirection(prefix, shader, light);
        setLightCone(prefix, shader, light);
    }

    private static void setLightDirection(String prefix, ES2Shader shader, ES2Light light) {
        if (light.isPointLight()) {
            shader.setConstant(prefix + "dir", 0f, 0f, 1f);
            return;
        }
        float length = (float) Math.sqrt(light.dirX * light.dirX + light.dirY * light.dirY + light.dirZ * light.dirZ);
        shader.setConstant(prefix + "dir", light.dirX / length, light.dirY / length, light.dirZ / length);
    }

    /** For I = pow((cosAngle - cosOuter) / (cosInner - cosOuter), falloff). */
    private static void setLightCone(String prefix, ES2Shader shader, ES2Light light) {
        if (light.isPointLight() || light.isDirectionalLight()) {
            shader.setConstant(prefix + "cosOuter", -1f); // cos(180)
            shader.setConstant(prefix + "denom", 2f);     // cos(0) - cos(180)
            shader.setConstant(prefix + "falloff", 0f);
            return;
        }
        float cosOuter = (float) Math.cos(Math.toRadians(light.outerAngle));
        float cosInner = (float) Math.cos(Math.toRadians(light.innerAngle));
        shader.setConstant(prefix + "cosOuter", cosOuter);
        shader.setConstant(prefix + "denom", cosInner - cosOuter);
        shader.setConstant(prefix + "falloff", light.falloff);
    }
}
