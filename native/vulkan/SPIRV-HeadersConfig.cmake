# ggml only requires the package-presence check here; shaderc consumes the
# SPIR-V grammar directly while generating the embedded Vulkan shaders.
set(SPIRV-Headers_FOUND TRUE)
