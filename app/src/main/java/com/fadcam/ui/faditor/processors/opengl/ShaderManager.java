package com.fadcam.ui.faditor.processors.opengl;

import android.opengl.GLES20;
import android.util.Log;
import android.util.SparseArray;

import com.fadcam.opengl.grafika.GlUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Video rendering shader compilation and caching manager.
 * Handles shader program creation, compilation, caching, and uniform/attribute management.
 * Requirements: 13.3, 13.5, 14.1, 14.4
 */
public class ShaderManager {
    
    private static final String TAG = "ShaderManager";
    
    // Shader program types
    public enum ShaderType {
        VIDEO_EXTERNAL,     // For external OES textures (video)
        VIDEO_2D,          // For regular 2D textures
        COLOR_CONVERSION,  // For color space conversion
        EFFECTS            // For video effects
    }
    
    // Video shader for external OES textures
    private static final String VIDEO_VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";
    
    private static final String VIDEO_FRAGMENT_SHADER_EXTERNAL =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";
    
    // Regular 2D texture shader
    private static final String VIDEO_FRAGMENT_SHADER_2D =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";
    
    // Color conversion shader (YUV to RGB)
    private static final String COLOR_CONVERSION_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform sampler2D yTexture;\n" +
            "uniform sampler2D uTexture;\n" +
            "uniform sampler2D vTexture;\n" +
            "void main() {\n" +
            "    float y = texture2D(yTexture, vTextureCoord).r;\n" +
            "    float u = texture2D(uTexture, vTextureCoord).r - 0.5;\n" +
            "    float v = texture2D(vTexture, vTextureCoord).r - 0.5;\n" +
            "    float r = y + 1.402 * v;\n" +
            "    float g = y - 0.344 * u - 0.714 * v;\n" +
            "    float b = y + 1.772 * u;\n" +
            "    gl_FragColor = vec4(r, g, b, 1.0);\n" +
            "}\n";
    
    // Effects shader (basic brightness/contrast)
    private static final String EFFECTS_FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform float uBrightness;\n" +
            "uniform float uContrast;\n" +
            "uniform float uSaturation;\n" +
            "void main() {\n" +
            "    vec4 color = texture2D(sTexture, vTextureCoord);\n" +
            "    \n" +
            "    // Apply brightness\n" +
            "    color.rgb += uBrightness;\n" +
            "    \n" +
            "    // Apply contrast\n" +
            "    color.rgb = (color.rgb - 0.5) * uContrast + 0.5;\n" +
            "    \n" +
            "    // Apply saturation\n" +
            "    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));\n" +
            "    color.rgb = mix(vec3(gray), color.rgb, uSaturation);\n" +
            "    \n" +
            "    gl_FragColor = color;\n" +
            "}\n";
    
    // Shader program information
    private static class ShaderProgramInfo {
        final int programId;
        final ShaderType type;
        final Map<String, Integer> attributeLocations;
        final Map<String, Integer> uniformLocations;
        
        ShaderProgramInfo(int programId, ShaderType type) {
            this.programId = programId;
            this.type = type;
            this.attributeLocations = new HashMap<>();
            this.uniformLocations = new HashMap<>();
        }
    }
    
    // Cached shader programs
    private final SparseArray<ShaderProgramInfo> shaderPrograms;
    private final Map<ShaderType, Integer> shaderTypeToId;
    
    // Default video shader program ID
    private int defaultVideoShaderProgram = 0;
    
    public ShaderManager() {
        this.shaderPrograms = new SparseArray<>();
        this.shaderTypeToId = new HashMap<>();
        
        // Initialize default shaders
        initializeDefaultShaders();
        
        Log.d(TAG, "ShaderManager initialized");
    }
    
    /**
     * Initialize default shader programs
     */
    private void initializeDefaultShaders() {
        // Create video external shader
        int videoExternalProgram = createShaderProgram(ShaderType.VIDEO_EXTERNAL);
        if (videoExternalProgram != 0) {
            defaultVideoShaderProgram = videoExternalProgram;
            Log.d(TAG, "Default video shader program created: " + videoExternalProgram);
        }
        
        // Create 2D texture shader
        createShaderProgram(ShaderType.VIDEO_2D);
        
        // Create color conversion shader
        createShaderProgram(ShaderType.COLOR_CONVERSION);
        
        // Create effects shader
        createShaderProgram(ShaderType.EFFECTS);
    }
    
    /**
     * Create a shader program of the specified type
     */
    public int createShaderProgram(ShaderType type) {
        String vertexShader = VIDEO_VERTEX_SHADER;
        String fragmentShader;
        
        switch (type) {
            case VIDEO_EXTERNAL:
                fragmentShader = VIDEO_FRAGMENT_SHADER_EXTERNAL;
                break;
            case VIDEO_2D:
                fragmentShader = VIDEO_FRAGMENT_SHADER_2D;
                break;
            case COLOR_CONVERSION:
                fragmentShader = COLOR_CONVERSION_FRAGMENT_SHADER;
                break;
            case EFFECTS:
                fragmentShader = EFFECTS_FRAGMENT_SHADER;
                break;
            default:
                Log.e(TAG, "Unknown shader type: " + type);
                return 0;
        }
        
        return createShaderProgram(vertexShader, fragmentShader, type);
    }
    
    /**
     * Create a custom shader program with provided vertex and fragment shaders
     */
    public int createShaderProgram(String vertexShaderSource, String fragmentShaderSource, ShaderType type) {
        int programId = GlUtil.createProgram(vertexShaderSource, fragmentShaderSource);
        if (programId == 0) {
            Log.e(TAG, "Failed to create shader program for type: " + type);
            return 0;
        }
        
        // Cache the program
        ShaderProgramInfo programInfo = new ShaderProgramInfo(programId, type);
        shaderPrograms.put(programId, programInfo);
        shaderTypeToId.put(type, programId);
        
        // Cache common attribute and uniform locations
        cacheCommonLocations(programInfo);
        
        Log.d(TAG, "Created shader program: " + programId + " for type: " + type);
        return programId;
    }
    
    /**
     * Get the default video shader program
     */
    public int getVideoShaderProgram() {
        return defaultVideoShaderProgram;
    }
    
    /**
     * Get shader program by type
     */
    public int getShaderProgram(ShaderType type) {
        Integer programId = shaderTypeToId.get(type);
        return programId != null ? programId : 0;
    }
    
    /**
     * Use a shader program
     */
    public void useProgram(int programId) {
        GLES20.glUseProgram(programId);
        GlUtil.checkGlError("glUseProgram");
    }
    
    /**
     * Get attribute location (cached)
     */
    public int getAttributeLocation(int programId, String attributeName) {
        ShaderProgramInfo programInfo = shaderPrograms.get(programId);
        if (programInfo == null) {
            Log.w(TAG, "Unknown shader program: " + programId);
            return -1;
        }
        
        Integer location = programInfo.attributeLocations.get(attributeName);
        if (location == null) {
            location = GLES20.glGetAttribLocation(programId, attributeName);
            if (location >= 0) {
                programInfo.attributeLocations.put(attributeName, location);
            } else {
                Log.w(TAG, "Attribute not found: " + attributeName + " in program " + programId);
            }
        }
        
        return location;
    }
    
    /**
     * Get uniform location (cached)
     */
    public int getUniformLocation(int programId, String uniformName) {
        ShaderProgramInfo programInfo = shaderPrograms.get(programId);
        if (programInfo == null) {
            Log.w(TAG, "Unknown shader program: " + programId);
            return -1;
        }
        
        Integer location = programInfo.uniformLocations.get(uniformName);
        if (location == null) {
            location = GLES20.glGetUniformLocation(programId, uniformName);
            if (location >= 0) {
                programInfo.uniformLocations.put(uniformName, location);
            } else {
                Log.w(TAG, "Uniform not found: " + uniformName + " in program " + programId);
            }
        }
        
        return location;
    }
    
    /**
     * Set uniform float value
     */
    public void setUniform(int programId, String uniformName, float value) {
        int location = getUniformLocation(programId, uniformName);
        if (location >= 0) {
            GLES20.glUniform1f(location, value);
            GlUtil.checkGlError("glUniform1f");
        }
    }
    
    /**
     * Set uniform float array value
     */
    public void setUniform(int programId, String uniformName, float[] values) {
        int location = getUniformLocation(programId, uniformName);
        if (location >= 0) {
            if (values.length == 16) {
                // Matrix4
                GLES20.glUniformMatrix4fv(location, 1, false, values, 0);
            } else if (values.length == 4) {
                // Vector4
                GLES20.glUniform4fv(location, 1, values, 0);
            } else if (values.length == 3) {
                // Vector3
                GLES20.glUniform3fv(location, 1, values, 0);
            } else if (values.length == 2) {
                // Vector2
                GLES20.glUniform2fv(location, 1, values, 0);
            }
            GlUtil.checkGlError("glUniform array");
        }
    }
    
    /**
     * Set uniform integer value
     */
    public void setUniform(int programId, String uniformName, int value) {
        int location = getUniformLocation(programId, uniformName);
        if (location >= 0) {
            GLES20.glUniform1i(location, value);
            GlUtil.checkGlError("glUniform1i");
        }
    }
    
    /**
     * Set video effects parameters
     */
    public void setVideoEffects(int programId, float brightness, float contrast, float saturation) {
        setUniform(programId, "uBrightness", brightness);
        setUniform(programId, "uContrast", contrast);
        setUniform(programId, "uSaturation", saturation);
    }
    
    /**
     * Check if shader program exists
     */
    public boolean hasShaderProgram(int programId) {
        return shaderPrograms.get(programId) != null;
    }
    
    /**
     * Check if shader program exists for type
     */
    public boolean hasShaderProgram(ShaderType type) {
        return shaderTypeToId.containsKey(type);
    }
    
    /**
     * Get shader program type
     */
    public ShaderType getShaderType(int programId) {
        ShaderProgramInfo programInfo = shaderPrograms.get(programId);
        return programInfo != null ? programInfo.type : null;
    }
    
    /**
     * Release a specific shader program
     */
    public void releaseProgram(int programId) {
        ShaderProgramInfo programInfo = shaderPrograms.get(programId);
        if (programInfo != null) {
            GLES20.glDeleteProgram(programId);
            shaderPrograms.remove(programId);
            shaderTypeToId.remove(programInfo.type);
            
            Log.d(TAG, "Released shader program: " + programId);
        }
    }
    
    /**
     * Release all shader programs
     */
    public void release() {
        Log.d(TAG, "Releasing all shader programs");
        
        for (int i = 0; i < shaderPrograms.size(); i++) {
            int programId = shaderPrograms.keyAt(i);
            GLES20.glDeleteProgram(programId);
        }
        
        shaderPrograms.clear();
        shaderTypeToId.clear();
        defaultVideoShaderProgram = 0;
        
        Log.d(TAG, "All shader programs released");
    }
    
    /**
     * Get number of cached shader programs
     */
    public int getShaderProgramCount() {
        return shaderPrograms.size();
    }
    
    // Private helper methods
    
    /**
     * Cache common attribute and uniform locations for performance
     */
    private void cacheCommonLocations(ShaderProgramInfo programInfo) {
        int programId = programInfo.programId;
        
        // Common attributes
        cacheAttributeLocation(programInfo, "aPosition");
        cacheAttributeLocation(programInfo, "aTextureCoord");
        
        // Common uniforms
        cacheUniformLocation(programInfo, "uMVPMatrix");
        cacheUniformLocation(programInfo, "uTexMatrix");
        cacheUniformLocation(programInfo, "sTexture");
        
        // Type-specific uniforms
        switch (programInfo.type) {
            case COLOR_CONVERSION:
                cacheUniformLocation(programInfo, "yTexture");
                cacheUniformLocation(programInfo, "uTexture");
                cacheUniformLocation(programInfo, "vTexture");
                break;
            case EFFECTS:
                cacheUniformLocation(programInfo, "uBrightness");
                cacheUniformLocation(programInfo, "uContrast");
                cacheUniformLocation(programInfo, "uSaturation");
                break;
        }
    }
    
    private void cacheAttributeLocation(ShaderProgramInfo programInfo, String attributeName) {
        int location = GLES20.glGetAttribLocation(programInfo.programId, attributeName);
        if (location >= 0) {
            programInfo.attributeLocations.put(attributeName, location);
        }
    }
    
    private void cacheUniformLocation(ShaderProgramInfo programInfo, String uniformName) {
        int location = GLES20.glGetUniformLocation(programInfo.programId, uniformName);
        if (location >= 0) {
            programInfo.uniformLocations.put(uniformName, location);
        }
    }
}