package com.uoai.inference

/** Model family shown in the provider's model installer/switcher UI. */
enum class ModelFamily(val label: String) {
    GEMMA_4("Gemma 4"),
    GEMMA_3("Gemma 3"),
}

/** Underlying runtime engine required by a model bundle. */
enum class ModelRuntimeKind(val label: String) {
    LITERT_LM("LiteRT-LM"),
    MEDIAPIPE_TASK("MediaPipe"),
}

/** Metadata describing an officially packaged Hugging Face Gemma bundle. */
data class ModelVariantPreset(
    val id: String,
    val family: ModelFamily,
    val tier: String,
    val label: String,
    val subtitle: String,
    val repo: String,
    val fileName: String,
    val approxSizeBytes: Long,
    val minRecommendedRamGb: Int,
    val maxContextTokens: Int,
    val runtime: ModelRuntimeKind,
    val gated: Boolean,
    val recommendedBackend: AcceleratorBackend = AcceleratorBackend.AUTO,
)

/** Catalog of all official Gemma 4 LiteRT-LM models and Gemma 3 bundles. */
object ModelCatalog {
    val ALL_PRESETS: List<ModelVariantPreset> = listOf(
        // --- Gemma 4 E2B (Effective 2B) ---
        ModelVariantPreset(
            id = "gemma-4-e2b-std",
            family = ModelFamily.GEMMA_4,
            tier = "E2B",
            label = "Gemma 4 E2B · Standard (CPU/GPU)",
            subtitle = "2.41 GB · Apache-2.0 · Best all-round mobile Gemma 4",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            approxSizeBytes = 2_588_100_000L,
            minRecommendedRamGb = 6,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-4-e2b-gpu",
            family = ModelFamily.GEMMA_4,
            tier = "E2B",
            label = "Gemma 4 E2B · GPU Tuned",
            subtitle = "2.18 GB · Apache-2.0 · Compact GPU-optimized build",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it-gpu.litertlm",
            approxSizeBytes = 2_340_840_000L,
            minRecommendedRamGb = 6,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.GPU,
        ),
        ModelVariantPreset(
            id = "gemma-4-e2b-tensor-g5",
            family = ModelFamily.GEMMA_4,
            tier = "E2B",
            label = "Gemma 4 E2B · Google Tensor G5 NPU",
            subtitle = "2.99 GB · Apache-2.0 · Pixel 10 Tensor G5 NPU",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
            approxSizeBytes = 3_206_330_000L,
            minRecommendedRamGb = 8,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.NPU,
        ),
        ModelVariantPreset(
            id = "gemma-4-e2b-tensor-g6",
            family = ModelFamily.GEMMA_4,
            tier = "E2B",
            label = "Gemma 4 E2B · Google Tensor G6 NPU",
            subtitle = "3.22 GB · Apache-2.0 · Tensor G6 NPU precompiled",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
            approxSizeBytes = 3_454_010_000L,
            minRecommendedRamGb = 8,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.NPU,
        ),
        ModelVariantPreset(
            id = "gemma-4-e2b-qcs8275",
            family = ModelFamily.GEMMA_4,
            tier = "E2B",
            label = "Gemma 4 E2B · Qualcomm QCS8275 NPU",
            subtitle = "2.54 GB · Apache-2.0 · Qualcomm NPU build",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it_Qualcomm_QCS8275.litertlm",
            approxSizeBytes = 2_722_320_000L,
            minRecommendedRamGb = 6,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.NPU,
        ),
        ModelVariantPreset(
            id = "gemma-4-e2b-npu",
            family = ModelFamily.GEMMA_4,
            tier = "E2B",
            label = "Gemma 4 E2B · Generic NPU (Uncompiled)",
            subtitle = "2.44 GB · Apache-2.0 · On-device NPU JIT compilation",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it_NPU_Uncompiled.litertlm",
            approxSizeBytes = 2_614_620_000L,
            minRecommendedRamGb = 6,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.NPU,
        ),

        // --- Gemma 4 E4B (Effective 4B) ---
        ModelVariantPreset(
            id = "gemma-4-e4b-std",
            family = ModelFamily.GEMMA_4,
            tier = "E4B",
            label = "Gemma 4 E4B · Standard (CPU/GPU)",
            subtitle = "3.41 GB · Apache-2.0 · Higher reasoning capability",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            approxSizeBytes = 3_659_530_000L,
            minRecommendedRamGb = 8,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-4-e4b-gpu",
            family = ModelFamily.GEMMA_4,
            tier = "E4B",
            label = "Gemma 4 E4B · GPU Tuned",
            subtitle = "3.08 GB · Apache-2.0 · Compact GPU build",
            repo = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it-gpu.litertlm",
            approxSizeBytes = 3_310_980_000L,
            minRecommendedRamGb = 8,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.GPU,
        ),

        // --- Gemma 4 12B ---
        ModelVariantPreset(
            id = "gemma-4-12b-std",
            family = ModelFamily.GEMMA_4,
            tier = "12B",
            label = "Gemma 4 12B · Standard (CPU/GPU)",
            subtitle = "6.41 GB · Apache-2.0 · Flagship 12 GB+ RAM devices",
            repo = "litert-community/gemma-4-12B-it-litert-lm",
            fileName = "gemma-4-12B-it.litertlm",
            approxSizeBytes = 6_883_270_000L,
            minRecommendedRamGb = 12,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-4-12b-gpu",
            family = ModelFamily.GEMMA_4,
            tier = "12B",
            label = "Gemma 4 12B · GPU Tuned",
            subtitle = "5.77 GB · Apache-2.0 · Flagship GPU acceleration",
            repo = "litert-community/gemma-4-12B-it-litert-lm",
            fileName = "gemma-4-12B-it-gpu.litertlm",
            approxSizeBytes = 6_195_720_000L,
            minRecommendedRamGb = 12,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.GPU,
        ),

        // --- Gemma 4 26B-A4B MoE ---
        ModelVariantPreset(
            id = "gemma-4-26b-a4b-gpu",
            family = ModelFamily.GEMMA_4,
            tier = "26B-A4B",
            label = "Gemma 4 26B-A4B MoE · GPU",
            subtitle = "15.27 GB · Apache-2.0 · 24 GB+ RAM / Tablet / Edge",
            repo = "litert-community/gemma-4-26B-A4B-it-litert-lm",
            fileName = "gemma-4-26B-A4B-it-gpu.litertlm",
            approxSizeBytes = 16_391_650_000L,
            minRecommendedRamGb = 24,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.GPU,
        ),

        // --- Gemma 4 31B ---
        ModelVariantPreset(
            id = "gemma-4-31b-gpu",
            family = ModelFamily.GEMMA_4,
            tier = "31B",
            label = "Gemma 4 31B · GPU",
            subtitle = "18.47 GB · Apache-2.0 · High-RAM Android / Edge Workstation",
            repo = "litert-community/gemma-4-31B-it-litert-lm",
            fileName = "gemma-4-31B-it-gpu.litertlm",
            approxSizeBytes = 19_834_960_000L,
            minRecommendedRamGb = 24,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = false,
            recommendedBackend = AcceleratorBackend.GPU,
        ),

        // --- Gemma 3 1B ---
        ModelVariantPreset(
            id = "gemma-3-1b-task",
            family = ModelFamily.GEMMA_3,
            tier = "1B",
            label = "Gemma 3 1B INT4 (MediaPipe .task)",
            subtitle = "529 MB · Gated license · Lightweight default",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.task",
            approxSizeBytes = 554_661_243L,
            minRecommendedRamGb = 4,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.MEDIAPIPE_TASK,
            gated = true,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-3-1b-litertlm",
            family = ModelFamily.GEMMA_3,
            tier = "1B",
            label = "Gemma 3 1B INT4 (.litertlm)",
            subtitle = "557 MB · Gated license · LiteRT-LM engine",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "gemma3-1b-it-int4.litertlm",
            approxSizeBytes = 584_417_280L,
            minRecommendedRamGb = 4,
            maxContextTokens = 32_768,
            runtime = ModelRuntimeKind.LITERT_LM,
            gated = true,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-3-1b-q4-ekv2048",
            family = ModelFamily.GEMMA_3,
            tier = "1B",
            label = "Gemma 3 1B Q4 · 2K KV (.task)",
            subtitle = "529 MB · Gated license · Multi-prefill 2K context",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task",
            approxSizeBytes = 554_661_246L,
            minRecommendedRamGb = 4,
            maxContextTokens = 2048,
            runtime = ModelRuntimeKind.MEDIAPIPE_TASK,
            gated = true,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-3-1b-q4-ekv4096",
            family = ModelFamily.GEMMA_3,
            tier = "1B",
            label = "Gemma 3 1B Q4 · 4K KV (.task)",
            subtitle = "657 MB · Gated license · Multi-prefill 4K context",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "Gemma3-1B-IT_multi-prefill-seq_q4_block128_ekv4096.task",
            approxSizeBytes = 689_297_398L,
            minRecommendedRamGb = 4,
            maxContextTokens = 4096,
            runtime = ModelRuntimeKind.MEDIAPIPE_TASK,
            gated = true,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
        ModelVariantPreset(
            id = "gemma-3-1b-q8-ekv4096",
            family = ModelFamily.GEMMA_3,
            tier = "1B",
            label = "Gemma 3 1B Q8 · 4K KV (.task)",
            subtitle = "~1.0 GB · Gated license · Higher precision 4K context",
            repo = "litert-community/Gemma3-1B-IT",
            fileName = "Gemma3-1B-IT_multi-prefill-seq_q8_ekv4096.task",
            approxSizeBytes = 1_073_741_824L,
            minRecommendedRamGb = 6,
            maxContextTokens = 4096,
            runtime = ModelRuntimeKind.MEDIAPIPE_TASK,
            gated = true,
            recommendedBackend = AcceleratorBackend.AUTO,
        ),
    )

    fun byFamily(family: ModelFamily): List<ModelVariantPreset> =
        ALL_PRESETS.filter { it.family == family }

    fun findPreset(repo: String, fileName: String): ModelVariantPreset? =
        ALL_PRESETS.firstOrNull {
            it.repo.equals(repo.trim(), ignoreCase = true) && it.fileName.equals(fileName.trim(), ignoreCase = true)
        }

    fun requiresHfToken(repo: String, fileName: String): Boolean =
        findPreset(repo, fileName)?.gated ?: false

    fun inferModelId(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.contains("gemma-4-e2b") || lower.contains("gemma4-e2b") -> "gemma-4-e2b"
            lower.contains("gemma-4-e4b") || lower.contains("gemma4-e4b") -> "gemma-4-e4b"
            lower.contains("gemma-4-12b") || lower.contains("gemma4-12b") -> "gemma-4-12b"
            lower.contains("gemma-4-26b") || lower.contains("gemma4-26b") -> "gemma-4-26b-a4b"
            lower.contains("gemma-4-31b") || lower.contains("gemma4-31b") -> "gemma-4-31b"
            lower.contains("gemma3") || lower.contains("gemma-3") -> "gemma-3"
            else -> lower.substringBeforeLast('.').replace(Regex("[^a-z0-9._-]"), "-").ifBlank { "local-model" }
        }
    }
}

