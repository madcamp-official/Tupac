package com.example.mobileguiagent

import com.example.mobileguiagent.model.ModelCoordinateSpace
import com.example.mobileguiagent.model.OnDeviceModelProfile
import com.example.mobileguiagent.model.OnDeviceModelProfileResolver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OnDeviceModelProfileResolverTest {
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("model-profile-test").toFile()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun noInstallationPointsAtSmallQwenToolProfile() {
        val resolved = OnDeviceModelProfileResolver.resolve(filesDir)

        assertEquals(OnDeviceModelProfileResolver.qwen3Text06B, resolved.profile)
        assertFalse(resolved.ready)
    }

    @Test
    fun absentTextProfileFallsBackToLegacyModel() {
        installModel(OnDeviceModelProfileResolver.qwen3VlTwoB)
        installModel(OnDeviceModelProfileResolver.miniCpmV46)

        val resolved = OnDeviceModelProfileResolver.resolve(filesDir)

        assertEquals(OnDeviceModelProfileResolver.miniCpmV46, resolved.profile)
        assertTrue(resolved.ready)
        assertFalse(resolved.visionAvailable)
    }

    @Test
    fun installedTextToolProfileWinsOverVisualModels() {
        installModel(OnDeviceModelProfileResolver.qwen3Text06B)
        installPair(OnDeviceModelProfileResolver.guiOwl15TwoB)
        installPair(OnDeviceModelProfileResolver.qwen3VlTwoB)

        val resolved = OnDeviceModelProfileResolver.resolve(filesDir)

        assertEquals(OnDeviceModelProfileResolver.qwen3Text06B, resolved.profile)
        assertTrue(resolved.ready)
        assertFalse(resolved.visionAvailable)
        assertTrue(resolved.profile.disableThinking)
    }

    @Test
    fun completeQwenPairWinsOverLegacyModel() {
        installPair(OnDeviceModelProfileResolver.qwen3VlTwoB)
        installPair(OnDeviceModelProfileResolver.miniCpmV46)

        val resolved = OnDeviceModelProfileResolver.resolve(filesDir)

        assertEquals(OnDeviceModelProfileResolver.qwen3VlTwoB, resolved.profile)
        assertTrue(resolved.ready)
        assertTrue(resolved.visionAvailable)
        assertEquals(ModelCoordinateSpace.DEVICE_PIXELS, resolved.profile.coordinateSpace)
        assertTrue(resolved.profile.preferImagePlanning)
    }

    @Test
    fun completeGuiOwlPairHasHighestPriority() {
        installPair(OnDeviceModelProfileResolver.guiOwl15TwoB)
        installPair(OnDeviceModelProfileResolver.qwen3VlTwoB)

        val resolved = OnDeviceModelProfileResolver.resolve(filesDir)

        assertEquals(OnDeviceModelProfileResolver.guiOwl15TwoB, resolved.profile)
        assertEquals(ModelCoordinateSpace.NORMALIZED_1000, resolved.profile.coordinateSpace)
        assertTrue(resolved.profile.preferImagePlanning)
    }

    private fun installPair(profile: OnDeviceModelProfile) {
        installModel(profile)
        profile.visionProjectorFile(filesDir)?.let(::touch)
    }

    private fun installModel(profile: OnDeviceModelProfile) {
        touch(profile.modelFile(filesDir))
    }

    private fun touch(file: File) {
        file.parentFile?.mkdirs()
        assertTrue(file.createNewFile())
    }
}
