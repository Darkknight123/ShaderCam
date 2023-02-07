package com.skamz.shadercam.logic.shaders.camera_view_defaults

import com.skamz.shadercam.logic.shaders.util.ColorShaderParam
import com.skamz.shadercam.logic.shaders.util.ShaderParam
import com.skamz.shadercam.logic.shaders.util.ShaderAttributes

class TintShaderData {
    companion object {
        val shaderMainText: String = """
        void main() {
            vec2 uv = vTextureCoord;            
            vec4 color = texture2D(sTexture, vTextureCoord);
            gl_FragColor = vec4(tint, 1.0) * color;
        }
    """.trimIndent()

        val params: MutableList<ShaderParam> = mutableListOf(
            ColorShaderParam("tint", android.graphics.Color.BLUE)
        )

    }
}

val TintShader = ShaderAttributes(
    "002 - Tint - Color Param Example",
    TintShaderData.shaderMainText,
    TintShaderData.params,
)