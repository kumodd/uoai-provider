package com.uoai.asr

import java.io.File

interface AsrModelRepository {
    fun modelsDir(): File
    fun listInstalled(): Map<AsrProfile, InstalledAsrModel>
    fun fileFor(profile: AsrProfile): File?
    fun targetFile(preset: AsrModelPreset): File
    fun recordInstalled(entry: InstalledAsrModel)
    fun delete(profile: AsrProfile): Boolean
    fun totalStorageBytes(): Long
}

